package com.androidguru.agent.shell.container

import com.androidguru.agent.shell.net.Downloader
import com.androidguru.agent.shell.process.ProcessChannelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

/**
 * Alpine Linux 容器管理 —— 一键安装 / 校验 / 修复 / 诊断。
 *
 * 保留 yl-ai 的全部关键工程决策：
 * - **镜像回退链**：清华 → 阿里云 → 南大 → 官方 CDN，逐个尝试；
 * - **版本解析**：抓取 releases 目录页，正则提取 minirootfs 文件名，数值化比较取最新；
 * - **官方 SHA256 校验**：先取 `$url.sha256` 再校验下载产物，不一致删档抛错；
 * - **解压用系统 tar 进程**（toybox/GNU tar 对 rootfs 的符号链接处理一致），
 *   解压后按 [EXPECTED_APPLET_LINKS] 清单补符号链接（tar 解包漏链的自愈）；
 * - **符号链接感知的校验**：`File.exists()` 对指向容器内绝对路径的链接会误报
 *   （宿主视角链接目标不存在），必须先 `Files.isSymbolicLink` 再判 —— yl-ai 坑 17。
 *
 * 为什么必须配 PRoot：Alpine 的 busybox 动态链接到 musl 解释器
 * `/lib/ld-musl-aarch64.so.1`，它只存在于 rootfs 内部；内核按 `PT_INTERP` 到
 * 宿主根文件系统找它必然失败（退出码 127）。只有 PRoot 能拦截 `execve` 并把
 * 解释器解析重定向进 rootfs。这是 yl-ai 的决定性对照实验结论。
 */
class AlpineContainer(
    private val baseDir: File,
    private val channelFactory: ProcessChannelFactory,
    private val downloader: Downloader,
    private val audit: com.androidguru.agent.shell.audit.AuditLog,
) {

    /** 安装信息（marker 文件的解析结果）。 */
    data class Installed(
        val version: String,
        val rootfs: File,
        val installedAt: Long,
        val totalBytes: Long,
    )

    sealed interface Progress {
        data class Stage(val text: String) : Progress
        data class Downloading(val received: Long, val total: Long, val text: String) : Progress
        data class Verifying(val text: String) : Progress
        data class Extracting(val text: String) : Progress
        data class Failed(val message: String) : Progress
        data class Done(val installed: Installed) : Progress
    }

    private val mirrors = listOf(
        "https://mirrors.tuna.tsinghua.edu.cn/alpine",
        "https://mirrors.aliyun.com/alpine",
        "https://mirror.nju.edu.cn/alpine",
        "https://dl-cdn.alpinelinux.org/alpine",
    )

    val containerRoot: File get() = File(baseDir, "linux").apply { mkdirs() }
    val rootfsDir: File get() = File(containerRoot, "alpine")
    private val markerFile: File get() = File(rootfsDir, INSTALL_MARKER)

    /** 已安装信息（无 marker 或解析失败返回 null）。 */
    fun installed(): Installed? {
        if (!markerFile.isFile) return null
        return runCatching {
            val text = markerFile.readText()
            val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: "unknown"
            val at = Regex("\"installedAt\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val bytes = Regex("\"totalBytes\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            Installed(version, rootfsDir, at, bytes)
        }.getOrNull()
    }

    /** rootfs 是否可用（busybox 存在即可判可用）。 */
    val isReady: Boolean
        get() = rootfsDir.isDirectory && File(rootfsDir, "bin/busybox").isFile

    /**
     * 安装 Alpine rootfs（多镜像回退 + 官方 SHA256 校验）。
     */
    suspend fun install(onProgress: (Progress) -> Unit = {}): Installed? = withContext(Dispatchers.IO) {
        val workDir = File(containerRoot, "tmp").apply { mkdirs() }
        var archive: File? = null
        try {
            onProgress(Progress.Stage("正在查询 Alpine 最新版本…"))
            val (version, archiveUrl) = resolveLatest()
            onProgress(Progress.Stage("找到版本 $version，开始下载…"))

            val expectSha = downloader.getText("$archiveUrl.sha256", limitBytes = 4096)
                .trim()
                .split(Regex("\\s+"))
                .firstOrNull()
                ?.lowercase()
                ?: throw IllegalStateException("无法解析官方 SHA256 校验文件")
            if (expectSha.length != 64) throw IllegalStateException("校验文件格式异常：$expectSha")

            archive = File(workDir, "alpine-$version.tar.gz")
            downloader.download(archiveUrl, archive, expectedSha256 = expectSha) { received, total ->
                val mb = received / 1024.0 / 1024.0
                val text = if (total > 0) {
                    "已下载 %.2f MB / %.2f MB（%d%%）".format(mb, total / 1024.0 / 1024.0, received * 100 / total)
                } else {
                    "已下载 %.2f MB".format(mb)
                }
                onProgress(Progress.Downloading(received, total, text))
            }

            onProgress(Progress.Verifying("正在校验完整性（SHA256）…"))
            val actualSha = Downloader.sha256(archive)
            if (!actualSha.equals(expectSha, ignoreCase = true)) {
                archive.delete()
                throw IllegalStateException(
                    "SHA256 校验失败，已丢弃下载文件。\n期望: $expectSha\n实际: $actualSha\n" +
                        "可能是下载被干扰或镜像内容变更，请重试。",
                )
            }
            onProgress(Progress.Verifying("校验通过 ✓"))

            if (rootfsDir.exists()) rootfsDir.deleteRecursively()
            rootfsDir.mkdirs()
            onProgress(Progress.Extracting("正在解压到 ${rootfsDir.absolutePath} …"))
            extractTarGz(archive)

            val totalBytes = dirSize(rootfsDir)
            val installed = Installed(version, rootfsDir, System.currentTimeMillis(), totalBytes)
            markerFile.writeText(
                """
                {
                  "distro": "alpine",
                  "version": "$version",
                  "source": "$archiveUrl",
                  "sha256": "$actualSha",
                  "installedAt": ${installed.installedAt},
                  "totalBytes": $totalBytes
                }
                """.trimIndent(),
            )
            audit.recordSetting("container.install", "alpine $version, ${totalBytes / 1024 / 1024} MB")
            archive.delete()
            onProgress(Progress.Done(installed))
            installed
        } catch (t: Throwable) {
            runCatching { archive?.delete() }
            onProgress(Progress.Failed(t.message ?: t.javaClass.simpleName))
            null
        } finally {
            runCatching { workDir.listFiles()?.forEach { it.delete() } }
        }
    }

    fun uninstall() {
        runCatching { rootfsDir.deleteRecursively() }
        audit.recordSetting("container.uninstall", "alpine")
    }

    /** 解析最新版本 → (version, 归档 URL)。 */
    private suspend fun resolveLatest(branch: String = "latest-stable"): Pair<String, String> {
        var lastError: Throwable? = null
        for (mirror in mirrors) {
            val url = "$mirror/$branch/releases/aarch64/"
            try {
                val html = downloader.getText(url, limitBytes = 512 * 1024)
                val names = Regex("""alpine-minirootfs-([0-9][0-9.]*)-aarch64\.tar\.gz"""")
                    .findAll(html)
                    .map { it.groupValues[1] }
                    .filter { it.isNotBlank() }
                    .toList()
                if (names.isEmpty()) {
                    lastError = IllegalStateException("镜像 $mirror 上没有找到 aarch64 的 rootfs")
                    continue
                }
                val latest = names.maxWithOrNull { a, b -> compareVersions(a, b) } ?: names.first()
                return latest to "$url/alpine-minirootfs-$latest-aarch64.tar.gz".replace("$url//", "$url/")
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw IllegalStateException(
            "所有镜像都无法访问，请检查网络：${lastError?.message ?: "未知原因"}",
            lastError,
        )
    }

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va - vb
        }
        return 0
    }

    /** 用系统 tar 进程解压（进程通道执行，非 Java 解压）。 */
    private suspend fun extractTarGz(archive: File) {
        val tar = listOf("/bin/tar", "/usr/bin/tar", "/system/bin/tar", "/system/xbin/tar")
            .firstOrNull { File(it).exists() }
            ?: throw IllegalStateException("系统没有 tar 命令，无法解压")

        val channel = channelFactory.open(
            program = tar,
            argv = listOf("-xzf", archive.absolutePath, "-C", rootfsDir.absolutePath),
            env = mapOf("PATH" to "/system/bin:/system/xbin:/usr/bin:/bin"),
            cwd = rootfsDir,
            rows = 30,
            cols = 200,
        )
        channel.use { p ->
            val sb = StringBuilder()
            val buf = ByteArray(4096)
            while (true) {
                val n = p.read(buf)
                if (n > 0) sb.append(String(buf, 0, n, Charsets.UTF_8))
                val code = p.waitFor(200)
                if (code != -2) {
                    if (code != 0) {
                        throw IllegalStateException("解压失败（tar 退出码 $code）：${sb.toString().trim().take(400)}")
                    }
                    break
                }
            }
        }
        // tar 解包可能漏符号链接：按清单自愈
        repairSymlinks()
    }

    /** 关键文件校验（符号链接感知）。 */
    fun verify(): List<String> {
        val root = rootfsDir
        val checks = listOf(
            "bin/busybox" to "BusyBox 主体",
            "bin/sh" to "shell",
            "sbin/apk" to "apk 包管理器",
            "etc/alpine-release" to "版本文件",
            "lib/apk/db/installed" to "已安装包数据库",
        )
        return checks.map { (rel, desc) ->
            val f = File(root, rel)
            val linkTarget = readSymlinkTarget(f)
            val present = f.exists() || linkTarget != null
            val extra = when {
                linkTarget != null -> "符号链接 → $linkTarget（在容器内有效）"
                !present -> "缺失"
                f.isDirectory -> "目录"
                else -> "${f.length()} 字节"
            }
            (if (present) "✓ " else "✗ ") + desc + "（$rel）：$extra"
        }
    }

    /**
     * 补符号链接（busybox applet 清单自愈）。
     */
    fun repairSymlinks(): List<String> {
        val root = rootfsDir
        val busybox = File(root, "bin/busybox")
        if (!busybox.isFile) return listOf("✗ bin/busybox 不存在，无法修复（请重新安装）")

        val fixed = mutableListOf<String>()
        for (rel in EXPECTED_APPLET_LINKS) {
            val f = File(root, rel)
            if (f.exists() || readSymlinkTarget(f) != null) continue
            val target = if (rel.startsWith("sbin/")) "../bin/busybox" else "busybox"
            f.parentFile?.mkdirs()
            val ok = runCatching {
                Files.createSymbolicLink(f.toPath(), java.nio.file.Path.of(target))
                true
            }.getOrElse { false }
            if (ok) fixed += rel
        }
        if (fixed.isEmpty()) return listOf("无需修复")
        audit.recordSetting("container.repair_links", fixed.joinToString(","))
        return fixed.map { "已补: $it" }
    }

    /** 诊断报告。 */
    fun diagnose(): String {
        val root = rootfsDir
        val sb = StringBuilder()
        sb.appendLine("rootfs: ${root.absolutePath}")
        sb.appendLine("总体积: ${dirSize(root) / 1024 / 1024} MB，条目数: ${root.walkTopDown().take(200_000).count()}")
        val symlinkCount = root.walkTopDown().take(50_000).count { readSymlinkTarget(it) != null }
        sb.appendLine("符号链接数量: $symlinkCount")
        val missingLinks = listOf("bin/sh", "bin/cat", "bin/ls", "bin/rm", "bin/mkdir", "bin/grep", "sbin/apk")
            .filter { rel -> !File(root, rel).exists() && readSymlinkTarget(File(root, rel)) == null }
        sb.appendLine("缺失的关键链接: " + if (missingLinks.isEmpty()) "无" else missingLinks.joinToString("、"))
        sb.appendLine("已安装包数: ${countInstalledPackages(root)}")
        return sb.toString()
    }

    private fun readSymlinkTarget(f: File): String? = runCatching {
        if (!Files.isSymbolicLink(f.toPath())) return null
        Files.readSymbolicLink(f.toPath()).toString()
    }.getOrNull()

    private fun countInstalledPackages(root: File): Int {
        val db = File(root, "lib/apk/db/installed")
        if (!db.isFile) return 0
        return runCatching {
            db.readLines().count { it.startsWith("P:") }
        }.getOrDefault(0)
    }

    private fun dirSize(dir: File): Long =
        dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val INSTALL_MARKER = ".agsh-install.json"

        /** busybox applet 符号链接自愈清单（与 Alpine minirootfs 的 bin/sbin 布局对齐）。 */
        val EXPECTED_APPLET_LINKS = listOf(
            "bin/sh", "bin/cat", "bin/ls", "bin/cp", "bin/mv", "bin/rm", "bin/mkdir", "bin/rmdir",
            "bin/ln", "bin/chmod", "bin/chown", "bin/touch", "bin/echo", "bin/pwd", "bin/ps",
            "bin/kill", "bin/sleep", "bin/date", "bin/df", "bin/mount", "bin/umount", "bin/tar",
            "bin/gzip", "bin/gunzip", "bin/grep", "bin/egrep", "bin/fgrep", "bin/sed", "bin/awk",
            "bin/find", "bin/sort", "bin/uniq", "bin/cut", "bin/tr", "bin/head", "bin/tail",
            "bin/wc", "bin/diff", "bin/stat", "bin/dd", "bin/sync", "bin/env", "bin/printenv",
            "bin/id", "bin/whoami", "bin/uname", "bin/hostname", "bin/base64", "bin/true",
            "bin/false", "bin/test", "bin/setsid", "bin/nohup", "bin/xargs", "bin/which",
            "bin/ash", "bin/ping", "bin/netstat", "bin/mktemp", "bin/readlink", "bin/realpath",
            "sbin/apk", "sbin/ifconfig", "sbin/ip", "sbin/route", "sbin/reboot", "sbin/poweroff",
            "sbin/mdev", "sbin/sysctl", "sbin/insmod", "sbin/lsmod", "sbin/modprobe",
        )
    }
}
