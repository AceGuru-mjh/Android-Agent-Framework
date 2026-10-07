package com.androidguru.agent.shell.termux

import com.androidguru.agent.shell.audit.InMemoryAuditLog
import com.androidguru.agent.shell.net.Downloader
import com.androidguru.agent.shell.process.ProcessChannel
import com.androidguru.agent.shell.process.ProcessChannelFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * TermuxLauncher 单元测试 —— 参数构造、环境变量组装（8.3 首现优先坑的回归）、
 * 诊断梯与一次性命令执行。全部走 [FakeFactory] 脚本化通道，不启动真实 proot。
 */
class TermuxLauncherTest {

    @get:Rule
    val folder = TemporaryFolder()

    // ---------- 脚本化通道 ----------

    /** 按脚本吐一次输出并返回固定退出码的通道（管道语义：数据读尽后 read 返回 0）。 */
    private class ScriptedChannel(output: String, private val exitCode: Int) : ProcessChannel {
        private val bytes = output.toByteArray(Charsets.UTF_8)
        private var pos = 0
        override fun read(buffer: ByteArray): Int {
            if (pos >= bytes.size) return 0
            val n = minOf(buffer.size, bytes.size - pos)
            System.arraycopy(bytes, pos, buffer, 0, n)
            pos += n
            return n
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            ByteArrayOutputStream().write(bytes, offset, length)
        }
        override fun waitFor(timeoutMs: Long): Int = exitCode
        override fun signal(signalNumber: Int) {}
        override fun isAlive(): Boolean = pos < bytes.size
        override fun close() {}
    }

    /** 记录 open 调用并按 argv 分发脚本通道的工厂。 */
    private class FakeFactory(
        private val responder: (program: String, argv: List<String>) -> ScriptedChannel,
    ) : ProcessChannelFactory {
        class Open(val program: String, val argv: List<String>, val env: Map<String, String>, val cwd: File?)
        val opens = mutableListOf<Open>()
        override fun open(
            program: String,
            argv: List<String>,
            env: Map<String, String>,
            cwd: File?,
            rows: Int,
            cols: Int,
        ): ProcessChannel {
            opens += Open(program, argv, env, cwd)
            return responder(program, argv)
        }
    }

    // ---------- 环境与启动器构造 ----------

    /** 构造一个走过完整安装管线的环境（假 bootstrap zip，不碰网络）。 */
    private fun installedEnvironment(): TermuxEnvironment {
        val zip = folder.newFile("fake-bootstrap.zip")
        val elf = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) +
            ByteArray(64) { (it % 97).toByte() }
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            listOf(
                "bin/bash", "bin/login", "bin/dash", "bin/apt", "bin/dpkg",
                "lib/libandroid-support.so", "lib/libncursesw.so.6.5",
            ).forEach { name ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(elf)
                zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("SYMLINKS.txt"))
            zos.write("dash←./bin/sh\n".toByteArray())
            zos.closeEntry()
        }
        val env = TermuxEnvironment(
            baseDir = folder.root,
            downloader = Downloader(),
            audit = InMemoryAuditLog(),
            catalog = TermuxBootstrapCatalog.BootstrapEntry(
                abi = "arm64-v8a",
                fileName = "bootstrap-aarch64.zip",
                mirrors = listOf("http://127.0.0.1:1/bootstrap-aarch64.zip"),
                sha256 = Downloader.sha256(zip),
            ),
            hostAbiProvider = { "arm64-v8a" },
            minInstallBytes = 1L,
        )
        runBlocking { env.install(TermuxEnvironment.InstallOptions(sourceZip = zip)) }
        return env
    }

    /** 真机上一定存在且可执行的系统 shell，充当"proot 二进制"（永不真跑，只过 prepare 检查）。 */
    private fun fakeProot(): File = File("/bin/sh")

    private fun newLauncher(
        env: TermuxEnvironment,
        factory: ProcessChannelFactory = FakeFactory { _, _ -> ScriptedChannel("", 0) },
        prootBinary: File? = fakeProot(),
    ): TermuxLauncher = TermuxLauncher(
        environment = env,
        channelFactory = factory,
        audit = null,
        prootBinary = prootBinary,
        prootLoader = fakeProot(),
        libDir = File(folder.root, "proot-lib").apply { mkdirs() },
    )

    // ---------- 参数构造 ----------

    @Test
    fun `buildProotArgs_自映射bind与系统绑定_命令追加尾部`() {
        val env = installedEnvironment()
        val args = newLauncher(env).buildProotArgs(listOf("echo", "hi"))

        assertEquals("自映射方案以 -r / 开头", listOf("-r", "/"), args.take(2))

        // 核心一行：宿主 termux 根目录 → guest 硬编码 $PREFIX 所在位置
        val bindIdx = args.indexOf("-b")
        assertTrue(bindIdx >= 0)
        assertEquals(
            "termux 自映射 bind",
            "${env.filesDir.absolutePath}:/data/data/com.termux/files",
            args[bindIdx + 1],
        )

        // /dev /proc /sys 必绑；/system /apex /vendor 存在才绑（桌面 JVM 上缺席）
        fun bindValue(name: String): String? {
            val i = args.indexOf(name)
            return if (i >= 0 && i > 0 && args[i - 1] == "-b") args[i] else null
        }
        assertEquals("/dev", bindValue("/dev"))
        assertEquals("/proc", bindValue("/proc"))
        assertEquals("/sys", bindValue("/sys"))

        // 工作目录与环境内 HOME 对齐
        val wIdx = args.indexOf("-w")
        assertEquals("/data/data/com.termux/files/home", args[wIdx + 1])

        // guest 命令追加在参数尾部
        assertEquals(listOf("echo", "hi"), args.takeLast(2))
    }

    @Test
    fun `buildLoginEnv_不含LD_LIBRARY_PATH_8_3首现优先坑回归`() {
        val env = installedEnvironment()
        val loginEnv = newLauncher(env).buildLoginEnv()

        assertEquals("/data/data/com.termux/files/usr", loginEnv["PREFIX"])
        assertEquals("/data/data/com.termux/files/home", loginEnv["HOME"])
        assertEquals("/data/data/com.termux/files/usr/tmp", loginEnv["TMPDIR"])
        assertTrue("PATH 应含 bin 与 applets", loginEnv["PATH"]!!.contains("bin/applets"))
        assertEquals("xterm-256color", loginEnv["TERM"])
        assertFalse(
            "登录环境绝不能带 LD_LIBRARY_PATH（yl-ai DEVLOG 8.3：宿主 envp 基底追加同名变量首现优先，" +
                "追加值会被静默忽略，泄漏进 guest 还会让 Termux 二进制加载宿主库直接崩）",
            loginEnv.containsKey("LD_LIBRARY_PATH"),
        )
    }

    @Test
    fun `buildProotEnv_带PROOT控制变量_LD_LIBRARY_PATH只指向宿主库`() {
        val env = installedEnvironment()
        val launcher = newLauncher(env)
        val loader = fakeProot()

        val prootEnv = launcher.buildProotEnv(loader)

        assertEquals("PROOT_LOADER 指向注入的 loader", loader.absolutePath, prootEnv["PROOT_LOADER"])
        assertTrue("PROOT_TMP_DIR 应在工作目录下", prootEnv["PROOT_TMP_DIR"]!!.startsWith(env.filesDir.absolutePath))
        assertEquals("0", prootEnv["PROOT_NO_SECCOMP"])
        // 8.3 结论：LD_LIBRARY_PATH 只为 proot 自己加载 libtalloc 服务 → 指向宿主库目录，
        // 绝不能混入 guest 的 lib 路径
        val ldPath = prootEnv["LD_LIBRARY_PATH"]
        assertNotNull("应为 proot 提供 LD_LIBRARY_PATH（宿主库目录）", ldPath)
        assertFalse("不得包含 guest lib 路径", ldPath!!.contains("/data/data/com.termux/files/usr/lib"))
        assertTrue(
            "应包含宿主 libDir 或 loader 所在目录",
            ldPath.contains(File(folder.root, "proot-lib").absolutePath) ||
                ldPath.contains(loader.parentFile!!.absolutePath),
        )
        // 登录变量并入同一 envp（proot 透传给 guest；guest 内由 agsh-init.sh 重建 LD_LIBRARY_PATH）
        assertEquals("/data/data/com.termux/files/usr", prootEnv["PREFIX"])
    }

    // ---------- 就绪检查 ----------

    @Test
    fun `prepare_环境未安装给明确原因`() {
        val env = TermuxEnvironment(
            baseDir = folder.root,
            downloader = Downloader(),
            audit = null,
            catalog = null,
            hostAbiProvider = { "arm64-v8a" },
            minInstallBytes = 1L,
        )
        val ready = newLauncher(env).prepare()
        assertFalse(ready.available)
        assertTrue("原因要指明缺什么：${ready.reason}", ready.reason.contains("尚未安装"))
    }

    @Test
    fun `prepare_缺proot二进制给明确原因`() {
        val env = installedEnvironment()
        val ready = newLauncher(env, prootBinary = null).prepare()
        assertFalse(ready.available)
        assertTrue("原因要提到 PRoot：${ready.reason}", ready.reason.contains("PRoot"))
    }

    @Test
    fun `prepare_环境与proot都在位即可用`() {
        val ready = newLauncher(installedEnvironment()).prepare()
        assertTrue(ready.available)
    }

    // ---------- 启动与一次性命令 ----------

    @Test
    fun `launchLoginShell_默认login命令_传参与env正确_记录复现命令行`() {
        val env = installedEnvironment()
        val factory = FakeFactory { _, _ -> ScriptedChannel("welcome\n", 0) }
        val launcher = newLauncher(env, factory = factory)

        val channel = launcher.launchLoginShell()

        assertNotNull(channel)
        assertEquals("只应打开一个通道", 1, factory.opens.size)
        val open = factory.opens.single()
        assertEquals("program 应是注入的 proot", fakeProot().absolutePath, open.program)
        assertEquals("argv[0..1] = -r /", listOf("-r", "/"), open.argv.take(2))
        assertEquals(
            "默认命令是环境内 login",
            "/data/data/com.termux/files/usr/bin/login",
            open.argv.last(),
        )
        assertEquals("env 里要有 PROOT_LOADER", fakeProot().absolutePath, open.env["PROOT_LOADER"])
        assertTrue("env 里要有登录变量", open.env.containsKey("PREFIX"))

        val repro = launcher.describeLastLaunch()
        assertTrue("复现命令行应含 proot 路径：$repro", repro.contains(fakeProot().absolutePath))
        assertTrue(repro.contains("PROOT_LOADER="))
        assertTrue(repro.contains("-r /"))
    }

    @Test
    fun `runShellCommand_返回命令退出码与输出`() {
        val env = installedEnvironment()
        val factory = FakeFactory { _, _ -> ScriptedChannel("hello from termux\n", 0) }
        val launcher = newLauncher(env, factory = factory)

        val result = launcher.runShellCommand("echo hello", timeoutMs = 5_000)

        assertTrue(result.contains("命令: echo hello"))
        assertTrue(result.contains("退出码: 0"))
        assertTrue(result.contains("hello from termux"))

        // argv 尾部应是 rawProgram -c <命令>
        val argv = factory.opens.single().argv
        assertEquals(
            listOf("/data/data/com.termux/files/usr/bin/sh", "-c", "echo hello"),
            argv.takeLast(3),
        )
    }

    @Test
    fun `runShellCommand_环境不可用时返回可读文本而不抛异常`() {
        val env = TermuxEnvironment(
            baseDir = folder.root,
            downloader = Downloader(),
            audit = null,
            catalog = null,
            hostAbiProvider = { "arm64-v8a" },
            minInstallBytes = 1L,
        )
        val result = newLauncher(env).runShellCommand("echo hi")
        assertTrue(result.startsWith("[环境不可用]"))
    }

    // ---------- 诊断梯 ----------

    @Test
    fun `diagnoseLadder_缺proot时首级即停`() {
        val launcher = newLauncher(installedEnvironment(), prootBinary = null)

        val ladder = launcher.diagnoseLadder()

        assertEquals("第一级失败就停，不给后续级噪音", 1, ladder.size)
        assertTrue(ladder.single().startsWith("✗ L1"))
    }

    @Test
    fun `diagnoseLadder_proot不能跑时停在L2`() {
        val env = installedEnvironment()
        val factory = FakeFactory { _, argv ->
            // proot --version 无输出且退出码非 0 → L2 判负
            if ("--version" in argv) ScriptedChannel("", 1) else ScriptedChannel("", 0)
        }
        val ladder = newLauncher(env, factory = factory).diagnoseLadder()

        assertEquals(2, ladder.size)
        assertTrue(ladder.last().startsWith("✗ L2"))
    }

    @Test
    fun `diagnoseLadder_全绿路径六级通过`() {
        val env = installedEnvironment()
        val factory = FakeFactory { _, argv ->
            if ("--version" in argv) ScriptedChannel("proot 5.3.0\n", 0)
            else ScriptedChannel("${TermuxLauncher.L6_MARKER}\n", 0)
        }
        val ladder = newLauncher(env, factory = factory).diagnoseLadder()

        assertEquals("六级都要报告", 6, ladder.size)
        assertTrue("全绿：$ladder", ladder.all { it.startsWith("✓") })
        assertTrue(ladder.first().contains("L1"))
        assertTrue(ladder.last().contains("L6"))
    }

    @Test
    fun `diagnoseLadder_命令往返失败时报L6并给复现命令行`() {
        val env = installedEnvironment()
        val factory = FakeFactory { _, argv ->
            if ("--version" in argv) ScriptedChannel("proot 5.3.0\n", 0)
            else ScriptedChannel("unexpected output\n", 1)
        }
        val ladder = newLauncher(env, factory = factory).diagnoseLadder()

        assertEquals(6, ladder.size)
        assertTrue(ladder.last().startsWith("✗ L6"))
        assertTrue("失败级要带复现线索", ladder.last().contains("复现命令行"))
    }

    @Test
    fun `lastLaunchCommand_未启动时为可读占位`() {
        val launcher = newLauncher(installedEnvironment())
        assertEquals("(尚未启动过)", launcher.describeLastLaunch())
    }
}
