package com.androidguru.agent.shell.control

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * RFC 6455 WebSocket 连接（服务端帧）—— 自研实现，零第三方依赖。
 *
 * 保留 yl-ai 的实现要点：
 * - 握手：`Sec-WebSocket-Accept = base64(SHA1(key + GUID))`（与 RFC 示例值一致）；
 * - 服务端帧不加掩码；客户端帧 4 字节 XOR 解掩码；
 * - 帧上限 4MB（防恶意超长帧耗尽内存）。
 *
 * 修复 issue #15（RFC 6455 合规）：
 * - **分帧续传**：TEXT/BINARY + FIN=0 后跟 CONT 帧的完整组装（旧实现首分片被当完整
 *   消息返回、后续 CONT 直接抛异常杀连接）；控制帧（PING/PONG/CLOSE）允许插在分片之间；
 * - **64 位长度符号安全**：长度为负（首字节 ≥0x80）直接拒绝，不再触发 NegativeArraySize；
 * - **未掩码客户端帧拒收**（RFC 6455 §5.1 要求服务端 fail-close）。
 *
 * 支持握手前已被上游 peek 过部分字节的场景（[handshakeWith]）。
 */
class WsConnection(
    private val input: InputStream,
    private val output: OutputStream,
    private val maxFrameBytes: Int = 4 * 1024 * 1024,
) {

    /** 从流上读取 HTTP 升级头并完成握手。 */
    fun handshake(): Boolean {
        val headers = readHttpHeaders() ?: return false
        return respondHandshake(headers)
    }

    /** 用已读到的原始头文本完成握手（上游 peek 过头部字节的场景）。 */
    fun handshakeWith(rawHead: String): Boolean = respondHandshake(parseHeaders(rawHead))

    private fun respondHandshake(headers: Map<String, String>): Boolean {
        val key = headers["sec-websocket-key"] ?: return false
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((key + WS_GUID).toByteArray(Charsets.US_ASCII)),
        )
        val response = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: $accept\r\n")
            append("\r\n")
        }
        output.write(response.toByteArray(Charsets.US_ASCII))
        output.flush()
        return true
    }

    private fun parseHeaders(raw: String): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        val lines = raw.split("\r\n").filter { it.isNotBlank() }
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) {
                headers[lines[i].substring(0, idx).trim().lowercase()] =
                    lines[i].substring(idx + 1).trim()
            }
        }
        return headers
    }

    fun sendText(text: String): Boolean = sendFrame(OPCODE_TEXT, text.toByteArray(Charsets.UTF_8))

    fun close() {
        runCatching { sendFrame(OPCODE_CLOSE, ByteArray(0)) }
        runCatching { output.flush() }
    }

    private class Frame(val opcode: Int, val fin: Boolean, val payload: ByteArray)

    /** 读取一条消息（自动组装分片）。返回 null = 对端关闭。 */
    fun readText(): String? {
        val data = readMessage() ?: return null
        return String(data, Charsets.UTF_8)
    }

    /** 读取一条完整消息（分片组装 + 控制帧处理）。返回 null = 对端关闭。 */
    private fun readMessage(): ByteArray? {
        var assembled: ByteArray? = null
        while (true) {
            val frame = readFrame() ?: return null
            when (frame.opcode) {
                OPCODE_TEXT -> {
                    if (assembled != null) {
                        throw IllegalStateException("分片消息进行中收到新的数据帧（协议错误）")
                    }
                    if (frame.fin) return frame.payload
                    assembled = frame.payload
                }

                OPCODE_CONT -> {
                    if (assembled == null) {
                        throw IllegalStateException("收到无起点的分片续帧（协议错误）")
                    }
                    val merged = ByteArray(assembled.size + frame.payload.size)
                    assembled.copyInto(merged)
                    frame.payload.copyInto(merged, assembled.size)
                    if (merged.size > maxFrameBytes) {
                        throw IllegalStateException("分片消息总长超限：${merged.size} 字节")
                    }
                    assembled = merged
                    if (frame.fin) return assembled
                }

                OPCODE_CLOSE -> {
                    runCatching { sendFrame(OPCODE_CLOSE, ByteArray(0)) }
                    return null
                }
                OPCODE_PING -> sendFrame(OPCODE_PONG, frame.payload)
                OPCODE_PONG -> Unit
                else -> Unit
            }
        }
    }

    private fun sendFrame(opcode: Int, payload: ByteArray): Boolean {
        return try {
            val header = ArrayList<Byte>(10)
            header.add((0x80 or opcode).toByte())
            when {
                payload.size < 126 -> header.add(payload.size.toByte())
                payload.size < 65536 -> {
                    header.add(126.toByte())
                    header.add(((payload.size shr 8) and 0xFF).toByte())
                    header.add((payload.size and 0xFF).toByte())
                }
                else -> {
                    header.add(127.toByte())
                    for (i in 7 downTo 0) {
                        header.add(((payload.size.toLong() shr (8 * i)) and 0xFF).toByte())
                    }
                }
            }
            synchronized(output) {
                output.write(header.toByteArray())
                output.write(payload)
                output.flush()
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun readFrame(): Frame? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) return null

        val opcode = b0 and 0x0F
        val fin = (b0 and 0x80) != 0
        val rsv = b0 and 0x70
        if (rsv != 0) throw IllegalStateException("RSV 位非零（未协商的扩展），协议错误")
        val masked = (b1 and 0x80) != 0
        // RFC 6455 §5.1：客户端发给服务端的帧必须加掩码，未掩码帧 fail-close
        if (!masked) throw IllegalStateException("客户端帧未加掩码（RFC 6455 §5.1），拒绝连接")
        var len = (b1 and 0x7F).toLong()

        if (len == 126L) {
            len = ((readByte() shl 8) or readByte()).toLong()
        } else if (len == 127L) {
            len = 0
            repeat(8) { len = (len shl 8) or readByte().toLong() }
        }

        // 修复 issue #15：64 位长度首字节 ≥0x80 时旧实现得到负 Long，
        // 判空通过后 readFully(len.toInt()) 抛 NegativeArraySizeException
        if (len < 0 || len > maxFrameBytes) {
            throw IllegalStateException("帧长度非法：$len 字节（上限 $maxFrameBytes）")
        }

        val maskKey = readFully(4)
        val payload = readFully(len.toInt())
        for (i in payload.indices) {
            payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
        }
        return Frame(opcode, fin, payload)
    }

    private fun readByte(): Int {
        val v = input.read()
        if (v < 0) throw EOFException("连接已关闭")
        return v
    }

    private fun readFully(n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) throw EOFException("连接提前关闭")
            read += r
        }
        return buf
    }

    private fun readHttpHeaders(): Map<String, String>? {
        val sb = StringBuilder()
        var last4 = ""
        while (true) {
            val c = input.read()
            if (c < 0) return null
            sb.append(c.toChar())
            last4 = (last4 + c.toChar()).takeLast(4)
            if (last4 == "\r\n\r\n") break
            if (sb.length > 16 * 1024) return null
        }
        return parseHeaders(sb.toString())
    }

    companion object {
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        const val OPCODE_CONT = 0x0
        const val OPCODE_TEXT = 0x1
        const val OPCODE_CLOSE = 0x8
        const val OPCODE_PING = 0x9
        const val OPCODE_PONG = 0xA

        /** RFC 6455 §1.3 示例：对 TEST_KEY 的期望 Accept 值（测试向量）。 */
        const val TEST_KEY = "dGhlIHNhbXBsZSBub25jZQ=="
        const val TEST_ACCEPT = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
    }
}
