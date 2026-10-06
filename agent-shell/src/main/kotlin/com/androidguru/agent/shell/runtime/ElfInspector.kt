package com.androidguru.agent.shell.runtime

import java.io.File
import java.io.RandomAccessFile

/**
 * ELF 判别 —— 区分「可执行文件」与「共享库」。
 *
 * 关键知识（来自 yl-ai 真机踩坑）：判断能不能执行**不能只看 ELF magic**，
 * 必须检查是否存在 `PT_INTERP` 程序头 —— 执行共享库会直接 SIGSEGV。
 * Android 上可执行文件全部伪装成 `lib*.so` 解压，本工具是把它们从真正的
 * 共享库里筛出来的唯一依据。
 *
 * 纯 JVM 可用（手写小端读取，零平台依赖）。
 */
object ElfInspector {

    private const val ELF_MAGIC_0 = 0x7F
    private const val ELF_MAGIC_1 = 'E'.code
    private const val ELF_MAGIC_2 = 'L'.code
    private const val ELF_MAGIC_3 = 'F'.code

    private const val ELFCLASS64 = 2
    private const val ELFDATA2LSB = 1

    private const val PT_LOAD = 1
    private const val PT_INTERP = 3

    data class Result(
        val isElf: Boolean,
        val is64Bit: Boolean,
        val hasInterp: Boolean,
        val interpPath: String? = null,
        val reason: String,
    ) {
        /** 可执行 = 是 ELF 且带解释器（PT_INTERP）。 */
        val executable: Boolean get() = isElf && hasInterp
    }

    fun inspect(file: File): Result {
        if (!file.isFile || file.length() < 64) {
            return Result(false, false, false, null, "不是常规文件或过小")
        }
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val ident = ByteArray(16)
                raf.readFully(ident)
                if (ident[0].toInt() and 0xFF != ELF_MAGIC_0 ||
                    ident[1].toInt() and 0xFF != ELF_MAGIC_1 ||
                    ident[2].toInt() and 0xFF != ELF_MAGIC_2 ||
                    ident[3].toInt() and 0xFF != ELF_MAGIC_3
                ) {
                    return@use Result(false, false, false, null, "不是 ELF 文件")
                }
                val is64 = ident[4].toInt() and 0xFF == ELFCLASS64
                val littleEndian = ident[5].toInt() and 0xFF == ELFDATA2LSB
                if (!is64 || !littleEndian) {
                    return@use Result(true, is64, false, null, "仅支持 64 位小端 ELF")
                }

                raf.seek(0x20)
                val phoff = readLongLE(raf)
                raf.seek(0x36)
                val phentsize = readShortLE(raf)
                raf.seek(0x38)
                val phnum = readShortLE(raf)

                if (phoff <= 0 || phentsize < 56 || phnum <= 0 || phnum > 128) {
                    return@use Result(true, true, false, null, "程序头表异常")
                }

                for (i in 0 until phnum) {
                    val base = phoff + i.toLong() * phentsize
                    raf.seek(base)
                    val pType = readIntLE(raf)
                    raf.seek(base + 8)
                    val pOffset = readLongLE(raf)
                    raf.seek(base + 8 + 8 + 8 + 8)
                    val pFilesz = readLongLE(raf)

                    if (pType == PT_INTERP && pFilesz in 2..512 && pOffset > 0) {
                        raf.seek(pOffset)
                        val buf = ByteArray(pFilesz.toInt() - 1)
                        raf.readFully(buf)
                        val interp = String(buf, Charsets.UTF_8).trimEnd('\u0000')
                        return@use Result(true, true, true, interp, "可执行（解释器 $interp）")
                    }
                }
                Result(true, true, false, null, "是共享库（无 PT_INTERP），不能直接执行")
            }
        }.getOrElse {
            Result(false, false, false, null, "读取失败: ${it.javaClass.simpleName}")
        }
    }

    private fun readIntLE(raf: RandomAccessFile): Int {
        val b = ByteArray(4); raf.readFully(b)
        return (b[0].toInt() and 0xFF) or
            ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or
            ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLE(raf: RandomAccessFile): Int {
        val b = ByteArray(2); raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    private fun readLongLE(raf: RandomAccessFile): Long {
        val b = ByteArray(8); raf.readFully(b)
        var v = 0L
        for (i in 7 downTo 0) {
            v = (v shl 8) or (b[i].toLong() and 0xFF)
        }
        return v
    }
}
