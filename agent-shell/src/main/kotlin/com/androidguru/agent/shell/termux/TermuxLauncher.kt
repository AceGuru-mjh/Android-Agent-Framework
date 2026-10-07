package com.androidguru.agent.shell.termux

import com.androidguru.agent.shell.audit.AuditLog
import com.androidguru.agent.shell.process.ProcessChannel
import com.androidguru.agent.shell.process.ProcessChannelFactory
import com.androidguru.agent.shell.terminal.AnsiStripper
import java.io.File

/**
 * Termux 环境启动器（纯 JVM 版）—— 用 PRoot 做一次"自我映射"后进入 Termux 用户态。
 *
 * 【为什么需要 PRoot】（yl-ai DEVLOG 8.2）
 * Termux 的二进制把 `/data/data/com.termux/files/usr` 硬编码进了 ELF（PT_INTERP、
 * RPATH、脚本 shebang 全是这个路径）。本框架的沙箱目录是别的路径，内核按 PT_INTERP
 * 找解释器必然失败（Alpine 上实测退出码 127）。解法只有一条 bind：
 *
 * ```
 * proot -r / -b <termux 根目录>:/data/data/com.termux/files ...
 * ```
 *
 * PRoot 在 syscall 层翻译路径，硬编码路径**不需要任何改写**就成立了。
 * 用 `-r /` 而不是某个 rootfs 也有理由：Termux 用户态本来就叠在安卓之上（依赖
 * /system 里的 liblog、bionic linker），必须看得见宿主；且少一层整体翻译少一层损耗。
 *
 * 与 yl-ai 原实现的差异：
 * - **去 Context 化**：环境（[environment]）、PRoot 二进制与 loader 全部构造注入，
 *   零 Android 依赖；宿主接线方式与 [com.androidguru.agent.shell.container.PRootLauncher] 一致；
 * - **通道 SPI 化**：返回 [ProcessChannel] 而非 PtyProcess —— 纯 JVM 宿主用管道通道，
 *   Android 宿主注入真 PTY 通道后交互语义（Ctrl-C / vim / top）不变。
 */
class TermuxLauncher(
    /** 已安装的 Termux 环境（[TermuxEnvironment.isInstalled] 为前提，prepare 里把关）。 */
    private val environment: TermuxEnvironment,
    /** 通道工厂：进程 I/O 的唯一出口（管道 / PTY 由宿主决定）。 */
    private val channelFactory: ProcessChannelFactory,
    /** 审计日志；null 表示宿主不接审计。 */
    private val audit: AuditLog? = null,
    /** 宿主注入：PRoot 主体可执行文件（Android 宿主把 libproot.so 放 jniLibs 后映射路径）。 */
    private val prootBinary: File?,
    /** 宿主注入：PRoot loader（proot 靠它注入目标进程）。 */
    private val prootLoader: File? = null,
    /** PRoot 依赖库目录（libtalloc.so.2 等；loader 所在目录会自动并入）。 */
    private val libDir: File? = null,
) {

    /** 就绪性结论。不就绪时 [reason] 给出可执行的修复方向，绝不裸抛到调用方面前。 */
    data class Readiness(
        val available: Boolean,
        val reason: String,
    )

    /** 就绪检查：环境已装 + PRoot 二件套在位且可执行。 */
    fun prepare(): Readiness {
        if (!environment.isInstalled) {
            return Readiness(false, "Termux 环境尚未安装。请先执行 TermuxEnvironment.install()（约 30 MB 下载）。")
        }
        val proot = prootBinary
        if (proot == null || !proot.isFile) {
            return Readiness(false, "缺少 PRoot 可执行文件（宿主未注入 libproot.so 或对应二进制）。" +
                "Termux 环境需要 PRoot 解析硬编码的 \$PREFIX 路径")
        }
        if (prootLoader == null || !prootLoader.isFile) {
            return Readiness(false, "缺少 PRoot loader；没有它 PRoot 无法注入目标进程")
        }
        if (!proot.canExecute()) {
            return Readiness(false, "PRoot 文件不可执行（检查挂载选项 / SELinux / 文件权限）")
        }
        return Readiness(true, "就绪")
    }

    /**
     * 组装 PRoot 参数（Termux 自映射方案）。
     *
     * 参数顺序与 yl-ai 真机验证过的启动线一致：
     * `-r /` + termux 自映射 + 系统 /dev /proc /sys /system 绑定 + zoneinfo / sdcard
     * 条件绑定 + `-w $GUEST_HOME` + guest 命令。
     *
     * @param command guest 内要执行的命令（默认调用方给 `$PREFIX/bin/login` 进登录 shell）
     */
    fun buildProotArgs(command: List<String>): List<String> {
        val args = mutableListOf<String>()
        args += "-r"; args += "/"
        // 自映射：宿主 termux 根目录 → guest 硬编码的 $GUEST_FILES（整个方案的核心一行）
        args += "-b"; args += "${environment.filesDir.absolutePath}:${TermuxBootstrapCatalog.GUEST_FILES}"

        // Termux 用户态叠在安卓上：bionic linker、liblog、zoneinfo 都来自宿主系统分区
        args += "-b"; args += "/dev"
        args += "-b"; args += "/proc"
        args += "-b"; args += "/sys"
        // 以下分区仅 Android 宿主存在（桌面 JVM 没有），存在才绑定 —— 参数构造在两端宿主都可复现
        for (sysDir in listOf("/system", "/apex", "/vendor")) {
            if (File(sysDir).isDirectory) { args += "-b"; args += sysDir }
        }

        // 时区数据：Termux bootstrap 不带 zoneinfo，借宿主的
        val tz = listOf("/system/usr/share/zoneinfo", "/usr/share/zoneinfo")
            .map(::File).firstOrNull { it.isDirectory }
        if (tz != null) {
            args += "-b"; args += "${tz.absolutePath}:${TermuxBootstrapCatalog.GUEST_PREFIX}/share/zoneinfo"
        }

        // 存储互通：/sdcard 挂进环境内约定位置（Android 权限模型下需宿主已授权）
        if (File("/sdcard").isDirectory) {
            args += "-b"; args += "/sdcard:${TermuxBootstrapCatalog.GUEST_FILES}/storage/shared"
        }

        args += "-w"; args += TermuxBootstrapCatalog.GUEST_HOME
        args += command
        return args
    }

    /**
     * 登录环境变量（guest 内应见到的值）。
     *
     * 【刻意不含 LD_LIBRARY_PATH】（yl-ai DEVLOG 8.3 的"首现优先"坑）
     * 绝不能把 LD_LIBRARY_PATH 指向 Termux 的 lib 就完事：
     * - Android 宿主的原生 PTY 层组装 envp 时以宿主 environ 为**基底再追加**，
     *   同名变量首现优先 → 追加值可能被静默忽略（等于没设）；
     * - 错误的 LD_LIBRARY_PATH 泄漏进环境内部，Termux 二进制会去加载宿主的库 —— 直接崩。
     * 环境内的库解析交给 Termux 二进制自身的 RPATH + 环境内引导脚本
     * （agsh-init.sh 在 guest 里重新 export LD_LIBRARY_PATH=$PREFIX/lib）。
     * 本函数返回的变量集就是"login env 的白名单"，宿主组装自己的 envp 时应以它为准。
     */
    fun buildLoginEnv(): Map<String, String> = linkedMapOf(
        "TERM" to "xterm-256color",
        "HOME" to TermuxBootstrapCatalog.GUEST_HOME,
        "PREFIX" to TermuxBootstrapCatalog.GUEST_PREFIX,
        "PATH" to "${TermuxBootstrapCatalog.GUEST_PREFIX}/bin:${TermuxBootstrapCatalog.GUEST_PREFIX}/bin/applets",
        "TMPDIR" to "${TermuxBootstrapCatalog.GUEST_PREFIX}/tmp",
        "LANG" to "C.UTF-8",
    )

    /**
     * PRoot 进程的 envp：PROOT_* 控制变量 + [buildLoginEnv] 的登录变量。
     *
     * LD_LIBRARY_PATH 出现在这份 map 里**只是为了让 proot 自己加载 libtalloc.so.2**
     * （指向宿主库目录）；guest 内不依赖它 —— 环境内的 agsh-init.sh 会重新 export
     * 正确值。这就是 8.3 的结论："LD_LIBRARY_PATH 只给 proot 用，不进 login env 语义"。
     */
    fun buildProotEnv(loader: File): Map<String, String> {
        val tmpDir = prootTmpDir()
        val hostLibs = listOfNotNull(libDir, loader.parentFile)
            .filter { it.isDirectory }
            .joinToString(":") { it.absolutePath }
        return buildMap {
            put("PROOT_LOADER", loader.absolutePath)
            put("PROOT_TMP_DIR", tmpDir.absolutePath)
            if (hostLibs.isNotEmpty()) put("LD_LIBRARY_PATH", hostLibs)
            put("PROOT_NO_SECCOMP", "0") // 部分 ROM 的 seccomp 与 proot 冲突，框架统一关（与 PRootLauncher 对齐）
            putAll(buildLoginEnv())
        }
    }

    private fun prootTmpDir(): File = File(environment.filesDir, "proot-tmp").apply { mkdirs() }

    /**
     * 启动登录 shell 通道（默认 `$PREFIX/bin/login`，由环境内 profile 完成初始化）。
     *
     * @param command 自定义 guest 命令（空 = 登录 shell）；一次性命令用 [runShellCommand]
     * @throws IllegalStateException 未就绪时（原因见 [prepare]）
     */
    fun launchLoginShell(
        command: List<String> = emptyList(),
        rows: Int = 30,
        cols: Int = 100,
    ): ProcessChannel {
        val ready = prepare()
        if (!ready.available) throw IllegalStateException(ready.reason)

        val proot = prootBinary!!
        val guestCommand = command.ifEmpty { listOf("${TermuxBootstrapCatalog.GUEST_PREFIX}/bin/login") }
        val args = buildProotArgs(guestCommand)
        val env = buildProotEnv(prootLoader!!)

        audit?.recordSetting(
            "termux.launch",
            "cmd=${guestCommand.joinToString(" ")} prefix=${environment.prefixDir.absolutePath}",
        )
        // 记录可复现命令行：proot 启动类失败只看退出码无从下手，
        // 把这行粘进 adb shell 逐项二分排查（yl-ai DEVLOG 8.8 的定位方法）
        lastLaunchCommand = buildString {
            append("PROOT_LOADER=${prootLoader.absolutePath} ")
            append("PROOT_TMP_DIR=${prootTmpDir().absolutePath} ")
            append("LD_LIBRARY_PATH=${env["LD_LIBRARY_PATH"] ?: "(空)"} ")
            append(proot.absolutePath)
            append(" ")
            append(args.joinToString(" "))
        }

        return channelFactory.open(
            program = proot.absolutePath,
            argv = args,
            env = env,
            cwd = prootTmpDir(),
            rows = rows,
            cols = cols,
        )
    }

    /** 最近一次启动的完整命令行（envp 前缀 + proot + 全部参数）；null = 尚未启动过。 */
    var lastLaunchCommand: String? = null
        private set

    /** 可复现启动命令行的可读版（诊断页直接展示）。 */
    fun describeLastLaunch(): String = lastLaunchCommand ?: "(尚未启动过)"

    /**
     * 一次性执行环境内命令并取输出（`sh -c <cmd>` 语义，与容器 exec 的通道读取循环同构）。
     *
     * 读取循环要点：先 read 后 waitFor —— 管道通道 waitFor 返回退出码时缓冲里可能
     * 还有数据，观察到退出码后再做一次"排干读"再收尾，避免截尾（yl-ai 的 drainPty
     * 在 PTY 上靠 350ms 静默窗口解决同一问题，管道通道用确定性排干替代）。
     *
     * @return 可读结果文本（命令 / 退出码 / 输出），失败原因也在文本里，不抛给调用方
     */
    fun runShellCommand(
        shellCommand: String,
        timeoutMs: Long = 30_000,
        maxOutput: Int = 8_000,
        rawProgram: String = "${TermuxBootstrapCatalog.GUEST_PREFIX}/bin/sh",
    ): String {
        val ready = prepare()
        if (!ready.available) return "[环境不可用] ${ready.reason}"

        val channel = try {
            launchLoginShell(command = listOf(rawProgram, "-c", shellCommand), rows = 40, cols = 200)
        } catch (t: Throwable) {
            return "[启动失败] ${t.message}"
        }

        val (exitLabel, text) = channel.use { p -> drainChannel(p, timeoutMs) }
        val truncated = if (text.length > maxOutput) text.takeLast(maxOutput) else text
        return buildString {
            appendLine("命令: $shellCommand")
            appendLine("退出码: $exitLabel（${text.length} 字符输出）")
            appendLine("输出:")
            append(truncated.ifBlank { "(无输出)" })
        }
    }

    /** 通道排水：读到退出码后继续把缓冲排干（见 [runShellCommand] 的说明）。返回 (退出码文本, 清洗后输出)。 */
    private fun drainChannel(channel: ProcessChannel, timeoutMs: Long): Pair<String, String> {
        val out = StringBuilder()
        val buf = ByteArray(8192)
        var total = 0L
        var exitCode: Int? = null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val n = channel.read(buf)
            if (n > 0) {
                out.append(String(buf, 0, n, Charsets.UTF_8))
                total += n
                if (total > MAX_COLLECT_BYTES) break // 硬上限： runaway 输出不拖垮宿主
                continue
            }
            if (exitCode == null) {
                val code = channel.waitFor(150)
                if (code != -2) exitCode = code
            } else {
                break // 退出码已定且缓冲已排干
            }
        }
        return (exitCode?.toString() ?: "超时") to AnsiStripper.clean(out.toString()).trim()
    }

    /**
     * 六级逐级诊断（沿真实启动路径，第一个 ✗ 就是故障点）：
     * 1. PRoot 二进制存在 → 2. proot 能跑 → 3. rootfs（环境内容）完整 →
     * 4. bash 存在 → 5. bash 可执行 → 6. 命令往返。
     *
     * 与 yl-ai 的差异：原版六级全靠"进 proot 跑命令"观察输出；框架版把
     * "文件层面的检查"（1/3/4/5）拆出来静态判定 —— 不进 ptrace 就能定位的
     * 问题没必要付出 proot 的不确定性，只在 2/6 两级真正启动通道。
     */
    fun diagnoseLadder(): List<String> {
        val ladder = mutableListOf<String>()

        // L1：PRoot 二进制存在（文件层面，最快的一刀）
        val proot = prootBinary
        val loader = prootLoader
        if (proot == null || !proot.isFile) {
            ladder += "✗ L1 PRoot 二进制缺失（宿主未注入 libproot.so 或对应二进制）—— 到此为止"
            return ladder
        }
        if (loader == null || !loader.isFile) {
            ladder += "✗ L1 PRoot loader 缺失 —— 到此为止"
            return ladder
        }
        if (!proot.canExecute()) {
            ladder += "✗ L1 PRoot 不可执行（挂载选项 / SELinux / 权限）—— 到此为止"
            return ladder
        }
        ladder += "✓ L1 PRoot 二进制在位：${proot.absolutePath}"

        // L2：proot 能跑（不挂 rootfs 跑 --version，把 proot 自身问题与环境内容问题分开）
        val prootRuns = runCatching {
            channelFactory.open(
                program = proot.absolutePath,
                argv = listOf("--version"),
                env = buildProotEnv(loader),
                cwd = prootTmpDir(),
                rows = 24, cols = 100,
            ).use { p -> drainChannel(p, PROBE_TIMEOUT_MS).second.isNotBlank() || p.waitFor(1000) == 0 }
        }.getOrElse { false }
        if (!prootRuns) {
            ladder += "✗ L2 proot 无法运行（--version 无输出/非零退出）—— 常见原因：ROM 禁止 ptrace、" +
                "libtalloc 缺失（LD_LIBRARY_PATH / RUNPATH 问题，用 ElfRunpathPatcher.clear 处理）；" +
                "复现命令行：${describeLastLaunch()}"
            return ladder
        }
        ladder += "✓ L2 proot 可运行"

        // L3：环境内容完整（marker + 关键文件 + 链接解析，纯文件检查）
        val verify = environment.verify()
        val bad = verify.filter { it.startsWith("✗") }
        if (bad.isNotEmpty() || !environment.isInstalled) {
            ladder += "✗ L3 环境内容不完整（${bad.size} 项不过）：${bad.take(3).joinToString("；")} —— 建议重装"
            return ladder
        }
        ladder += "✓ L3 环境内容完整（verify ${verify.size} 项全过）"

        // L4：bash 存在
        val bash = File(environment.prefixDir, "bin/bash")
        if (!bash.isFile) {
            ladder += "✗ L4 bin/bash 不存在 —— bootstrap 解压不完整，建议重装"
            return ladder
        }
        ladder += "✓ L4 bash 存在：${bash.absolutePath}"

        // L5：bash 可执行（无执行位是 zip 解压环节最典型的坏法）
        if (!bash.canExecute()) {
            ladder += "✗ L5 bash 无执行位 —— 解压环节问题，建议重装（或 chmod +x 修复）"
            return ladder
        }
        ladder += "✓ L5 bash 可执行"

        // L6：命令往返（真跑一条命令并核对输出，全链路收口）。
        // 必须核对**通道输出**而不是 runShellCommand 的格式化文本 —— 后者的
        // "命令:" 行里就带着标记，命令根本没跑起来也会被误判成成功。
        val roundTrip = runCatching {
            launchLoginShell(
                command = listOf(
                    "${TermuxBootstrapCatalog.GUEST_PREFIX}/bin/sh",
                    "-c",
                    "printf $L6_MARKER",
                ),
                rows = 40, cols = 200,
            ).use { drainChannel(it, PROBE_TIMEOUT_MS).second }
        }.getOrElse { "抛异常：${it.javaClass.simpleName}: ${it.message}" }
        if (L6_MARKER !in roundTrip) {
            ladder += "✗ L6 命令往返失败：$roundTrip —— 复现命令行：${describeLastLaunch()}"
            return ladder
        }
        ladder += "✓ L6 命令往返成功（echo 往返核对通过）"
        return ladder
    }

    /**
     * 冒烟自检：就绪性 + 环境内一条命令的实测。
     * 任何异常都转成可读报告（含复现命令行与常见原因），绝不把异常抛给设置页。
     */
    fun smokeTest(): String = runCatching { smokeTestInner() }.getOrElse { t ->
        buildString {
            appendLine("Termux 自检抛异常：${t.javaClass.simpleName}: ${t.message}")
            appendLine()
            appendLine("最近一次启动命令行（可直接粘进 adb shell 复现）：")
            appendLine(describeLastLaunch())
            appendLine()
            appendLine("常见原因：")
            appendLine(" · PRoot 在本机被限制（部分厂商 ROM 禁止 ptrace）")
            appendLine(" · 绑定路径不存在或不可读")
            appendLine(" · Termux 二进制缺少可执行位（解压环节问题）")
        }
    }

    private fun smokeTestInner(): String {
        val ready = prepare()
        val installed = environment.installedInfo()
        val head = buildString {
            appendLine("Termux 环境就绪性：")
            appendLine("  状态: ${if (ready.available) "可用" else "不可用 —— ${ready.reason}"}")
            appendLine("  \$PREFIX(宿主): ${environment.prefixDir.absolutePath}")
            appendLine("  映射到环境内: ${TermuxBootstrapCatalog.GUEST_PREFIX}")
            appendLine("  体积: ${(installed?.totalBytes ?: 0L) / 1024 / 1024} MB")
            appendLine("  复现命令行: ${describeLastLaunch()}")
        }
        // 未就绪就把就绪性报告带上一条环境内诊断建议后收尾，不去启动通道
        if (!ready.available) return head + "\n环境不可用，跳过环境内实测。\n"

        val probe = runCatching {
            runShellCommand(
                "uname -m; echo PREFIX=\$PREFIX; for c in apt dpkg bash git python3; do " +
                    "printf '%s: ' \$c; command -v \$c || echo '(未安装)'; done",
                timeoutMs = PROBE_TIMEOUT_MS,
            )
        }.getOrElse { "实测抛异常：${it.javaClass.simpleName}: ${it.message}" }
        return head + "\n环境内实测（uname + 工具清点）：\n" + probe
    }

    companion object {
        /** 探针类命令的超时（诊断用途，比正式命令短）。 */
        const val PROBE_TIMEOUT_MS = 20_000L

        /** 单次命令收集输出的硬上限（字节）。 */
        const val MAX_COLLECT_BYTES: Long = 120_000L * 4

        /** L6 往返标记。 */
        const val L6_MARKER = "AGSH_L6_ROUNDTRIP_OK"
    }
}
