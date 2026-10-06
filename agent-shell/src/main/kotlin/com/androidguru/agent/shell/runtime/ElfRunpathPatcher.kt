package com.androidguru.agent.shell.runtime

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ELF64 动态段读写器 —— 清空 `DT_RUNPATH` / 改写 `DT_SONAME`（原地等长改写）。
 *
 * 为什么需要它（yl-ai 真机踩坑的核心）：动态链接器的解析顺序是
 * **RUNPATH 优先于 LD_LIBRARY_PATH** —— 二进制若自带 RUNPATH，宿主布置的
 * `LD_LIBRARY_PATH` 会被静默忽略。本工具把 RUNPATH 条目的 tag 原地改写为
 * `DT_DEBUG`（对动态链接器无副作用的占位），并把字符串表里的路径清零，
 * 让 LD_LIBRARY_PATH 重新生效。
 *
 * 全部操作为纯 JVM 字节处理，原地改写不改变文件长度。
 */
object ElfRunpathPatcher {

    private const val PT_DYNAMIC = 2
    private const val PT_LOAD = 1

    private const val DT_NULL = 0L
    private const val DT_STRTAB = 5L
    private const val DT_SONAME = 14L
    private const val DT_RPATH = 15L
    private const val DT_RUNPATH = 29L
    private const val DT_DEBUG = 21L

    private val ELF_MAGIC = intArrayOf(0x7F, 'E'.code, 'L'.code, 'F'.code)

    /**
     * 清空 RUNPATH / RPATH：tag 改写为 DT_DEBUG，strtab 中对应字符串清零。
     */
    fun clear(file: File): Boolean {
        return runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                val map = parse(raf) ?: return false
                val entry = map.entries.firstOrNull { it.first.tag == DT_RUNPATH || it.first.tag == DT_RPATH }
                    ?: return true // 没有 RUNPATH，视为已清空
                val strOff = map.strTableFileOffset ?: return false
                val strValue = entry.first.value

                raf.seek(entry.second)
                raf.writeLong(DT_DEBUG) // tag → DT_DEBUG（无害占位）
                raf.seek(entry.second + 8)
                raf.writeLong(0L)       // value → 0

                // 字符串清零（保持长度不变）
                var i = 0L
                while (strOff + strValue + i < raf.length()) {
                    raf.seek(strOff + strValue + i)
                    val b = raf.read()
                    if (b <= 0) break
                    raf.seek(strOff + strValue + i)
                    raf.write(0)
                    i++
                }
                true
            }
        }.getOrDefault(false)
    }

    /** 读取 RUNPATH（不存在返回 null；已清空返回空串）。 */
    fun readRunpath(file: File): String? {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val map = parse(raf) ?: return null
                val entry = map.entries.firstOrNull { it.first.tag == DT_RUNPATH || it.first.tag == DT_RPATH }
                    ?: return ""
                val strOff = map.strTableFileOffset ?: return null
                readCString(raf, strOff + entry.first.value) ?: ""
            }
        }.getOrNull()
    }

    /** 读取 DT_SONAME。 */
    fun readSoname(file: File): String? {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val map = parse(raf) ?: return null
                val entry = map.entries.firstOrNull { it.first.tag == DT_SONAME } ?: return null
                val strOff = map.strTableFileOffset ?: return null
                readCString(raf, strOff + entry.first.value)
            }
        }.getOrNull()
    }

    /**
     * 改写 DT_SONAME（新名必须 ≤ 旧名长度，原地补 NUL）。
     * 供「伪装文件名 ↔ 真实 soname」对齐（如 libtallocimpl.so → libtalloc.so.2）。
     */
    fun renameSoname(file: File, newName: String): Boolean {
        return runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                val map = parse(raf) ?: return false
                val entry = map.entries.firstOrNull { it.first.tag == DT_SONAME } ?: return false
                val strOff = map.strTableFileOffset ?: return false
                val old = readCString(raf, strOff + entry.first.value) ?: return false
                if (newName.length > old.length) return false

                val buf = newName.toByteArray(Charsets.US_ASCII)
                raf.seek(strOff + entry.first.value)
                raf.write(buf)
                for (i in buf.size until old.length + 1) {
                    raf.write(0)
                }
                true
            }
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------

    private class DynMap(
        val entries: List<Pair<Dyn, Long>>, // (entry, file offset)
        val strTableFileOffset: Long?,
    )

    private data class Dyn(val tag: Long, val value: Long)

    private fun parse(raf: RandomAccessFile): DynMap? {
        val len = raf.length()
        if (len < 64) return null

        val ident = ByteArray(16)
        raf.seek(0); raf.readFully(ident)
        for (i in ELF_MAGIC.indices) {
            if (ident[i].toInt() and 0xFF != ELF_MAGIC[i]) return null
        }
        if (ident[4].toInt() and 0xFF != 2 || ident[5].toInt() and 0xFF != 1) return null // 仅 64 位小端

        raf.seek(0x20)
        val phoff = readLong(raf)
        raf.seek(0x36)
        val phentsize = readShort(raf)
        raf.seek(0x38)
        val phnum = readShort(raf)
        if (phoff <= 0 || phentsize < 56 || phnum <= 0 || phnum > 128) return null

        var dynOffset = -1L
        var dynSize = -1L
        val loads = mutableListOf<Triple<Long, Long, Long>>() // (offset, vaddr, filesz)

        for (i in 0 until phnum) {
            val base = phoff + i.toLong() * phentsize
            raf.seek(base)
            val pType = readInt(raf).toLong() and 0xFFFFFFFFL
            raf.seek(base + 8)
            val pOffset = readLong(raf)
            val pVaddr = readLong(raf)
            raf.seek(base + 8 + 8 + 8 + 8)
            val pFilesz = readLong(raf)

            when (pType) {
                PT_DYNAMIC.toLong() -> { dynOffset = pOffset; dynSize = pFilesz }
                PT_LOAD.toLong() -> loads.add(Triple(pOffset, pVaddr, pFilesz))
            }
        }
        if (dynOffset < 0 || dynSize <= 0) return null

        val entries = mutableListOf<Pair<Dyn, Long>>()
        var strTabVaddr: Long? = null
        var off = dynOffset
        val end = dynOffset + dynSize
        while (off + 16 <= end && off + 16 <= len) {
            raf.seek(off)
            val tag = readLong(raf)
            val value = readLong(raf)
            if (tag == DT_NULL) break
            if (tag == DT_STRTAB) strTabVaddr = value
            entries.add(Dyn(tag, value) to off)
            off += 16
        }

        // vaddr → 文件偏移（经 PT_LOAD 段换算）
        val strTableFileOffset = strTabVaddr?.let { vaddr ->
            loads.firstOrNull { (_, vaddr0, filesz) -> vaddr >= vaddr0 && vaddr < vaddr0 + filesz }
                ?.let { (offset, vaddr0, _) -> offset + (vaddr - vaddr0) }
        }

        return DynMap(entries, strTableFileOffset)
    }

    private fun readCString(raf: RandomAccessFile, offset: Long, maxLen: Int = 512): String? {
        if (offset <= 0) return null
        val buf = ByteBuffer.allocate(maxLen)
        raf.seek(offset)
        var i = 0
        while (i < maxLen) {
            val b = raf.read()
            if (b <= 0) break
            buf.put(b.toByte())
            i++
        }
        return String(buf.array(), 0, i, Charsets.UTF_8)
    }

    private fun readInt(raf: RandomAccessFile): Int {
        val b = ByteArray(4); raf.readFully(b)
        return (b[0].toInt() and 0xFF) or
            ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or
            ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun readShort(raf: RandomAccessFile): Int {
        val b = ByteArray(2); raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    private fun readLong(raf: RandomAccessFile): Long {
        val b = ByteArray(8); raf.readFully(b)
        var v = 0L
        for (i in 7 downTo 0) {
            v = (v shl 8) or (b[i].toLong() and 0xFF)
        }
        return v
    }

    /** 调试辅助：Buffer 小端视图（未参与主流程，供测试对照）。 */
    internal fun leLong(v: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
}
