package com.androidguru.agent.shell.termux

import com.androidguru.agent.shell.audit.AuditLog
import com.androidguru.agent.shell.net.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Termux 环境管理（纯 JVM 版）—— bootstrap 下载 / 校验 / 解压 / 链接恢复 / 完整性验证。
 *
 * 重写自 yl-ai 的 `TermuxEnvironment`（object + android.content.Context），
 * 关键差异：
 * - **去 Context 化**：根目录 [baseDir] 由构造注入（Android 宿主传 `filesDir`，
 *   桌面宿主传任意可写目录），实际工作目录是 [filesDir]（即 `baseDir/termux`），
 *   与 yl-ai 的 `filesDir(context)` 映射关系一致；
 * - **Downloader / AuditLog 注入**：不再依赖 Android 日志与具体网络栈；
 * - **ABI 门可注入**：[hostAbiProvider] 默认从 `os.arch` 推断，测试与模拟器场景可覆盖；
 * - **安装包可本地注入**：[install] 的 [InstallOptions.sourceZip] 允许宿主把 bootstrap
 *   打进 APK assets 直接安装（跳过下载），SHA256 校验照做 —— 安装链路不变，只是换了喂料方式。
 *
 * 【为什么是 Termux bootstrap 而不是再拼一个 rootfs】（yl-ai DEVLOG 8.1）
 * Alpine rootfs 走"整体翻译 + apk 生态"，重且偏运维；Termux bootstrap 是为无 root
 * 安卓编译的完整用户态，二进制直接可执行、apt 包多、装得快。两者并存，各管一段。
 *
 * 【失败回滚策略】（yl-ai DEVLOG 8.7 的教训）
 * 安装失败时**只删标记文件**（[markerFile]），`bootstrap.zip`、`.part` 半分片与
 * 已解压内容全部保留，下次 install() 会复用已校验的 zip / 断点续传并覆盖解压。
 * 早期实现失败即 `deleteRecursively()`，把用户已下好的 30 MB 一起删掉 —— 流量在
 * 移动设备上是真实成本，回滚的目的是"回到可重试状态"，不是"假装什么都没发生"。
 */
class TermuxEnvironment(
    /** 宿主可写根目录（对应 Android `filesDir`）。工作目录 [filesDir] 建在其下。 */
    baseDir: File,
    /** 下载器（断点续传 + SHA256 校验由它保证）。 */
    private val downloader: Downloader,
    /** 审计日志；null 表示宿主不接审计（所有审计点自动跳过，功能不受影响）。 */
    private val audit: AuditLog? = null,
    /** 指定 bootstrap 发行物；null 时按 [hostAbiProvider] 的 ABI 从 [TermuxBootstrapCatalog.CATALOG] 选取。 */
    private val catalog: TermuxBootstrapCatalog.BootstrapEntry? = null,
    /** 宿主 ABI 提供者（默认从 `os.arch` 推断；Android 宿主可直连 `Build.SUPPORTED_ABIS`）。 */
    private val hostAbiProvider: () -> String = { detectHostAbi() },
    /**
     * 判定"已安装"的最小字节数。真机 bootstrap 约 200 MB；设一个 5 MB 级的门槛挡住
     * 解压到一半就断电的残骸。测试构造迷你假 zip 时可调小。
     */
    private val minInstallBytes: Long = DEFAULT_MIN_INSTALL_BYTES,
) {

    /** Termux 工作根目录：`baseDir/termux`。proot 启动时整个目录映射到 guest 的 $GUEST_FILES。 */
    val filesDir: File = File(baseDir, "termux")

    /** $PREFIX 的宿主真身：`filesDir/usr`。bootstrap 全部解压到这里。 */
    val prefixDir: File = File(filesDir, "usr")

    /** 环境内 HOME 的宿主真身：`filesDir/home`。 */
    val homeDir: File = File(filesDir, "home")

    private val markerFile: File = File(filesDir, MARKER_NAME)
    private val zipFile: File = File(filesDir, "bootstrap.zip")
    private val initScriptFile: File = File(filesDir, "etc/$INIT_SCRIPT_NAME")

    /** 安装结果摘要（marker 信息的结构化视图）。 */
    data class Installed(
        val prefix: File,
        val home: File,
        val installedAt: Long,
        val totalBytes: Long,
        val describe: String,
    )

    /** 安装进度事件（与 AlpineContainer.Progress 同构，宿主可用同一套 UI 呈现）。 */
    sealed interface Progress {
        data class Stage(val text: String) : Progress
        data class Downloading(val received: Long, val total: Long, val text: String) : Progress
        data class Verifying(val text: String) : Progress
        data class Extracting(val text: String) : Progress
        data class Failed(val message: String) : Progress
        data class Done(val installed: Installed) : Progress
    }

    /** 安装参数。[sourceZip] 提供时跳过下载（宿主内置 assets 场景），校验照做。 */
    data class InstallOptions(
        val sourceZip: File? = null,
        val onProgress: (Progress) -> Unit = {},
    )

    /** 环境是否可用：marker + 关键文件 + 最小体积三重判定（详见 [installedInfo]）。 */
    val isInstalled: Boolean
        get() = installedInfo() != null

    /**
     * 已安装信息。三重判定缺一不可：
     * 1. 关键文件在（bin/bash、bin/login、libandroid-support —— 没有它们环境必残）；
     * 2. marker 在（记录了来源与哈希，是"完整走过安装流程"的凭据）；
     * 3. 体积达标（[minInstallBytes]，挡解压到一半的残骸）。
     */
    fun installedInfo(): Installed? {
        val essential = listOf("bin/bash", "bin/login", "lib/libandroid-support.so")
        if (essential.any { !File(prefixDir, it).isFile }) return null
        if (!markerFile.isFile) return null
        val total = dirSize(filesDir)
        if (total < minInstallBytes) return null
        return Installed(
            prefix = prefixDir,
            home = homeDir,
            installedAt = markerFile.lastModified(),
            totalBytes = total,
            describe = readDescribe(),
        )
    }

    /**
     * 安装完整流程：下载（多镜像回退）→ SHA256 校验 → 解压 → 内容判别符号链接 →
     * SYMLINKS.txt 恢复 → 顶层链接 → 目录与双软件源 → 引导脚本 → marker（原子写）。
     *
     * @throws IllegalStateException 占位哈希 / ABI 不匹配 / 目录缺失 / 完成后自检不过
     * @throws Downloader.ChecksumMismatch 哈希不符（拒绝安装 + 审计；半成品按类注释策略保留）
     */
    suspend fun install(options: InstallOptions = InstallOptions()): Installed = withContext(Dispatchers.IO) {
        val onProgress = options.onProgress
        try {
            val entry = resolveEntry()
            if (TermuxBootstrapCatalog.isPlaceholderSha256(entry.sha256)) {
                val msg = "内置的 bootstrap 校验值尚未锚定，出于安全考虑拒绝安装。" +
                    "请在 TermuxBootstrapCatalog.CATALOG 里填入官方发布的 SHA256。"
                audit?.recordSetting("termux.install_refused", "placeholder_sha256 abi=${entry.abi}")
                onProgress(Progress.Failed(msg))
                throw IllegalStateException(msg)
            }
            val hostAbi = hostAbiProvider()
            if (hostAbi != entry.abi) {
                val msg = "当前宿主 ABI 为 $hostAbi，Termux 环境目前只提供 ${entry.abi} 版本。"
                audit?.recordSetting("termux.install_refused", "abi=$hostAbi need=${entry.abi}")
                onProgress(Progress.Failed(msg))
                throw IllegalStateException(msg)
            }

            audit?.recordSetting(
                "termux.install_start",
                "abi=${entry.abi} mirrors=${entry.mirrors.size} sha=${entry.sha256.take(12)}…",
            )

            if (filesDir.isDirectory) cleanInstallLeftovers()
            filesDir.mkdirs()

            // 取 zip：本地注入 > 复用已校验缓存 > 多镜像下载
            val zip: File = when {
                options.sourceZip != null -> {
                    onProgress(Progress.Stage("使用宿主提供的本地安装包…"))
                    verifyShaOrThrow(options.sourceZip, entry.sha256)
                    options.sourceZip.copyTo(zipFile, overwrite = true)
                    zipFile
                }
                zipFile.isFile && Downloader.sha256(zipFile).equals(entry.sha256, ignoreCase = true) -> {
                    // 上一轮失败留下的已校验 zip 直接复用 —— 回滚策略保留半成品的意义所在
                    onProgress(Progress.Verifying("已有校验通过的安装包，跳过下载"))
                    audit?.recordSetting("termux.install_zip_reused", zipFile.name)
                    zipFile
                }
                else -> downloadWithMirrors(entry, onProgress)
            }

            onProgress(Progress.Extracting("解压中（真机约 3500 个条目，需要几十秒）…"))
            val extraction = extractZip(zip, prefixDir)

            onProgress(Progress.Stage("恢复符号链接与目录结构…"))
            val links = restoreSymlinks(prefixDir)
            linkTopLevel()
            ensureDirs()

            onProgress(Progress.Stage("配置软件源与引导脚本…"))
            writeAptSources()
            writeInitScript()
            writeProfile()

            // 先写 marker 再做安装态检查：installedInfo 的三重判定包含"marker 在"
            writeMarker(entry, extraction, links)
            val result = installedInfo()
                ?: throw IllegalStateException("安装完成但状态检查未通过（体积或关键文件不足），请查看 diagnose()")
            audit?.recordSetting(
                "termux.install_done",
                "entries=${extraction.entries} links=${links.created} ${result.totalBytes / 1024 / 1024} MB",
            )
            onProgress(Progress.Done(result))
            result
        } catch (t: Throwable) {
            // 回滚策略（DEVLOG 8.7）：只删标记，保留 zip / .part / 已解压内容，
            // 下次重装覆盖复用。绝不 deleteRecursively() 砸掉用户已下载的 30 MB。
            runCatching { markerFile.delete() }
            audit?.recordSetting("termux.install_failed", "${t.javaClass.simpleName}: ${t.message}")
            onProgress(
                Progress.Failed(
                    "安装失败：${t.message ?: t.javaClass.simpleName}；已下载内容已保留，重试会从断点继续",
                ),
            )
            throw t
        }
    }

    /** 解析生效的发行物条目（显式注入优先，否则按宿主 ABI 查目录）。 */
    private fun resolveEntry(): TermuxBootstrapCatalog.BootstrapEntry {
        catalog?.let { return it }
        val abi = hostAbiProvider()
        return TermuxBootstrapCatalog.entryFor(abi)
            ?: throw IllegalStateException(
                "TermuxBootstrapCatalog 里没有 ABI=$abi 的条目；该架构的 bootstrap 哈希尚未锚定，拒绝安装",
            )
    }

    /** 多镜像按序下载；单镜像内部最多重试 2 次（回退交给下一个镜像更有价值）。 */
    private suspend fun downloadWithMirrors(
        entry: TermuxBootstrapCatalog.BootstrapEntry,
        onProgress: (Progress) -> Unit,
    ): File {
        var lastError: Throwable? = null
        for ((index, url) in entry.mirrors.withIndex()) {
            onProgress(Progress.Stage("正在下载 Termux bootstrap（镜像 ${index + 1}/${entry.mirrors.size}）…"))
            try {
                downloader.download(
                    url = url,
                    dest = zipFile,
                    expectedSha256 = entry.sha256,
                    maxAttempts = 2,
                ) { received, total ->
                    onProgress(Progress.Downloading(received, total, formatProgress(received, total)))
                }
                // Downloader 已做 SHA256 校验（不符抛 ChecksumMismatch 且删 .part）
                return zipFile
            } catch (e: Downloader.ChecksumMismatch) {
                // 哈希不符：不做镜像回退 —— 官方源发布新版本导致哈希变更时换镜像也会失败；
                // 被篡改的产物换镜像更没有意义。拒绝安装的审计与进度上报交给 install() 的 catch 统一处理。
                throw e
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw IllegalStateException(
            "所有镜像都无法下载（${entry.mirrors.size} 个）：${lastError?.message ?: "未知原因"}",
            lastError,
        )
    }

    private fun verifyShaOrThrow(zip: File, expected: String) {
        val actual = Downloader.sha256(zip)
        if (!actual.equals(expected, ignoreCase = true)) {
            throw Downloader.ChecksumMismatch(expected.lowercase(), actual)
        }
    }

    /** 重装前的清扫：只清临时解压目录与旧引导脚本，不动 zip 与已解压内容。 */
    private fun cleanInstallLeftovers() {
        runCatching { File(filesDir, "tmp-extract").deleteRecursively() }
        runCatching { initScriptFile.delete() }
    }

    /**
     * 解压 bootstrap zip 到 [target]（$PREFIX 真身）。
     *
     * 【ZipEntry 内容判别符号链接】（yl-ai DEVLOG 8.4）
     * `ZipInputStream` 只读本地文件头，拿不到中央目录里的 unix 模式位
     * （Android 的 ZipEntry 更是根本没有 getExternalAttributes()）。
     * 改用内容判别：链接目标是一小段路径文本，而真身要么是 ELF（0x7F 'E' 'L' 'F'）、
     * 要么是 "#!" 开头的脚本、要么含 NUL（二进制）。判据反过来写（"像路径就当链接"）
     * 会把恰好内容是路径的正常文件误判成链接 —— 那比漏判严重得多，所以判据按
     * "证明它是真身"的方向写，剩下的短单行文本才判成链接。
     *
     * 判成链接但创建失败时降级为普通文件落盘（内容即目标文本）：环境会坏，
     * 但留下了可诊断的痕迹，而不是凭空消失。
     */
    internal fun extractZip(zip: File, target: File): ExtractReport {
        var entries = 0
        var links = 0
        var skipped = 0
        ZipInputStream(zip.inputStream().buffered(BUFFER_SIZE)).use { zis ->
            val execNames = mutableListOf<String>()
            while (true) {
                val entry: ZipEntry = zis.nextEntry ?: break
                val name = entry.name
                if (name.isBlank() || name.contains("..")) {
                    // 路径穿越与空名直接丢弃：bootstrap 是受信产物，但 zip 解析器不该信任任何名字
                    skipped++
                    zis.closeEntry()
                    continue
                }
                val out = File(target, name)
                if (entry.isDirectory) {
                    out.mkdirs()
                    zis.closeEntry()
                    continue
                }
                out.parentFile?.mkdirs()

                val bytes = zis.readBytes()

                // SYMLINKS.txt 必须落为普通文件：它是链接清单本身，
                // 若被内容判别误判成链接（内容是短单行文本），
                // restoreSymlinks 就再也读不到清单，bin/sh 等关键链接永远恢复不了
                val isLinkList = name.substringAfterLast('/') == SYMLINKS_LIST_NAME
                val linkTarget = if (!isLinkList && looksLikeSymlinkTarget(bytes)) {
                    String(bytes, Charsets.UTF_8).trim()
                } else {
                    null
                }

                if (linkTarget != null) {
                    runCatching { Files.deleteIfExists(out.toPath()) }
                    val linked = runCatching {
                        Files.createSymbolicLink(out.toPath(), File(linkTarget).toPath())
                    }.isSuccess
                    if (linked) {
                        links++
                    } else {
                        out.writeBytes(bytes) // 降级落盘：留痕，便于诊断"为什么这个命令不见了"
                    }
                } else {
                    FileOutputStream(out).use { it.write(bytes) }
                }

                // 可执行位：bin/libexec 下的与动态库。zip 里没有模式位，只能按名字约定补
                if (name.startsWith("bin/") || name.startsWith("libexec/") ||
                    name.endsWith(".so") || name.contains(".so.")
                ) {
                    runCatching { out.setExecutable(true, false) }
                }
                execNames += name
                entries++
                zis.closeEntry()
            }
        }
        val report = ExtractReport(entries, links, skipped)
        audit?.recordSetting("termux.extract", "entries=$entries links=$links skipped=$skipped")
        return report
    }

    /** 解压统计（写进 marker 与审计）。 */
    data class ExtractReport(val entries: Int, val contentDetectedLinks: Int, val skippedEntries: Int)

    /**
     * 内容判别：这段字节更像"符号链接的目标文本"还是"真身"。
     * 判据全部按"证明它是真身"的方向写（见 [extractZip] 的 KDoc）。
     */
    private fun looksLikeSymlinkTarget(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > MAX_LINK_TARGET_BYTES) return false
        if (bytes.size >= 4 && bytes[0] == 0x7F.toByte() &&
            bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()
        ) {
            return false // ELF 真身
        }
        val text = String(bytes, Charsets.UTF_8)
        if (text.startsWith("#!")) return false // 脚本真身
        if (bytes.any { it == 0.toByte() }) return false // 二进制真身
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.contains('\n')) return false // 链接目标是单行短文本
        return true
    }

    /**
     * 按 SYMLINKS.txt 恢复符号链接。
     *
     * 【为什么必须做】（yl-ai DEVLOG 8.5）
     * 官方包把链接清单写进 SYMLINKS.txt（格式 `目标←链接名`，U+2190 分隔），
     * zip 里对应条目是**空文件**。只靠内容判别会把它们判成"空文件、不是链接"，
     * 于是 bin/sh、libc 系链接真的变成空文件 —— 环境里一半命令消失，且报错指不到原因。
     *
     * 【解析规则 —— 曾连错两次，两次症状完全不同】（yl-ai DEVLOG 8.7）
     * 正确规则：**链接名相对 $PREFIX，目标相对链接所在目录**（即兄弟节点）。
     * `dash←./bin/sh` 读作"bin/sh 指向同目录的 dash"。
     * - 错法一：目标相对链接目录但没去掉左侧 `./` → 全部悬空（lib/lib/…），好查；
     * - 错法二：目标相对 $PREFIX → 链接不悬空、ls 看着正常，但 bin/sh 指向
     *   $PREFIX/dash（真身在 $PREFIX/bin/dash），所有经 sh 的调用**静默失败** —— 极难查。
     *
     * 目标也可能写成 guest 绝对路径（/data/data/com.termux/files/usr/…），
     * 统一剥离 [TermuxBootstrapCatalog.GUEST_FILES_PREFIX] 后按相对目标处理。
     * 指向尚不存在目标的链接（悬空）**不算失败**：部分链接指向装包后才出现的文件。
     * 恢复完成后删除 SYMLINKS.txt —— 它还在就意味着清单没被消费（verify 会提示）。
     */
    internal fun restoreSymlinks(prefix: File): SymlinkReport {
        val listFile = File(prefix, SYMLINKS_LIST_NAME)
        if (!listFile.isFile) {
            // 老版本 bootstrap 可能没有清单；内容判别已覆盖 zip 内嵌链接，这里只提示
            return SymlinkReport(created = 0, failed = 0, unresolved = 0, samples = listOf("(无 SYMLINKS.txt)"))
        }

        var created = 0
        var failed = 0
        var unresolved = 0
        val samples = mutableListOf<String>()

        listFile.forEachLine { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachLine
            val sep = line.indexOf(LINK_ARROW)
            if (sep <= 0 || sep >= line.length - 1) return@forEachLine

            val rawTarget = line.substring(0, sep).trim()
            val linkName = line.substring(sep + 1).trim().removePrefix("./")
            if (linkName.isEmpty() || linkName.contains("..")) return@forEachLine

            val link = File(prefix, linkName)          // 链接名相对 $PREFIX
            val linkDir = link.parentFile ?: prefix
            linkDir.mkdirs()

            val targetRel = rawTarget
                .removePrefix("./")
                .let { if (it.startsWith(TermuxBootstrapCatalog.GUEST_FILES_PREFIX)) {
                    it.removePrefix(TermuxBootstrapCatalog.GUEST_FILES_PREFIX)
                } else {
                    it
                } }
                .trimStart('/')                          // 目标相对链接所在目录（兄弟节点）
            val targetAbs = File(linkDir, targetRel)
            if (!targetAbs.exists()) unresolved++        // 悬空不算失败（可能指向装包后才有的文件）

            // 落盘成相对链接：环境整体被 proot 映射，绝对宿主路径在 guest 里不存在
            val relative = runCatching {
                linkDir.canonicalFile.toPath()
                    .relativize(targetAbs.canonicalFile.toPath())
                    .toString()
            }.getOrElse { targetAbs.absolutePath }

            runCatching { Files.deleteIfExists(link.toPath()) }
            val ok = runCatching {
                Files.createSymbolicLink(link.toPath(), File(relative).toPath())
            }.isSuccess
            if (ok) {
                created++
                if (samples.size < SAMPLE_LIMIT) samples += "$linkName -> $relative"
            } else {
                failed++
            }
        }

        runCatching { listFile.delete() }
        audit?.recordSetting(
            "termux.symlinks_restored",
            "created=$created failed=$failed unresolved=$unresolved",
        )
        return SymlinkReport(created, failed, unresolved, samples)
    }

    /** 链接恢复统计（写进 marker 与审计；unresolved 悬空是常态，failed 才要关注）。 */
    data class SymlinkReport(val created: Int, val failed: Int, val unresolved: Int, val samples: List<String>)

    /**
     * 顶层兼容链接：files/bin → usr/bin 等。
     * Termux 约定 $PREFIX=…/files/usr，但部分脚本按 `…/files/bin` 找东西；
     * yl-ai 真机验证过这组清单，原样保留。
     */
    private fun linkTopLevel() {
        for ((name, target) in TOP_LEVEL_LINKS) {
            val link = File(filesDir, name)
            // 已是链接或已存在同名实体都不动：重装覆盖语义下保持幂等
            val existing = runCatching { Files.readSymbolicLink(link.toPath()) }.getOrNull()
            if (existing != null) continue
            if (link.exists()) continue
            runCatching { Files.createSymbolicLink(link.toPath(), File(target).toPath()) }
        }
    }

    private fun ensureDirs() {
        listOf("tmp", "etc/apt/sources.list.d", "etc/apt/preferences.d", "var/lib/dpkg")
            .forEach { File(prefixDir, it).mkdirs() }
        homeDir.mkdirs()
        runCatching { File(prefixDir, "tmp").setWritable(true, false) } // tmp 需全员可写
    }

    /**
     * apt 双软件源：官方源 + 国内镜像（注释态）。
     * 两者都写上是刻意的：官方源在部分网络下不可达，用户放开镜像行即可顶上；
     * 这是纯文本配置，用户可自行修改 —— 不藏在代码里。99-termux-dpkg 固定
     * `--force-confold`：无人值守安装时遇到配置冲突保留旧文件，避免 apt 卡在交互问询。
     */
    private fun writeAptSources() {
        val sources = File(prefixDir, "etc/apt/sources.list")
        sources.parentFile?.mkdirs()
        sources.writeText(
            """
            # Termux 官方源与国内镜像。镜像行默认注释，官方源不可达时放开即可。
            deb $APT_SOURCE_PRIMARY stable main
            # deb $APT_SOURCE_MIRROR stable main
            """.trimIndent() + "\n",
        )

        val dpkg = File(prefixDir, "etc/apt/apt.conf.d/99-agsh-dpkg")
        dpkg.parentFile?.mkdirs()
        dpkg.writeText("DPkg::Options { \"--force-confold\"; };\n")
    }

    /**
     * guest 侧引导脚本（环境内路径！）：login/profile 会 source 它。
     * 注意路径写法用的是 **环境内** 的 /data/data/com.termux/... —— 启动时
     * [filesDir] 被 proot 自映射到那个位置（见 TermuxLauncher 的 KDoc）。
     * 与 yl-ai 的差异：改名为 agsh-init.sh、提示符标记 agsh，避免与 yl-ai 的
     * aterm 痕迹混淆；内容语义一致。
     */
    private fun writeInitScript() {
        val script = initScriptFile
        script.parentFile?.mkdirs()
        script.writeText(
            """
            #!/data/data/com.termux/files/usr/bin/sh
            # 由 Android-Agent-Framework 生成：进入终端环境时的初始化。可自由修改。
            # 注意：这里用的是环境内路径（proot 已把宿主目录映射到该位置）。

            export PREFIX=${TermuxBootstrapCatalog.GUEST_PREFIX}
            export HOME=${TermuxBootstrapCatalog.GUEST_HOME}
            export TMPDIR=${'$'}PREFIX/tmp
            export PATH=${'$'}PREFIX/bin:${'$'}PREFIX/bin/applets
            export LD_LIBRARY_PATH=${'$'}PREFIX/lib
            export LANG=C.UTF-8
            export TERM=xterm-256color

            # 提示符标明"这是框架内的环境"，避免和系统 shell 混淆
            if [ -n "${'$'}PS1" ]; then
              PS1="\[\e[32m\]agsh\[\e[0m\]:\w ${'$'} "
            fi

            # 首次进入时提示一句，之后不再打扰
            if [ ! -f ${'$'}HOME/.agsh-welcomed ]; then
              : > ${'$'}HOME/.agsh-welcomed
              echo "AGSH 终端环境已就绪。用 apt update && apt install <包名> 装工具。"
            fi
            """.trimIndent() + "\n",
        )
        runCatching { script.setExecutable(true, false) }
    }

    /** 把引导脚本挂进 profile / bash.bashrc（幂等：有标记行就不再追加）。 */
    private fun writeProfile() {
        val marker = "# --- AGSH 引导（自动加载，可删除）---"
        val snippet = "\n$marker\n" +
            "[ -f ${TermuxBootstrapCatalog.GUEST_FILES}/etc/$INIT_SCRIPT_NAME ] && " +
            ". ${TermuxBootstrapCatalog.GUEST_FILES}/etc/$INIT_SCRIPT_NAME\n"

        for (name in listOf("usr/etc/profile", "usr/etc/bash.bashrc")) {
            val f = File(filesDir, name)
            if (!f.isFile) {
                f.parentFile?.mkdirs()
                f.writeText("#!/data/data/com.termux/files/usr/bin/sh\n")
            }
            if (f.readText().contains(marker)) continue
            f.appendText(snippet)
        }
    }

    /**
     * marker 原子写：先写 .tmp 再 rename。安装中途被杀时不会留下"半截 JSON"——
     * 要么没有 marker（视为未安装，走重装），要么是完整 marker。
     */
    private fun writeMarker(
        entry: TermuxBootstrapCatalog.BootstrapEntry,
        extraction: ExtractReport,
        links: SymlinkReport,
    ) {
        val tmp = File(filesDir, "$MARKER_NAME.tmp")
        tmp.writeText(
            """
            {
              "kind": "termux-bootstrap",
              "abi": "${entry.abi}",
              "source": "${entry.mirrors.first()}",
              "sha256": "${entry.sha256}",
              "installedAt": ${System.currentTimeMillis()},
              "entries": ${extraction.entries},
              "contentLinks": ${extraction.contentDetectedLinks},
              "restoredLinks": ${links.created},
              "unresolvedLinks": ${links.unresolved},
              "initScript": "${TermuxBootstrapCatalog.GUEST_FILES}/etc/$INIT_SCRIPT_NAME"
            }
            """.trimIndent() + "\n",
        )
        if (markerFile.exists()) markerFile.delete()
        if (!tmp.renameTo(markerFile)) {
            tmp.copyTo(markerFile, overwrite = true)
            tmp.delete()
        }
    }

    /** 卸载：整个工作目录递归删除（与安装失败的"只删标记"回滚策略区分开 —— 卸载是显式意图）。 */
    fun uninstall() {
        runCatching { filesDir.deleteRecursively() }
        audit?.recordSetting("termux.uninstall", filesDir.absolutePath)
    }

    /**
     * 完整性检查：marker、关键文件、bash 可执行位、符号链接可解析。
     * 每项一行 `✓/✗` 文本，供 diagnose 与宿主设置页直接展示。
     *
     * 链接检查的教训（yl-ai DEVLOG 8.5）：悬空链接看起来"文件存在"，
     * 是最隐蔽的坏法 —— 所以对存在的符号链接必须解析并确认目标可达。
     */
    fun verify(): List<String> {
        val lines = mutableListOf<String>()
        val checks = listOf(
            "bin/bash" to "shell（环境的核心）",
            "bin/login" to "登录程序（交互式进入环境用）",
            "bin/apt" to "包管理器",
            "bin/dpkg" to "底层包工具",
            "lib/libandroid-support.so" to "安卓兼容层（几乎所有二进制都依赖）",
            "lib/libncursesw.so.6.5" to "终端库真身（链接目标）",
            "etc/apt/sources.list" to "软件源配置",
            "etc/$INIT_SCRIPT_NAME" to "框架引导脚本",
        )
        for ((rel, why) in checks) {
            lines += describeCheck(prefixDir, rel, why)
        }

        // marker 单独检查（不在 $PREFIX 下）
        val markerOk = markerFile.isFile
        lines += (if (markerOk) "✓ " else "✗ ") + "$MARKER_NAME —— 安装标记（记录来源与校验锚）"

        // 符号链接解析抽查：存在才查，缺了不冤枉（不同版本 bootstrap 布局有差异）
        for (rel in SYMLINK_PROBES) {
            val f = File(prefixDir, rel)
            val target = runCatching { Files.readSymbolicLink(f.toPath()) }.getOrNull() ?: continue
            val resolved = File(f.parentFile, target.toString())
            lines += if (resolved.exists()) {
                "✓ $rel -> $target（链接可解析）"
            } else {
                "✗ $rel -> $target（悬空链接！目标不存在）"
            }
        }

        // 清单残留提示：install 后 SYMLINKS.txt 应已被消费删除
        if (File(prefixDir, SYMLINKS_LIST_NAME).isFile) {
            lines += "⚠ SYMLINKS.txt 仍存在 —— 链接清单未被消费，环境内一半命令可能不可用，请重装"
        }
        return lines
    }

    /** 单项检查的可读描述（含链接目标与悬空告警）。 */
    private fun describeCheck(root: File, rel: String, why: String): String {
        val f = File(root, rel)
        val link = runCatching { Files.readSymbolicLink(f.toPath()) }.getOrNull()
        val resolved = link?.let { File(f.parentFile, it.toString()) }
        val exists = f.exists() || (resolved?.exists() == true)
        val mark = if (exists) "✓" else "✗"
        val exec = if (exists && f.isFile && !f.canExecute()) "（存在但无执行位！）" else ""
        val linkInfo = if (link != null) {
            "（链接 -> $link${if (resolved?.exists() == true) "" else " ← 指向不存在！"}）"
        } else {
            ""
        }
        return "$mark $rel —— $why$exec$linkInfo"
    }

    /**
     * 分步诊断报告：目录状态 → marker → 完整性 → 下载源锚 → 结论。
     * 每步一个文本块，宿主可用 joinToString("\\n") 拼成整页，也可逐条投喂 LLM。
     */
    fun diagnose(): List<String> = buildList {
        val total = dirSize(filesDir)
        add("[1] 根目录: ${filesDir.absolutePath}\n    存在=${filesDir.isDirectory} 体积=${total / 1024 / 1024} MB")
        val markerLine = if (markerFile.isFile) {
            "存在。内容摘要: " + markerFile.readLines()
                .filter { it.contains("sha256") || it.contains("abi") || it.contains("installedAt") }
                .map { it.trim().trimEnd(',') }
                .joinToString(" | ")
        } else {
            "不存在（视为未安装）"
        }
        add("[2] 安装标记 $MARKER_NAME: $markerLine")
        add("[3] 完整性检查:\n    " + verify().joinToString("\n    "))
        val entry = runCatching { resolveEntry() }.getOrNull()
        add(
            "[4] 下载源锚: abi=${entry?.abi ?: "?"} 镜像数=${entry?.mirrors?.size ?: 0}\n" +
                "    期望 SHA256: " + when {
                entry == null -> "(无该 ABI 的锚定条目)"
                TermuxBootstrapCatalog.isPlaceholderSha256(entry.sha256) -> "(未锚定！拒绝安装)"
                else -> entry.sha256
            },
        )
        add(
            "[5] 结论: " + when {
                isInstalled -> "已安装，完整性见上（全 ✓ 才可用）"
                filesDir.isDirectory -> "目录存在但不完整 —— 重试 install()（已下载半成品会被复用）"
                else -> "未安装"
            },
        )
    }

    /** 一句话描述（供设置页 / 提示词使用）。 */
    fun describe(): String {
        val installed = installedInfo()
            ?: return "未安装（约 30 MB 下载 / 200 MB 落盘，装好后可用 apt 装 git/python/node 等）"
        return "已安装 ${installed.totalBytes / 1024 / 1024} MB · ${installed.describe}"
    }

    private fun readDescribe(): String {
        val found = listOf("bin/bash", "bin/sh", "bin/apt").filter { File(prefixDir, it).isFile }
        return if (found.isEmpty()) "未知" else "含 ${found.joinToString(", ")}"
    }

    companion object {
        const val MARKER_NAME = "bootstrap.json"
        const val INIT_SCRIPT_NAME = "agsh-init.sh"

        /** SYMLINKS.txt 的官方文件名与分隔符（U+2190 左箭头）。 */
        const val SYMLINKS_LIST_NAME = "SYMLINKS.txt"
        const val LINK_ARROW = '\u2190'

        /** 链接目标文本的最大长度：超过它必是真身（路径不会那么长）。 */
        const val MAX_LINK_TARGET_BYTES = 512

        /** 判定已安装的最小体积（真机约 200 MB，5 MB 门槛只挡残骸）。 */
        const val DEFAULT_MIN_INSTALL_BYTES: Long = 5L * 1024 * 1024

        private const val BUFFER_SIZE = 1 shl 16
        private const val SAMPLE_LIMIT = 4

        /** 顶层兼容链接清单（files 下名字 → $PREFIX 内真身）。 */
        val TOP_LEVEL_LINKS = listOf(
            "bin" to "usr/bin",
            "lib" to "usr/lib",
            "libexec" to "usr/libexec",
            "share" to "usr/share",
            "etc" to "usr/etc",
            "include" to "usr/include",
            "var" to "usr/var",
            "tmp" to "usr/tmp",
        )

        /** 符号链接解析抽查项（存在才查）。 */
        private val SYMLINK_PROBES = listOf("bin/sh", "lib/libncurses.so.6", "lib/libc.so")

        private val APT_SOURCE_PRIMARY = "https://packages.termux.dev/apt/termux-main"
        private val APT_SOURCE_MIRROR = "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"

        /** 宿主 ABI 推断（纯 JVM 无 Build.SUPPORTED_ABIS，按 os.arch 映射主流名字）。 */
        fun detectHostAbi(): String = when (
            val arch = System.getProperty("os.arch").orEmpty().lowercase()
        ) {
            "aarch64", "arm64" -> "arm64-v8a"
            "amd64", "x86_64" -> "x86_64"
            "arm" -> "armeabi-v7a"
            else -> arch
        }

        /** 体积统计：必须用 NIO walk 且**不跟随**符号链接（NOFOLLOW_LINKS）—— */
        /** File.walkTopDown 会跟随链接把 coreutils 一个真身按几十个命令名重复计入 */
        /** （yl-ai 真机 204 MB 被报成 502 MB）；而 Files.isRegularFile 不带 NOFOLLOW 时 */
        /** 对「指向文件的链接」也返回 true，仍会把目标体积重复计入 —— 这里比 yl-ai 修得更彻底。 */
        internal fun dirSize(dir: File): Long {
            if (!dir.isDirectory) return 0
            return runCatching {
                Files.walk(dir.toPath()).use { stream ->
                    stream.filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }
                        .mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }
                        .sum()
                }
            }.getOrDefault(0L)
        }

        /** 环境内引导脚本的 guest 绝对路径（宿主诊断输出用）。 */
        fun initScriptInnerPath(): String =
            "${TermuxBootstrapCatalog.GUEST_FILES}/etc/$INIT_SCRIPT_NAME"

        private fun formatProgress(received: Long, total: Long): String {
            val mb = received / 1024.0 / 1024.0
            return if (total > 0) {
                "已下载 %.1f / %.1f MB（%d%%）".format(mb, total / 1024.0 / 1024.0, received * 100 / total)
            } else {
                "已下载 %.1f MB".format(mb)
            }
        }
    }
}
