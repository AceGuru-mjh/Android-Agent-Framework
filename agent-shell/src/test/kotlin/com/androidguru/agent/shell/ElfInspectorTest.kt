package com.androidguru.agent.shell

import com.androidguru.agent.shell.runtime.ElfInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ElfInspectorTest {

    @Test
    fun `非ELF文件被拒绝`() {
        val f = File.createTempFile("notelf", ".bin")
        try {
            f.writeBytes(ByteArray(100) { 'A'.code.toByte() })
            val r = ElfInspector.inspect(f)
            assertFalse(r.isElf)
            assertFalse(r.executable)
            assertTrue(r.reason.contains("不是 ELF"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `过小文件被拒绝`() {
        val f = File.createTempFile("tiny", ".bin")
        try {
            f.writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
            val r = ElfInspector.inspect(f)
            assertFalse(r.isElf)
        } finally {
            f.delete()
        }
    }

    @Test
    fun `不存在的文件被拒绝`() {
        val r = ElfInspector.inspect(File("/nonexistent/path/binary"))
        assertFalse(r.executable)
        assertNotNull(r.reason)
    }

    @Test
    fun `32位ELF标记为不支持但仍识别为ELF`() {
        // 构造 32 位小端 ELF 头（ELFCLASS32）
        val f = File.createTempFile("elf32", ".so")
        try {
            val bytes = ByteArray(64)
            bytes[0] = 0x7F; bytes[1] = 'E'.code.toByte(); bytes[2] = 'L'.code.toByte(); bytes[3] = 'F'.code.toByte()
            bytes[4] = 1 // ELFCLASS32
            bytes[5] = 1 // ELFDATA2LSB
            f.writeBytes(bytes)
            val r = ElfInspector.inspect(f)
            assertTrue(r.isElf)
            assertFalse(r.is64Bit)
            assertFalse(r.executable)
        } finally {
            f.delete()
        }
    }

    @Test
    fun `本机真实ELF可执行文件被识别`() {
        // CI / 本机几乎必有 /bin/true 或 /bin/sh（64位 ELF，带 PT_INTERP）
        val candidates = listOf("/bin/true", "/usr/bin/true", "/bin/sh", "/bin/ls")
        val f = candidates.map(::File).firstOrNull { it.isFile } ?: return // 环境特殊时跳过
        val r = ElfInspector.inspect(f)
        assertTrue("真实可执行文件应被识别：$f → ${r.reason}", r.executable)
        assertEquals(true, r.hasInterp)
    }

    @Test
    fun `本机共享库无PT_INTERP不被识别为可执行`() {
        // /bin/true 所在系统的 libc 通常是共享库；找不到就跳过
        val candidates = listOf(
            "/lib/x86_64-linux-gnu/libz.so.1", "/usr/lib/x86_64-linux-gnu/libz.so.1",
            "/lib/x86_64-linux-gnu/libm.so.6", "/usr/lib/x86_64-linux-gnu/libbz2.so.1.0",
        )
        val f = candidates.map(::File).firstOrNull { it.isFile } ?: return
        val r = ElfInspector.inspect(f)
        assertFalse("共享库不应被判为可执行：$f → ${r.reason}", r.executable)
        assertTrue(r.isElf)
    }
}
