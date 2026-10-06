package com.androidguru.agent.shell.container

import com.androidguru.agent.shell.audit.AuditLog
import com.androidguru.agent.shell.process.ProcessChannel
import com.androidguru.agent.shell.process.ProcessChannelFactory
import com.androidguru.agent.shell.runtime.ElfRunpathPatcher
import java.io.File

/**
 * PRoot 启动器 —— 容器与 Termux 环境共用的「用户态叠宿主」入口。
 *
 * PRoot 通过 ptrace 拦截 `execve` 并把 ELF 解释器解析重定向进 rootfs，
 * 同时 `-0` 伪装 uid=0 —— 这是免 root 进入 Alpine 容器（`uid=0`、`apk` 可用）
 * 的唯一通路。
 *
 * 框架侧改动：二进制来源由宿主注入（Android 宿主把 `libproot.so` / 
 * `libproot_loader.so` 放进 jniLibs 并把路径映射进来；桌面宿主直接给可执行路径）。
 * 不就绪时 [Readiness.available] = false 并给出明确原因，绝不裸抛。
 */
class PRootLauncher(
    private val baseDir: File,
    private val channelFactory: ProcessChannelFactory,
    private val audit: AuditLog,
    /** 宿主注入：PRoot 主体（可执行）。 */
    private val prootBinary: File?,
    /** 宿主注入：PRoot loader。 */
    private val prootLoader: File?,
    /** 依赖库目录（映射 libtalloc.so.2 等）。 */
    private val libDir: File? = null,
) {

    data class Readiness(
        val available: Boolean,
        val reason: String,
        val prootPath: String? = null,
        val loaderPath: String? = null,
    )

    /** 就绪检查（fail-fast + 明确原因）。 */
    fun prepare(): Readiness {
        val proot = prootBinary
        val loader = prootLoader
        if (proot == null || !proot.isFile) {
            return Readiness(false, "缺少 PRoot 可执行文件（宿主未注入 libproot.so 或对应二进制）")
        }
        if (loader == null || !loader.isFile) {
            return Readiness(false, "缺少 PRoot loader；没有它 PRoot 无法注入目标进程")
        }
        if (!proot.canExecute()) {
            return Readiness(false, "PRoot 文件不可执行（检查挂载选项 / SELinux / 文件权限）")
        }
        val runpath = ElfRunpathPatcher.readRunpath(proot)
        if (!runpath.isNullOrEmpty()) {
            // RUNPATH 会覆盖 LD_LIBRARY_PATH（yl-ai 坑 21）；宿主可提前用 ElfRunpathPatcher.clear 处理
            audit.recordSetting("proot.runpath_warning", "RUNPATH=$runpath")
        }
        return Readiness(true, "容器运行环境就绪", proot.absolutePath, loader.absolutePath)
    }

    /**
     * 组装 PRoot 参数（Alpine 容器场景）。
     *
     * 关键映射：`<home>:/root` —— 容器 /root 指向宿主可写目录，
     * 容器内写的文件在普通会话里也能看到（文件互通设计）。
     */
    fun buildProotArgs(
        rootfs: File,
        workDir: String,
        home: File,
        shared: File?,
    ): List<String> {
        val args = mutableListOf<String>()
        args += "-r"; args += rootfs.absolutePath
        args += "-0"
        args += "-w"; args += workDir
        args += "-b"; args += "/dev"
        args += "-b"; args += "/proc"
        args += "-b"; args += "/sys"
        if (File("/system").isDirectory) { args += "-b"; args += "/system" }
        if (File("/apex").isDirectory) { args += "-b"; args += "/apex" }

        args += "-b"; args += "${home.absolutePath}:/root"
        if (shared != null && shared.isDirectory) {
            args += "-b"; args += "${shared.absolutePath}:/sdcard"
        }

        val tz = listOf("/system/usr/share/zoneinfo", "/usr/share/zoneinfo")
            .map(::File).firstOrNull { it.isDirectory }
        if (tz != null) { args += "-b"; args += "${tz.absolutePath}:/usr/share/zoneinfo" }
        return args
    }

    /** 组装容器启动环境变量。 */
    fun buildEnv(loader: File, libPaths: List<File>): Map<String, String> {
        val tmpDir = File(baseDir, "proot-tmp").apply { mkdirs() }
        return linkedMapOf(
            "PROOT_LOADER" to loader.absolutePath,
            "PROOT_TMP_DIR" to tmpDir.absolutePath,
            "LD_LIBRARY_PATH" to libPaths.filter { it.isDirectory }.joinToString(":") { it.absolutePath },
            "HOME" to "/root",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "PROOT_NO_SECCOMP" to "0",
        )
    }

    /**
     * 启动容器会话通道。
     *
     * @throws IllegalStateException 未就绪时
     */
    fun launch(
        rootfs: File,
        command: List<String> = listOf("/bin/sh"),
        home: File,
        shared: File? = null,
        workDir: String = "/root",
        rows: Int = 30,
        cols: Int = 100,
    ): ProcessChannel {
        val ready = prepare()
        if (!ready.available) throw IllegalStateException(ready.reason)

        val proot = File(ready.prootPath!!)
        val loader = File(ready.loaderPath!!)
        val libPaths = listOfNotNull(libDir, loader.parentFile)

        val args = buildProotArgs(rootfs, workDir, home, shared) + command
        val env = buildEnv(loader, libPaths)

        audit.recordSetting("container.launch", "rootfs=${rootfs.name} cmd=${command.joinToString(" ")}")

        return channelFactory.open(
            program = proot.absolutePath,
            argv = args,
            env = env,
            cwd = libPaths.firstOrNull() ?: home,
            rows = rows,
            cols = cols,
        )
    }

    /** 供诊断 / 日志复现的完整启动命令描述。 */
    fun describeLaunchCommand(rootfs: File, home: File): String {
        val ready = prepare()
        if (!ready.available) return "# 容器不可用：${ready.reason}"
        val tmpDir = File(baseDir, "proot-tmp").absolutePath
        val args = buildProotArgs(rootfs, "/root", home, null)
        val libPath = listOfNotNull(libDir, prootLoader?.parentFile)
            .joinToString(":") { it.absolutePath }
        return buildString {
            appendLine("PROOT_LOADER=${ready.loaderPath} \\")
            appendLine("PROOT_TMP_DIR=$tmpDir \\")
            appendLine("LD_LIBRARY_PATH=$libPath \\")
            append("${ready.prootPath} ${args.joinToString(" ")} /bin/sh")
        }
    }

    /** `proot --version` 探测（带故障排查指引）。 */
    fun probeProot(home: File): String {
        val ready = prepare()
        val sb = StringBuilder()
        sb.appendLine("容器运行环境：")
        sb.appendLine("  就绪: ${if (ready.available) "是" else "否 —— ${ready.reason}"}")
        ready.prootPath?.let { p ->
            sb.appendLine("  proot: $p")
            sb.appendLine("  当前 RUNPATH: ${ElfRunpathPatcher.readRunpath(File(p)) ?: "(无)"}")
        }
        if (!ready.available) return sb.toString()

        sb.appendLine()
        sb.appendLine("执行 proot --version：")
        return runCatching {
            val proot = File(ready.prootPath!!)
            val loader = File(ready.loaderPath!!)
            val env = buildEnv(loader, listOfNotNull(libDir, loader.parentFile))
            val channel = channelFactory.open(
                program = proot.absolutePath,
                argv = listOf("--version"),
                env = env,
                cwd = home,
                rows = 24,
                cols = 100,
            )
            channel.use { p ->
                val out = StringBuilder()
                val buf = ByteArray(4096)
                val deadline = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < deadline) {
                    val n = p.read(buf)
                    if (n > 0) out.append(String(buf, 0, n, Charsets.UTF_8))
                    val code = p.waitFor(150)
                    if (code != -2) {
                        val text = out.toString().trim()
                        sb.appendLine(if (code == 0) "  ✓ 启动成功（退出码 0）" else "  ✗ 退出码 $code")
                        sb.appendLine(text.lines().take(6).joinToString("\n") { "  $it" })
                        return sb.toString()
                    }
                }
                sb.appendLine("  探测超时")
                sb.toString()
            }
        }.getOrElse { t ->
            sb.appendLine("  ✗ 启动失败：${t.javaClass.simpleName}: ${t.message}")
            sb.appendLine()
            sb.appendLine("  常见原因：")
            sb.appendLine("   · 依赖库缺失或 RUNPATH 未清空（用 ElfRunpathPatcher.clear 处理）")
            sb.appendLine("   · 内核/ROM 限制了 ptrace（容器功能不可用，其余功能不受影响）")
            sb.toString()
        }
    }
}
