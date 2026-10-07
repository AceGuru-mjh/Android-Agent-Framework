package com.androidguru.agent.shell

import com.androidguru.agent.shell.audit.AuditLog
import com.androidguru.agent.shell.audit.FileAuditLog
import com.androidguru.agent.shell.container.AlpineContainer
import com.androidguru.agent.shell.container.PRootLauncher
import com.androidguru.agent.shell.control.LocalControlServer
import com.androidguru.agent.shell.job.ShellJob
import com.androidguru.agent.shell.net.Downloader
import com.androidguru.agent.shell.policy.ApprovalGate
import com.androidguru.agent.shell.policy.CommandPolicy
import com.androidguru.agent.shell.process.ProcessChannelFactory
import com.androidguru.agent.shell.process.JvmProcessChannelFactory
import com.androidguru.agent.shell.runtime.EnvironmentProbe
import com.androidguru.agent.shell.runtime.SelfCheck
import com.androidguru.agent.shell.runtime.ShellEnvironment
import com.androidguru.agent.shell.session.CommandRunner
import com.androidguru.agent.shell.session.SessionCheckpoint
import com.androidguru.agent.shell.session.ShellSession
import com.androidguru.agent.shell.session.ShellSessionManager
import com.androidguru.agent.shell.termux.TermuxEnvironment
import com.androidguru.agent.shell.termux.TermuxLauncher
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 每会话一个 [CommandRunner] 的共享缓存（修复 issue #18）。
 *
 * ShellRuntime 与 LocalControlServer 必须共用同一份缓存 —— 若各自建缓存，
 * 同一会话会有两个 runner、两把互不相干的 Mutex，agent 工具与控制 API
 * 并发 exec 时哨兵输出互相污染。
 */
internal object CommandRunnerCache {
    private val runners = ConcurrentHashMap<String, CommandRunner>()

    fun runnerOf(session: ShellSession): CommandRunner =
        runners.computeIfAbsent(session.id) { CommandRunner(session) }
}

/**
 * Shell 运行时 —— 终端能力的组装根。
 *
 * 一个 [ShellRuntime] 聚合全部终端子系统，[agent-shell-tools 的 ShellToolSet]
 * 在它之上构造框架工具集。宿主按需组装或全部使用默认值：
 *
 * ```kotlin
 * val runtime = ShellRuntime.create(baseDir = File("data/shell"))
 * ```
 *
 * Android 宿主的差异全部通过两个参数注入：
 * - [channelFactory] → 基于 forkpty 的真 PTY 通道工厂（交互语义完整）；
 * - [environment] 的 extraPathDirs → nativeLibraryDir（唯一可 execve 的位置）。
 */
class ShellRuntime(
    /** 状态落盘根目录。 */
    val baseDir: File,
    val environment: ShellEnvironment,
    val channelFactory: ProcessChannelFactory,
    val policy: CommandPolicy = CommandPolicy,
    val approval: ApprovalGate = ApprovalGate(),
    val audit: AuditLog,
    val downloader: Downloader,
    val probe: EnvironmentProbe,
    val sessions: ShellSessionManager,
    val jobs: ShellJob.ShellJobStore,
    val container: AlpineContainer,
    val proot: PRootLauncher,
    val checkpoint: SessionCheckpoint,
    val controlServer: LocalControlServer,
) {

    /**
     * Termux 环境（惰性构建：宿主不用 Termux 时零开销）。
     *
     * 补齐 yl-ai 里程碑 2 能力（缺口分析 A 组）：bootstrap 安装（多镜像 + SHA256）、
     * ZipEntry 内容判别符号链接、SYMLINKS.txt 恢复、apt 双源、引导脚本、完整性校验。
     */
    val termux: TermuxEnvironment by lazy {
        TermuxEnvironment(baseDir = baseDir, downloader = downloader, audit = audit)
    }

    /**
     * Termux 启动器（复用与容器相同的 PRoot 二件套解析约定：AGSH_PROOT /
     * AGSH_PROOT_LOADER / AGSH_PROOT_LIB 环境变量；Android 宿主直接构造
     * [ShellRuntime] 时可自行创建 [TermuxLauncher] 并用 createTermuxSession 的
     * 通道注入方式接入）。
     */
    val termuxLauncher: TermuxLauncher by lazy {
        TermuxLauncher(
            environment = termux,
            channelFactory = channelFactory,
            audit = audit,
            prootBinary = System.getenv("AGSH_PROOT")?.let(::File),
            prootLoader = System.getenv("AGSH_PROOT_LOADER")?.let(::File),
            libDir = System.getenv("AGSH_PROOT_LIB")?.let(::File),
        )
    }

    /** 每会话一个 [CommandRunner]（共享缓存，与控制 API 一致）。 */
    fun runnerOf(session: ShellSession): CommandRunner = CommandRunnerCache.runnerOf(session)

    /** 后台作业工厂。 */
    fun startJob(command: String, workdir: String? = null): ShellJob =
        ShellJob.start(
            id = jobs.newId(),
            command = command,
            workdir = workdir,
            environment = environment,
            channelFactory = channelFactory,
            store = jobs,
        )

    /** 在 Alpine 容器里新建会话（未安装时返回 null 并推送错误横幅式消息由宿主处理）。 */
    fun createContainerSession(command: List<String> = listOf("/bin/sh")): ShellSession? {
        if (!container.isReady) return null
        return openSession(
            id = java.util.UUID.randomUUID().toString().replace("-", "").take(8),
            title = "容器 · alpine",
            cwd = "/root",
            replayBanner =
                "[已进入容器] Alpine · rootfs=${container.rootfsDir.absolutePath}\r\n" +
                    "提示：容器内文件系统独立；/root 已映射到宿主可写目录，互通。\r\n",
            auditTag = "container.session",
            auditDetail = "alpine cmd=${command.joinToString(" ")}",
        ) {
            proot.launch(
                rootfs = container.rootfsDir,
                command = command,
                home = environment.home,
            )
        }
    }

    /**
     * 在 Termux 环境里新建会话（补齐 yl-ai 里程碑 2 的「Termux 执行链路」能力）。
     *
     * 未就绪（未安装 / PRoot 二件套缺失）时返回 null，宿主可用
     * `termuxLauncher.prepare().reason` 向用户展示修复方向。
     *
     * @param command 环境内启动命令；空 = 官方 login（登录 shell + 引导脚本）
     */
    fun createTermuxSession(command: List<String> = emptyList()): ShellSession? {
        val ready = termuxLauncher.prepare()
        if (!ready.available) {
            audit.recordSetting("termux.session_refused", ready.reason)
            return null
        }
        return openSession(
            id = java.util.UUID.randomUUID().toString().replace("-", "").take(8),
            title = "Termux 环境",
            cwd = termux.homeDir.absolutePath,
            replayBanner =
                "[已进入 Termux 环境] prefix=${termux.prefixDir.absolutePath}\r\n" +
                    "提示：apt / bash 可用；路径与官方 Termux 一致（proot 自映射）。\r\n",
            auditTag = "termux.session",
            auditDetail = "cmd=${command.joinToString(" ")}",
        ) {
            termuxLauncher.launchLoginShell(command)
        }
    }

    /** 会话打开的公共骨架：建会话 → 开通道 → cwd → 横幅 → 注册 → 审计；失败返回 null。 */
    private fun openSession(
        id: String,
        title: String,
        cwd: String,
        replayBanner: String,
        auditTag: String,
        auditDetail: String,
        open: () -> com.androidguru.agent.shell.process.ProcessChannel,
    ): ShellSession? {
        val session = ShellSession(
            id = id,
            title = title,
            environment = environment,
            channelFactory = channelFactory,
        )
        return try {
            val channel = open()
            session.attachChannel(channel)
            session.setCurrentDirectory(cwd)
            session.pushReplay(replayBanner)
            sessions.registerExternal(session)
            audit.recordSetting(auditTag, auditDetail)
            session
        } catch (t: Throwable) {
            audit.recordSetting("${auditTag}_failed", "${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** 自检。 */
    fun selfCheck(runEndToEnd: Boolean = true): String = SelfCheck(
        environment = environment,
        channelFactory = channelFactory,
        probe = probe,
    ).run(runEndToEnd)

    companion object {

        /**
         * 用默认实现组装运行时。
         *
         * @param baseDir 状态根目录（会话检查点 / 审计 / 作业日志 / 容器 rootfs 都在其下）
         * @param channelFactory 通道工厂（默认 [JvmProcessChannelFactory]）
         * @param extraPathDirs 额外 PATH 目录（Android 宿主传 nativeLibraryDir）
         */
        fun create(
            baseDir: File,
            channelFactory: ProcessChannelFactory = JvmProcessChannelFactory(),
            extraPathDirs: List<File> = emptyList(),
            audit: AuditLog = FileAuditLog(File(baseDir, "audit")),
            tokenStore: File = File(baseDir, "control/api-token"),
        ): ShellRuntime {
            baseDir.mkdirs()
            val environment = ShellEnvironment(
                prefix = File(baseDir, "usr"),
                home = File(baseDir, "home"),
                tmp = File(baseDir, "tmp"),
                extraPathDirs = extraPathDirs,
            )
            val sessions = ShellSessionManager(environment, channelFactory)
            val jobs = ShellJob.ShellJobStore(File(baseDir, "jobs"))
            jobs.init()
            val approval = ApprovalGate()
            val proot = PRootLauncher(
                baseDir = baseDir,
                channelFactory = channelFactory,
                audit = audit,
                prootBinary = System.getenv("AGSH_PROOT")?.let(::File),
                prootLoader = System.getenv("AGSH_PROOT_LOADER")?.let(::File),
                libDir = System.getenv("AGSH_PROOT_LIB")?.let(::File),
            )
            return ShellRuntime(
                baseDir = baseDir,
                environment = environment,
                channelFactory = channelFactory,
                approval = approval,
                audit = audit,
                downloader = Downloader(),
                probe = EnvironmentProbe(environment),
                sessions = sessions,
                jobs = jobs,
                container = AlpineContainer(
                    baseDir = baseDir,
                    channelFactory = channelFactory,
                    downloader = Downloader(),
                    audit = audit,
                ),
                proot = proot,
                checkpoint = SessionCheckpoint(File(baseDir, "checkpoint")),
                controlServer = LocalControlServer(
                    sessions = sessions,
                    policy = CommandPolicy,
                    approval = approval,
                    audit = audit,
                    environment = environment,
                    tokenStore = tokenStore,
                    // 修复 issue #18：与运行时共享同一份 CommandRunner 缓存，
                    // 避免同一会话两个 runner 并发执行破坏哨兵串行互斥
                    runnerProvider = CommandRunnerCache::runnerOf,
                ),
            )
        }

        /** 关闭运行时持有的全部资源（进程退出前调用）。 */
        fun shutdown(runtime: ShellRuntime) {
            runtime.controlServer.stop()
            runtime.sessions.closeAll()
        }
    }
}
