package com.androidguru.agent.shell

import com.androidguru.agent.shell.control.WsConnection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

class WsConnectionTest {

    @Test
    fun `握手accept值符合RFC测试向量`() {
        val output = ByteArrayOutputStream()
        val ws = WsConnection(ByteArrayInputStream(ByteArray(0)), output)
        val head = "GET /?token=x HTTP/1.1\r\n" +
            "Host: 127.0.0.1\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: ${WsConnection.TEST_KEY}\r\n" +
            "Sec-WebSocket-Version: 13\r\n\r\n"
        assertTrue(ws.handshakeWith(head))
        val response = output.toString("US-ASCII")
        assertTrue(response.contains("101 Switching Protocols"))
        assertTrue(response.contains("Sec-WebSocket-Accept: ${WsConnection.TEST_ACCEPT}"))
    }

    @Test
    fun `缺少key握手失败`() {
        val output = ByteArrayOutputStream()
        val ws = WsConnection(ByteArrayInputStream(ByteArray(0)), output)
        val head = "GET / HTTP/1.1\r\nUpgrade: websocket\r\n\r\n"
        assertTrue(!ws.handshakeWith(head))
    }

    @Test
    fun `服务端帧不加掩码_客户端帧解掩码`() {
        // 服务端 sendText → 读回原始帧头检查：FIN|TEXT，无掩码位
        val out = ByteArrayOutputStream()
        val ws = WsConnection(ByteArrayInputStream(ByteArray(0)), out)
        ws.sendText("hello")
        val bytes = out.toByteArray()
        assertEquals(0x81, bytes[0].toInt() and 0xFF) // FIN + text
        assertEquals(5, bytes[1].toInt() and 0x7F)    // len=5，掩码位=0
        assertEquals("hello", String(bytes, 2, 5, Charsets.UTF_8))
    }

    /** 构造一个带掩码的客户端帧（RFC 6455 §5.1 要求客户端帧必须掩码）。 */
    private fun maskedFrame(opcode: Int, fin: Boolean, payload: ByteArray): ByteArray {
        val mask = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val masked = ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }
        val b0 = (if (fin) 0x80 else 0) or opcode
        return byteArrayOf(b0.toByte(), (0x80 or payload.size).toByte()) + mask + masked
    }

    @Test
    fun `readText 解析带掩码的客户端帧`() {
        // 手工构造带掩码的 "ping" 帧
        val frame = maskedFrame(0x1, fin = true, payload = "ping".toByteArray(Charsets.UTF_8))
        val ws = WsConnection(ByteArrayInputStream(frame), ByteArrayOutputStream())
        assertEquals("ping", ws.readText())
    }

    @Test
    fun `close帧返回null`() {
        val ws = WsConnection(ByteArrayInputStream(maskedFrame(0x8, fin = true, ByteArray(0))), ByteArrayOutputStream())
        assertNull(ws.readText())
    }

    @Test
    fun `分片消息被完整组装`() {
        // 修复 issue #15：FIN=0 的首分片 + CONT 续帧 → 组装为一条完整消息
        val first = maskedFrame(0x1, fin = false, payload = "hel".toByteArray(Charsets.UTF_8))
        val cont = maskedFrame(0x0, fin = true, payload = "lo".toByteArray(Charsets.UTF_8))
        val ws = WsConnection(ByteArrayInputStream(first + cont), ByteArrayOutputStream())
        assertEquals("hello", ws.readText())
    }

    @Test
    fun `未掩码客户端帧被拒绝`() {
        // 修复 issue #15：RFC 6455 §5.1 要求服务端对未掩码客户端帧 fail-close
        val frame = byteArrayOf(0x81.toByte(), 0x04) + "ping".toByteArray()
        val ws = WsConnection(ByteArrayInputStream(frame), ByteArrayOutputStream())
        var threw = false
        try {
            ws.readText()
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `负的64位长度被拒绝`() {
        // 修复 issue #15：长度首字节 ≥0x80 时旧实现得到负 Long → NegativeArraySize
        val header = byteArrayOf(0x81.toByte(), 127.toByte()) + ByteArray(8) { 0x85.toByte() } +
            ByteArray(4) // mask key
        val ws = WsConnection(ByteArrayInputStream(header), ByteArrayOutputStream())
        var threw = false
        try {
            ws.readText()
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `超长帧被拒绝`() {
        val len = 5 * 1024 * 1024
        val header = byteArrayOf(0x81.toByte(), 127.toByte()) + ByteArray(8) { i ->
            ((len.toLong() shr (8 * (7 - i))) and 0xFF).toByte()
        } + ByteArray(4) // mask key
        val ws = WsConnection(ByteArrayInputStream(header), ByteArrayOutputStream())
        var threw = false
        try {
            ws.readText()
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }
}
