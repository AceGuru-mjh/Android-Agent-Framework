package com.androidguru.agent.shell.job

import com.androidguru.agent.shell.process.ProcessChannel
import com.androidguru.agent.shell.process.ProcessChannelFactory
import com.androidguru.agent.shell.runtime.ShellEnvironment
import com.androidguru.agent.shell.terminal.AnsiStripper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger

/**
 * 后台作业 —— 生命周期比一次工具调用长的任务（起服务器 / 下载 / 长编译）。
 *
 * 为什么必须有（yl-ai 的核心论证）：`terminal_exec` 的模型是「执行-等待-返回」，
 * 所有真正有用的长任务都塞不进去 —— 硬等只会超时，超时后进程状态就丢了，
 * 用户会看到「AI 说失败了，其实服务还开着」。作业的模型是「**启动-记账-随时查看**」：
 *
 * - 跑在独立通道里（独立进程组），不占用用户正在看的终端；
 * - 输出**直接重定向到日志文件**（`>log 2>&1`），边跑边落盘 ——
 *   这是本次移植的一个简化优化：不再用 `tee` + 管道，`$?` 即命令真实退出码
 *   （原实现的 `PIPESTATUS` 修正只在 bash 下有效，纯 sh 会语法错误）；
 * - 状态写进 `index.json`（纯 JSON，出问题时 cat 一下就能看）；
 * - 自动探测输出里的可访问地址（`0.0.0.0:8080` → `http://127.0.0.1:8080`）。
 */
class ShellJob(
    val id: String,
    val command: String,
    val workdir: String,
    val logFile: File,
    private val channel: ProcessChannel,
    private val store: ShellJobStore?,
) {

    @Serializable
    enum class State { RUNNING, STOPPING, STOPPED, EXITED, INTERRUPTED }

    // 修复 issue #20：state 跨线程读写（watch 协程 / stop / JobTools 轮询）需保证可见性
    @Volatile
    var state: State = State.RUNNING
        private set

    var exitCode: Int? = null
        private set

    var startedAt: Long = System.currentTimeMillis()
        private set

    var finishedAt: Long? = null
        private set

    /** 从输出里探测到的服务地址（起服务器场景自动给出）。 */
    @Volatile
    var serviceUrl: String? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null

    val durationMs: Long
        get() = (finishedAt ?: System.currentTimeMillis()) - startedAt

    /** 启动 watch 协程：轮询存活 + 输出探测 + 结束改判。 */
    internal fun startWatching() {
        watchJob = scope.launch {
            // 服务地址探测：在日志文件里找监听地址
            var lastSize = 0L
            var quietRounds = 0
            while (isActive) {
                delay(200)
                if (channel.isAlive()) {
                    val size = runCatching { logFile.length() }.getOrDefault(0L)
                    if (size > lastSize) {
                        probeServiceUrl(lastSize, size)
                        lastSize = size
                        quietRounds = 0
                    }
                } else {
                    // 等日志长度连续两次不变再收尾（防丢最后一段输出，yl-ai 的教训）
                    quietRounds++
                    if (quietRounds >= 2) {
                        finish()
                        break
                    }
                }
            }
        }
    }

    private fun probeServiceUrl(fromByte: Long, toByte: Long) {
        val text = runCatching {
            RandomAccessFile(logFile, "r").use { raf ->
                val len = (toByte - fromByte).coerceAtMost(64 * 1024)
                val buf = ByteArray(len.toInt())
                raf.seek(fromByte)
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        }.getOrNull() ?: return

        val match = Regex("""(?:0\.0\.0\.0|127\.0\.0\.1|localhost|\[::]|\*):(\d{2,5})""").find(text) ?: return
        val port = match.groupValues[1]
        serviceUrl = "http://127.0.0.1:$port"
        store?.save(this)
    }

    private fun finish() {
        val code = runCatching { channel.waitFor(1000) }.getOrDefault(-1)
        // 修复 issue #20：stop() 已判停（STOPPING/STOPPED）时不得改判 ——
        // 旧实现里 stop() 置 STOPPED 后 watch 循环的 finish() 会把
        // 用户主动停止的作业改写成 EXITED，退出码也被 waitFor 的 137 覆盖
        synchronized(this) {
            if (state == State.STOPPING || state == State.STOPPED) return
            exitCode = code
            finishedAt = System.currentTimeMillis()
            state = State.EXITED
        }
        store?.save(this)
    }

    /**
     * 停止作业：Ctrl-C（PTTY 场景）→ SIGTERM → 强杀的阶梯。
     */
    suspend fun stop() {
        if (!channel.isAlive()) return
        state = State.STOPPING
        store?.save(this)
        channel.signal(ProcessChannel.SIGINT)
        delay(2000)
        if (channel.isAlive()) {
            channel.signal(ProcessChannel.SIGTERM)
            delay(500)
        }
        if (channel.isAlive()) {
            channel.signal(ProcessChannel.SIGKILL)
        }
        if (!channel.isAlive()) {
            exitCode = -1
            finishedAt = System.currentTimeMillis()
            state = State.STOPPED
        } else {
            // 通道仍存活（极少数情况）：强制判停
            exitCode = -1
            finishedAt = System.currentTimeMillis()
            state = State.STOPPED
        }
        store?.save(this)
    }

    /** 读取输出尾部（已清洗 ANSI）。 */
    fun readOutput(maxChars: Int = 20000): String {
        if (!logFile.isFile) return ""
        return runCatching {
            val bytes = logFile.length().coerceAtMost(maxChars.toLong() * 4)
            RandomAccessFile(logFile, "r").use { raf ->
                raf.seek(logFile.length() - bytes)
                val buf = ByteArray(bytes.toInt())
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        }.map { AnsiStripper.clean(it) }
            .getOrDefault("")
            .takeLast(maxChars)
    }

    /** 面向模型的文本渲染。 */
    fun toToolText(includeOutput: Boolean = false, maxOutputChars: Int = 4000): String = buildString {
        appendLine("作业: $id")
        appendLine("命令: $command")
        appendLine("状态: ${state.name}")
        appendLine("时长: ${durationMs / 1000}s")
        exitCode?.let { appendLine("退出码: $it") }
        serviceUrl?.let { appendLine("可访问地址: $it") }
        appendLine("日志: ${logFile.absolutePath}")
        if (includeOutput) {
            appendLine("输出尾部:")
            append(readOutput(maxOutputChars).ifBlank { "(无输出)" })
        }
    }

    fun close() {
        runCatching { channel.close() }
        scope.cancel()
    }

    // ------------------------------------------------------------------

    /**
     * 作业持久化存储：`index.json` + 重启后状态改判。
     *
     * 改判原则（保留 yl-ai 的设计）：进程挂在 App 进程下，App 一死必然也没了 ——
     * 残留的 running/stopping **如实改判为 interrupted**，留假状态只会误导用户。
     */
    class ShellJobStore(private val jobsDir: File) {

        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
        private val lock = Any()
        private val live = LinkedHashMap<String, ShellJob>()
        private val records = LinkedHashMap<String, Record>()
        private val counter = AtomicInteger(0)

        val dir: File get() = jobsDir

        fun init() {
            jobsDir.mkdirs()
            loadAndReadjudicate()
        }

        private fun loadAndReadjudicate() {
            val index = File(jobsDir, INDEX_FILE)
            if (!index.isFile) return
            runCatching {
                val container = json.decodeFromString<Container>(index.readText())
                container.jobs.forEach { rec ->
                    val adjudicated = if (rec.state == "RUNNING" || rec.state == "STOPPING") {
                        rec.copy(state = "INTERRUPTED")
                    } else rec
                    records[rec.id] = adjudicated
                }
                persist()
            }
        }

        /** 新作业 id（时间 + 计数，base36）。 */
        fun newId(): String =
            "job_" + java.lang.Long.toString(System.currentTimeMillis(), 36) +
                "_" + java.lang.Integer.toString(counter.incrementAndGet(), 36)

        fun register(job: ShellJob) {
            synchronized(lock) { live[job.id] = job }
            save(job)
        }

        fun save(job: ShellJob) {
            synchronized(lock) {
                records[job.id] = Record(
                    id = job.id,
                    command = job.command,
                    workdir = job.workdir,
                    state = job.state.name,
                    log = job.logFile.absolutePath,
                    startedAt = job.startedAt,
                    durationMs = job.durationMs,
                    exitCode = job.exitCode,
                    url = job.serviceUrl,
                )
            }
            persist()
        }

        /** 磁盘记录 ∪ live（按 id 覆盖，startedAt 倒序）。 */
        fun list(): List<Record> = synchronized(lock) {
            (records.values.asSequence() + live.values.map { it.toRecord() })
                .distinctBy { it.id }
                .sortedByDescending { it.startedAt }
                .toList()
        }

        /** live 作业才可操作（跨进程的旧作业只能读日志）。 */
        fun get(id: String): ShellJob? = synchronized(lock) { live[id] }

        fun getRecord(id: String): Record? = synchronized(lock) { records[id] }

        private fun persist() {
            val snapshot = synchronized(lock) {
                Container(jobs = list())
            }
            runCatching {
                jobsDir.mkdirs()
                // 修复 issue #21 L-4：tmp 名加唯一后缀，避免并发 persist 互相截断
                val tmp = File(jobsDir, "$INDEX_FILE.tmp-${System.nanoTime()}")
                tmp.writeText(json.encodeToString(snapshot))
                val index = File(jobsDir, INDEX_FILE)
                if (index.exists()) index.delete()
                if (!tmp.renameTo(index)) {
                    index.writeText(json.encodeToString(snapshot))
                    tmp.delete()
                }
            }
        }

        private fun ShellJob.toRecord() = Record(
            id = id,
            command = command,
            workdir = workdir,
            state = state.name,
            log = logFile.absolutePath,
            startedAt = startedAt,
            durationMs = durationMs,
            exitCode = exitCode,
            url = serviceUrl,
        )

        @Serializable
        data class Record(
            val id: String,
            val command: String,
            val workdir: String,
            val state: String,
            val log: String,
            val startedAt: Long,
            val durationMs: Long,
            val exitCode: Int? = null,
            val url: String? = null,
        )

        @Serializable
        data class Container(val jobs: List<Record>)

        companion object {
            const val INDEX_FILE = "index.json"
        }
    }

    companion object {
        /**
         * 启动作业：独立通道 + 输出重定向到日志文件。
         */
        fun start(
            id: String,
            command: String,
            workdir: String?,
            environment: ShellEnvironment,
            channelFactory: ProcessChannelFactory,
            store: ShellJobStore?,
        ): ShellJob {
            val dir = store?.dir ?: File(environment.tmp, "jobs")
            val logFile = File(dir, "$id.log")
            if (logFile.exists()) logFile.delete()

            val cwd: File = workdir?.let { w -> File(w).takeIf { d -> d.isDirectory } } ?: environment.home
            // 作业包装：子 shell 圆括号 + 输出全部重定向进日志；$? 直接是命令的退出码
// （无 tee 管道 → 无 PIPESTATUS 依赖；圆括号对换行/分号的容错优于花括号）
            val wrapped = "(\n$command\n) >'${logFile.absolutePath}' 2>&1\n"
            val argv = listOf("-c", wrapped)

            val env = environment.buildEnv(
                linkedMapOf(
                    "AGSH_JOB_ID" to id,
                    "AGSH_JOB_LOG" to logFile.absolutePath,
                ),
            )
            val channel = channelFactory.open(
                program = environment.defaultShell(),
                argv = argv,
                env = env,
                cwd = cwd,
                rows = 40,
                cols = 120,
            )
            val job = ShellJob(
                id = id,
                command = command,
                workdir = cwd.absolutePath,
                logFile = logFile,
                channel = channel,
                store = store,
            )
            store?.register(job)
            job.startWatching()
            return job
        }
    }
}
