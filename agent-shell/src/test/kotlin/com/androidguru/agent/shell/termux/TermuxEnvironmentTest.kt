package com.androidguru.agent.shell.termux

import com.androidguru.agent.shell.audit.AuditLog
import com.androidguru.agent.shell.audit.InMemoryAuditLog
import com.androidguru.agent.shell.net.Downloader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * TermuxEnvironment 单元测试 —— 全部用本地构造的假 bootstrap zip，不碰真实网络。
 *
 * 覆盖：ZipEntry 内容判别符号链接、SYMLINKS.txt 恢复规则（链接名相对 $PREFIX、
 * 目标相对链接所在目录）、完整安装管线（解压 → 链接 → 双软件源 → 引导脚本 → marker）、
 * SHA256 校验失败拒绝安装、复用已校验缓存、verify / diagnose / uninstall。
 */
class TermuxEnvironmentTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val audit = InMemoryAuditLog()

    // ---------- 假 bootstrap 构造 ----------

    /** 假 ELF：0x7FELF 魔数 + 填充（内容判别必须认成"真身"）。 */
    private fun elfBytes(): ByteArray {
        val head = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
        return head + ByteArray(64) { (it % 97).toByte() }
    }

    private fun scriptBytes(): ByteArray =
        "#!/data/data/com.termux/files/usr/bin/sh\necho ok\n".toByteArray()

    /** 构造一个与真机 bootstrap 布局同构的迷你 zip（条目相对 $PREFIX）。 */
    private var zipSeq = 0

    private fun newFakeBootstrap(): File {
        // 名字带序号：同一测试里可能构造多个（如 newEnvironment 的默认 entry + 测试自用的 zip）
        val zip = folder.newFile("fake-bootstrap-${zipSeq++}.zip")
        val entries: List<Pair<String, ByteArray>> = listOf(
            "bin/bash" to elfBytes(),
            "bin/login" to elfBytes(),
            "bin/dash" to elfBytes(),
            "bin/apt" to scriptBytes(),
            "bin/dpkg" to elfBytes(),
            "lib/libandroid-support.so" to elfBytes(),
            "lib/libncursesw.so.6.5" to elfBytes(),
            // 内容判别符号链接：短单行路径文本
            "lib/libncurses.so.6" to "libncursesw.so.6.5".toByteArray(),
            // 官方包的链接清单：zip 内对应条目是空文件，链接信息全在这一行行文本里
            "SYMLINKS.txt" to "dash←./bin/sh\n".toByteArray(),
        )
        // 固定时间戳：ZipEntry 默认取当前时间（DOS 格式 2 秒粒度），两次构造跨边界时
        // 字节不同 → SHA256 不同 → install 对不上 catalog 哈希（CI 上已实际偶发）。
        // 固定后任何两个假 zip 字节级一致，跨用例哈希恒匹配。
        val fixedTime = 1_700_000_000_000L
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name).apply { time = fixedTime })
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return zip
    }

    /** 测试用发行物条目：SHA256 现算自假 zip，镜像指向必不通的本地端口（证明没走网络）。 */
    private fun newEntry(zip: File, sha256: String = Downloader.sha256(zip)) =
        TermuxBootstrapCatalog.BootstrapEntry(
            abi = "arm64-v8a",
            fileName = "bootstrap-aarch64.zip",
            mirrors = listOf("http://127.0.0.1:1/bootstrap-aarch64.zip"),
            sha256 = sha256,
        )

    private fun newEnvironment(entry: TermuxBootstrapCatalog.BootstrapEntry? = null): TermuxEnvironment =
        TermuxEnvironment(
            baseDir = folder.root,
            downloader = Downloader(),
            audit = audit,
            catalog = entry ?: newEntry(newFakeBootstrap()),
            hostAbiProvider = { "arm64-v8a" },
            minInstallBytes = 1L, // 假 zip 只有几百字节，真机门槛 5 MB 在测试里关掉
        )

    private fun completeInstall(env: TermuxEnvironment, zip: File = newFakeBootstrap()): TermuxEnvironment.Installed =
        runBlocking { env.install(TermuxEnvironment.InstallOptions(sourceZip = zip)) }

    /** recordSetting 事件的 what 值（事件 type 恒为 "setting"，语义信息在 payload.what 里）。 */
    private fun settingWhats(): List<String> =
        audit.recent(200)
            .filter { it.type == AuditLog.TYPE_SETTING }
            .map { it.payload["what"] ?: "" }

    // ---------- 解压与内容判别 ----------

    @Test
    fun `解压_ELF与脚本落盘_路径文本判为符号链接`() {
        val env = newEnvironment(entry = newEntry(newFakeBootstrap()))
        val target = folder.newFolder("extract-a")
        val zip = newFakeBootstrap()

        val report = env.extractZip(zip, target)

        assertEquals(9, report.entries)
        assertEquals(1, report.contentDetectedLinks)

        val bash = File(target, "bin/bash")
        assertTrue("ELF 内容应落为普通文件", bash.isFile)
        assertTrue("bin 下的文件应有执行位", bash.canExecute())

        val apt = File(target, "bin/apt")
        assertTrue("#! 脚本应落为普通文件而不是链接", apt.isFile)
        assertEquals(scriptBytes().decodeToString(), apt.readText())

        val link = File(target, "lib/libncurses.so.6")
        assertTrue("短单行路径文本应判为符号链接", Files.isSymbolicLink(link.toPath()))
        assertEquals("libncursesw.so.6.5", Files.readSymbolicLink(link.toPath()).toString())
    }

    @Test
    fun `解压_含NUL与多行文本不算链接_路径穿越被拒`() {
        val env = newEnvironment(entry = newEntry(newFakeBootstrap()))
        val target = folder.newFolder("extract-b")
        val zip = folder.newFile("weird.zip")
        val entries: List<Pair<String, ByteArray>> = listOf(
            "bin/nulbin" to byteArrayOf(0x01, 0x00, 0x02, 0x03),
            "bin/multiline" to "a/b\nc/d".toByteArray(),
            "../evil" to "boom".toByteArray(),
            "bin/ok" to elfBytes(),
        )
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }

        val report = env.extractZip(zip, target)

        assertEquals("路径穿越条目应被拒绝", 1, report.skippedEntries)
        assertEquals(3, report.entries)
        assertFalse("含 NUL 的二进制不应成为链接", Files.isSymbolicLink(File(target, "bin/nulbin").toPath()))
        assertFalse("多行文本不应成为链接", Files.isSymbolicLink(File(target, "bin/multiline").toPath()))
        assertFalse("越界文件绝不能落在目标之外", File(target.parentFile, "evil").exists())
    }

    // ---------- SYMLINKS.txt 恢复 ----------

    @Test
    fun `SYMLINKS恢复_链接名相对PREFIX_目标相对链接所在目录`() {
        val env = newEnvironment(entry = newEntry(newFakeBootstrap()))
        val prefix = folder.newFolder("prefix-a")
        val bin = File(prefix, "bin").apply { mkdirs() }
        val dash = File(bin, "dash").apply { writeBytes(elfBytes()); setExecutable(true) }
        // 清单：链接名相对 $PREFIX；裸名目标相对链接所在目录；guest 绝对路径剥离 $PREFIX 后同样处理
        val list = File(prefix, "SYMLINKS.txt")
        list.writeText(
            """
            dash←./bin/sh
            dash←./bin/sh-abs
            /data/data/com.termux/files/usr/dash←./bin/deep
            ghost←./bin/missing
            """.trimIndent() + "\n",
        )

        val report = env.restoreSymlinks(prefix)

        assertEquals("4 条清单都应建出链接", 4, report.created)
        assertEquals("1 条指向尚不存在（ghost），悬空不算失败", 1, report.unresolved)
        assertEquals(0, report.failed)
        assertFalse("清单消费后应删除", list.exists())

        // 关键断言：链接落盘为**相对**兄弟名 —— 环境整体被 proot 映射，绝对宿主路径在 guest 里不存在
        val sh = File(prefix, "bin/sh")
        assertEquals("dash", Files.readSymbolicLink(sh.toPath()).toString())
        assertEquals("sh 应解析到同目录 dash", dash.canonicalFile, sh.canonicalFile)

        // guest 绝对目标 /data/data/com.termux/files/usr/dash → 剥前缀成 dash，相对 bin/ → bin/dash
        val deep = File(prefix, "bin/deep")
        assertEquals("dash", Files.readSymbolicLink(deep.toPath()).toString())
        assertEquals(File(bin, "dash").canonicalFile, deep.canonicalFile)
    }

    // ---------- 完整安装管线 ----------

    @Test
    fun `install_本地zip全管线_marker原子写且verify全绿`() {
        val env = newEnvironment()
        val zip = newFakeBootstrap()
        val progress = mutableListOf<TermuxEnvironment.Progress>()

        val installed = runBlocking {
            env.install(TermuxEnvironment.InstallOptions(sourceZip = zip) { progress += it })
        }

        assertTrue("应产出安装信息", installed.totalBytes > 0)
        assertEquals("安装体积应与工作目录实际体积一致", TermuxEnvironment.dirSize(env.filesDir), installed.totalBytes)
        assertTrue(env.isInstalled)

        // marker：原子写 + 内容可读
        val marker = File(env.filesDir, "bootstrap.json")
        assertTrue(marker.isFile)
        val markerText = marker.readText()
        assertTrue(markerText.contains("\"kind\": \"termux-bootstrap\""))
        assertTrue(markerText.contains(Downloader.sha256(zip)))
        assertFalse("原子写不应留下临时文件", File(env.filesDir, "bootstrap.json.tmp").exists())

        // 顶层兼容链接
        assertTrue("files/bin 应链接到 usr/bin", Files.isSymbolicLink(File(env.filesDir, "bin").toPath()))

        // 双软件源 + dpkg 配置 + 引导脚本 + profile 挂载
        val sources = File(env.prefixDir, "etc/apt/sources.list")
        assertTrue(sources.isFile && sources.readText().contains("deb https://packages.termux.dev/apt/termux-main stable main"))
        assertTrue(File(env.prefixDir, "etc/apt/apt.conf.d/99-agsh-dpkg").readText().contains("--force-confold"))
        val init = File(env.filesDir, "etc/agsh-init.sh")
        assertTrue(init.isFile && init.canExecute())
        assertTrue(init.readText().contains("export PREFIX=/data/data/com.termux/files/usr"))
        assertTrue(File(env.prefixDir, "etc/profile").readText().contains("AGSH 引导"))

        // 完整性检查全绿（无 ✗）
        val verify = env.verify()
        assertTrue("verify 应有检查项", verify.isNotEmpty())
        assertTrue("verify 应全绿：$verify", verify.none { it.startsWith("✗") })
        assertTrue("bin/sh 链接应可解析", verify.any { it.contains("bin/sh") && it.contains("可解析") })

        // 审计与进度
        assertTrue("应有完成审计", settingWhats().contains("termux.install_done"))
        assertTrue(progress.any { it is TermuxEnvironment.Progress.Done })

        // diagnose 分步输出
        val diagnose = env.diagnose()
        assertEquals("诊断应有 5 个步骤", 5, diagnose.size)
        assertTrue(diagnose[0].startsWith("[1]"))
        assertTrue(diagnose[4].contains("已安装"))
    }

    @Test
    fun `install_占位哈希拒绝安装并审计`() {
        val zip = newFakeBootstrap()
        val env = newEnvironment(entry = newEntry(zip, sha256 = "0".repeat(64)))

        val error = runCatching {
            runBlocking { env.install(TermuxEnvironment.InstallOptions(sourceZip = zip)) }
        }.exceptionOrNull()

        assertNotNull("占位哈希必须抛错", error)
        assertTrue("错误信息要指明原因：$error", error!!.message!!.contains("拒绝安装"))
        assertFalse(env.isInstalled)
        assertTrue("应有拒绝审计", settingWhats().contains("termux.install_refused"))
        assertFalse("不应写 marker", File(env.filesDir, "bootstrap.json").exists())
    }

    @Test
    fun `install_SHA256不匹配拒绝安装且不写marker`() {
        val zip = newFakeBootstrap()
        val env = newEnvironment(entry = newEntry(zip, sha256 = "a".repeat(64)))

        val error = runCatching {
            runBlocking { env.install(TermuxEnvironment.InstallOptions(sourceZip = zip)) }
        }.exceptionOrNull()

        assertTrue("应为校验失败异常", error is Downloader.ChecksumMismatch)
        assertFalse(env.isInstalled)
        assertFalse("不应写 marker", File(env.filesDir, "bootstrap.json").exists())
        assertTrue("校验失败必须有审计", settingWhats().contains("termux.install_failed"))
    }

    @Test
    fun `install_ABI不匹配拒绝安装`() {
        val zip = newFakeBootstrap()
        val env = TermuxEnvironment(
            baseDir = folder.root,
            downloader = Downloader(),
            audit = audit,
            catalog = newEntry(zip),
            hostAbiProvider = { "x86_64" },
            minInstallBytes = 1L,
        )

        val error = runCatching {
            runBlocking { env.install(TermuxEnvironment.InstallOptions(sourceZip = zip)) }
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error!!.message!!.contains("ABI"))
        assertFalse(env.isInstalled)
    }

    @Test
    fun `install_复用已校验缓存zip跳过下载`() {
        val zip = newFakeBootstrap()
        val env = newEnvironment(entry = newEntry(zip))
        // 预置已下载的 zip（上一轮失败留下的半成品场景）
        env.filesDir.mkdirs()
        zip.copyTo(File(env.filesDir, "bootstrap.zip"), overwrite = true)

        val installed = runBlocking { env.install() }

        assertTrue(installed.totalBytes > 0)
        assertTrue("应有复用审计", settingWhats().contains("termux.install_zip_reused"))
        assertTrue("缓存 zip 应保留以便下次复用", File(env.filesDir, "bootstrap.zip").isFile)
    }

    @Test
    fun `install_镜像全不可达时报错清晰且半成品保留`() {
        val env = newEnvironment(entry = newEntry(newFakeBootstrap()))

        val error = runCatching {
            runBlocking { env.install() } // 无缓存 zip、无 sourceZip → 走镜像下载（127.0.0.1:1 必不通）
        }.exceptionOrNull()

        assertTrue("应报所有镜像失败：$error", error!!.message!!.contains("所有镜像"))
        assertFalse(env.isInstalled)
        assertTrue("失败要有审计", settingWhats().contains("termux.install_failed"))
    }

    // ---------- verify / diagnose / 卸载 / 未安装态 ----------

    @Test
    fun `verify_关键文件损坏时报✗`() {
        val env = newEnvironment()
        completeInstall(env)
        File(env.prefixDir, "bin/bash").delete()

        val verify = env.verify()
        assertTrue("缺 bash 应报 ✗：$verify", verify.any { it.startsWith("✗ bin/bash") })
        assertFalse("损坏后不应算已安装", env.isInstalled)
    }

    @Test
    fun `uninstall_递归清理工作目录`() {
        val env = newEnvironment()
        completeInstall(env)
        assertTrue(env.filesDir.isDirectory)

        env.uninstall()

        assertFalse("卸载后工作目录应消失", env.filesDir.exists())
        assertFalse(env.isInstalled)
        assertTrue("卸载应有审计", settingWhats().contains("termux.uninstall"))
    }

    @Test
    fun `未安装态_installedInfo为null_verify与diagnose可读`() {
        val env = newEnvironment()

        assertNull(env.installedInfo())
        assertFalse(env.isInstalled)
        assertTrue("未安装 verify 应有 ✗ 项", env.verify().any { it.startsWith("✗") })
        assertTrue("未安装 diagnose 应给出结论", env.diagnose().last().contains("未安装"))
        assertTrue("describe 应给出安装指引", env.describe().contains("未安装"))
        assertEquals(
            "环境内引导脚本路径映射",
            "/data/data/com.termux/files/etc/agsh-init.sh",
            TermuxEnvironment.initScriptInnerPath(),
        )
    }

    @Test
    fun `dirSize_不跟随符号链接重复计数`() {
        // yl-ai 真机坑：File.walkTopDown 跟随符号链接，coreutils 一个真身被按几十个
        // 命令名重复计入（204 MB 报成 502 MB）。这里造一个真身 + 10 个指向它的链接验证。
        val dir = folder.newFolder("size-check")
        val bin = File(dir, "bin").apply { mkdirs() }
        val real = File(bin, "real").apply { writeBytes(ByteArray(10_000)) }
        repeat(10) { i ->
            runCatching { Files.createSymbolicLink(File(bin, "link$i").toPath(), real.toPath()) }
        }

        val size = TermuxEnvironment.dirSize(dir)

        assertTrue("只应统计普通文件一次（得到 $size）", size in 10_000..20_000)
    }
}
