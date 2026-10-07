package com.androidguru.agent.shell.nativeruntime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 原生层集成测试 —— 加载 host 构建的 libagsh_native.so 端到端验证。
 *
 * 运行方式：
 * ```
 * cmake -S agent-shell-native/src/main/cpp -B /tmp/agsh-build && cmake --build /tmp/agsh-build
 * ./gradlew :agent-shell-native:test -Pagsh.native.lib=/tmp/agsh-build/libagsh_native.so
 * ```
 * 库不可用时全部自动跳过（assumeTrue）—— 保证纯 JVM CI 矩阵不红；
 * `-Pagsh.native.strict=true` 时库缺失视为失败（CI native-jvm job 用）。
 */
class NativeBridgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun nativeAvailable(): Boolean {
        val strict = System.getProperty("agsh.native.strict")?.toBoolean() ?: false
        val loaded = NativeBridge.isLoaded()
        if (strict && !loaded) {
            throw AssertionError("agsh.native.strict=true 但原生库未加载（检查构建产物路径）")
        }
        return loaded
    }

    @Test
    fun `native library loads and reports info`() {
        assumeTrue(nativeAvailable())
        val info = NativeBridge.nativeInfo()
        assertTrue("info 应含版本标识: $info", info.contains("agsh_native"))
        assertTrue("info 应含 uid: $info", info.contains("uid="))
    }

    @Test
    fun `pty spawn echoes with crlf and exits zero`() {
        assumeTrue(nativeAvailable())
        val handle = NativeBridge.spawn(
            program = "/bin/sh",
            argv = listOf("-c", "echo AGSH_NATIVE_OK"),
            env = emptyMap(),
            cwd = null,
            rows = 24,
            cols = 80,
        )
        assertTrue(handle.masterFd > 0)
        assertTrue(handle.pid > 0)

        val channel = NativePtyChannel(handle.masterFd, handle.pid)
        channel.use { pty ->
            // 读到进程退出为止（对部分到达免疫），再读尽内核尾部缓冲
            val out = StringBuilder()
            val buf = ByteArray(4096)
            while (true) {
                val n = pty.read(buf)
                if (n > 0) out.append(String(buf, 0, n, Charsets.UTF_8))
                val code = pty.waitFor(50)
                if (code != -2) break // 进程已退出（或未知）
                if (n == -1) break    // EOF
            }
            // 退出后内核缓冲里通常还有尾巴，读尽（容错若干轮）
            repeat(4) {
                while (true) {
                    val n = pty.read(buf)
                    if (n <= 0) break
                    out.append(String(buf, 0, n, Charsets.UTF_8))
                }
                Thread.sleep(20)
            }
            // PTY 行规程 ONLCR：输出带 \r\n（证明这是真 PTY 而非管道）
            assertTrue("应读到输出: $out", out.contains("AGSH_NATIVE_OK"))
            assertTrue(
                "PTY 输出应含 CRLF: ${out.toString().replace("\r", "\\r").replace("\n", "\\n")}",
                out.contains("\r\n"),
            )
            assertEquals(0, pty.waitFor(5000))
            assertEquals(0, pty.waitFor(1000)) // 退出码缓存：可重复查询
            assertFalse(pty.isAlive())
        }
    }

    @Test
    fun `pty exit code propagates`() {
        assumeTrue(nativeAvailable())
        val handle = NativeBridge.spawn("/bin/sh", listOf("-c", "exit 42"), emptyMap(), null, 24, 80)
        NativePtyChannel(handle.masterFd, handle.pid).use { pty ->
            assertEquals(42, pty.waitFor(5000))
        }
    }

    @Test
    fun `pty wait timeout then sigkill`() {
        assumeTrue(nativeAvailable())
        val handle = NativeBridge.spawn("/bin/sh", listOf("-c", "sleep 5"), emptyMap(), null, 24, 80)
        NativePtyChannel(handle.masterFd, handle.pid).use { pty ->
            assertEquals(-2, pty.waitFor(150))            // 超时约定
            assertTrue(pty.isAlive())
            pty.signal(com.androidguru.agent.shell.process.ProcessChannel.SIGKILL)
            assertEquals(128 + 9, pty.waitFor(5000))     // 信号杀死 = 128 + sig
        }
    }

    @Test
    fun `pty resize takes effect`() {
        assumeTrue(nativeAvailable())
        val handle = NativeBridge.spawn("/bin/sh", listOf("-c", "exit 0"), emptyMap(), null, 10, 40)
        NativePtyChannel(handle.masterFd, handle.pid).use { pty ->
            pty.resize(66, 200) // 不抛即通过（TIOCSWINSZ）
            assertEquals(0, pty.waitFor(5000))
        }
    }

    @Test
    fun `native ansi stripper matches jvm implementation`() {
        assumeTrue(nativeAvailable())
        val jvm = com.androidguru.agent.shell.terminal.AnsiStripper
        val samples = listOf(
            "\u001B[31mRED\u001B[0m",
            "\u001B]0;title\u0007visible",
            "\u001B]0;t\u001B\\v",
            "10%\r50%\r100%\ndone",
            "a\rb\b\u0007c",
            "\u001B[32m你好\u001B[0m🎉",
            "plain",
            "",
        )
        for (s in samples) {
            assertEquals("剥离结果应与 JVM 版一致: ${s.encodeToByteArray().contentToString()}",
                jvm.strip(s), NativeAnsiStripper.strip(s))
        }
    }

    @Test
    fun `native elf tools round trip on crafted elf`() {
        assumeTrue(nativeAvailable())
        // 最小 ELF64 LE：带 DT_RUNPATH=/data/agsh-runpath 与 DT_SONAME
        val elf = tmp.newFile("mini.elf").apply {
            writeBytes(buildMiniElf64())
        }

        assertEquals("/data/agsh-runpath", NativeElfTools.readRunpath(elf))
        assertTrue(NativeElfTools.clearRunpath(elf))
        assertEquals("", NativeElfTools.readRunpath(elf))  // 清空后为空串
        assertTrue(NativeElfTools.clearRunpath(elf))       // 幂等

        assertEquals("liboldname.so", NativeElfTools.readSoname(elf))
        assertTrue(NativeElfTools.renameSoname(elf, "libnew.so"))
        assertEquals("libnew.so", NativeElfTools.readSoname(elf))
        assertFalse("更长的 soname 应被拒绝", NativeElfTools.renameSoname(elf, "libtoo_long_name.so"))
    }

    @Test
    fun `native elf tools reject non elf`() {
        assumeTrue(nativeAvailable())
        val plain = tmp.newFile("plain.txt").apply { writeText("not an elf") }
        // 非文件 / 非 ELF：readRunpath 语义 = null（解析失败）
        assertNull(NativeElfTools.readRunpath(plain))
        assertFalse(NativeElfTools.clearRunpath(plain))
        assertNull(NativeElfTools.readSoname(plain))
    }

    @Test
    fun `factory availability flag agrees with bridge`() {
        val available = NativeProcessChannelFactory.isAvailable()
        assertEquals(available, NativeBridge.isLoaded())
        if (!available) {
            assertEquals(null, NativeProcessChannelFactory.createIfAvailable())
        }
    }

    @Test
    fun `native strip ansi handles byte streams`() {
        assumeTrue(nativeAvailable())
        val input = "\u001B[1mbold\u001B[0m plain".toByteArray(Charsets.UTF_8)
        val out = NativeAnsiStripper.strip(input)
        assertArrayEquals("bold plain".toByteArray(Charsets.UTF_8), out)
    }

    // ------------------------------------------------------------------
    // 与 C++ 测试同源的 mini ELF64 LE 构造：
    // ehdr + PT_LOAD + PT_DYNAMIC + [DT_STRTAB][DT_RUNPATH][DT_SONAME][DT_NULL] + strtab
    // ------------------------------------------------------------------
    private fun buildMiniElf64(): ByteArray {
        val ehdrSize = 64
        val phdrSize = 56
        val phnum = 2
        val dynOff = ehdrSize + phdrSize * phnum           // 176
        val strOff = dynOff + 4 * 16                       // 240
        val runpath = "/data/agsh-runpath"
        val soname = "liboldname.so"
        val total = strOff + 1 + runpath.length + 1 + soname.length + 1

        val b = ByteArray(total)
        fun put64(off: Int, v: Long) {
            for (i in 0 until 8) b[off + i] = (v ushr (8 * i)).toByte()
        }
        fun put16(off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        }

        // ELF header
        b[0] = 0x7F; b[1] = 'E'.code.toByte(); b[2] = 'L'.code.toByte(); b[3] = 'F'.code.toByte()
        b[4] = 2; b[5] = 1; b[6] = 1
        put16(0x10, 3)        // ET_DYN
        put16(0x34, 64)       // e_ehsize
        put16(0x36, 56)       // e_phentsize
        put16(0x38, phnum)    // e_phnum
        put64(0x20, ehdrSize.toLong()) // e_phoff

        // Phdr[0] PT_LOAD（vaddr == offset == 0，filesz = total）
        put64(0x40, 1)
        put64(0x40 + 32, total.toLong())

        // Phdr[1] PT_DYNAMIC
        val p1 = ehdrSize + phdrSize
        put64(p1, 2)
        put64(p1 + 8, dynOff.toLong())
        put64(p1 + 16, dynOff.toLong())
        put64(p1 + 32, (4 * 16).toLong())

        // 动态条目
        val sonameStrOff = 1 + runpath.length + 1
        put64(dynOff, 5); put64(dynOff + 8, strOff.toLong())   // DT_STRTAB
        put64(dynOff + 16, 29); put64(dynOff + 24, 1)         // DT_RUNPATH → strtab+1
        put64(dynOff + 32, 14); put64(dynOff + 40, sonameStrOff.toLong()) // DT_SONAME
        // DT_NULL 隐含（全 0）

        // strtab
        System.arraycopy(runpath.toByteArray(), 0, b, strOff + 1, runpath.length)
        System.arraycopy(soname.toByteArray(), 0, b, strOff + sonameStrOff, soname.length)
        return b
    }
}
