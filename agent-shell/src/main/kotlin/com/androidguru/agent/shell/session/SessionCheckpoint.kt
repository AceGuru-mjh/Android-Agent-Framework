package com.androidguru.agent.shell.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 会话检查点 —— 原子快照 + 崩溃恢复。
 *
 * 模型（保留 yl-ai 的设计决策）：**进程不恢复，恢复的是视觉与工作目录**。
 * 原来运行的进程与后台任务已随系统回收，快照只保留 title / cwd / 尾部输出；
 * 恢复时重建新会话并回放尾部，用户不丢失上下文，也不被假状态误导。
 *
 * 原子写纪律：先写 `.tmp` → 删除目标 → rename（yl-ai 的 tmp→delete→rename 模式）。
 */
class SessionCheckpoint(
    private val baseDir: File,
    private val maxSessions: Int = MAX_SESSIONS,
) {

    private val file: File get() = File(baseDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** 保存全部会话快照。 */
    fun save(manager: ShellSessionManager): Boolean {
        val snapshots = manager.sessionList.map { info ->
            val tail = manager.get(info.id)?.tailText(MAX_TAIL_CHARS) ?: ""
            Snapshot(
                id = info.id,
                title = info.title,
                cwd = info.cwd,
                state = info.state.name,
                savedAt = System.currentTimeMillis(),
                tail = tail,
            )
        }.take(maxSessions)
        if (snapshots.isEmpty()) return false
        return writeAtomic(Container(version = VERSION, savedAt = System.currentTimeMillis(), sessions = snapshots))
    }

    /** 读取快照（过期返回 null）。 */
    fun load(maxAgeMs: Long = DEFAULT_MAX_AGE_MS): Container? {
        if (!file.isFile) return null
        return runCatching {
            val container = json.decodeFromString<Container>(file.readText())
            if (System.currentTimeMillis() - container.savedAt > maxAgeMs) return null
            container
        }.getOrNull()
    }

    /**
     * 恢复会话：逐快照重建新会话（cwd 失效回 home），回放尾部输出与说明横幅。
     *
     * @return 恢复的会话数（0 = 无可恢复快照，调用方可直接新建默认会话）
     */
    fun restore(manager: ShellSessionManager): Int {
        val container = load() ?: return 0
        var restored = 0
        for (snapshot in container.sessions) {
            val cwd = runCatching { java.io.File(snapshot.cwd) }
                .getOrNull()
                ?.takeIf { it.isDirectory }
                ?.absolutePath
            val session = manager.create(title = snapshot.title, cwd = cwd)
            session.pushReplay(
                "[会话已恢复] ${snapshot.title}\r\n" +
                    "这是重建的新会话：原来运行的进程与后台任务已随系统回收，无法恢复。\r\n" +
                    "以下是上次退出时的终端尾部内容：\r\n\r\n"
            )
            session.pushReplay(snapshot.tail.replace("\n", "\r\n") + "\r\n")
            restored++
        }
        return restored
    }

    fun clear() {
        runCatching { file.delete() }
    }

    private fun writeAtomic(container: Container): Boolean {
        return runCatching {
            baseDir.mkdirs()
            val tmp = File(baseDir, "$FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(container))
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                // rename 失败（跨文件系统等）：回退直写
                file.writeText(json.encodeToString(container))
                tmp.delete()
            }
            true
        }.getOrDefault(false)
    }

    @Serializable
    data class Snapshot(
        val id: String,
        val title: String,
        val cwd: String,
        val state: String,
        val savedAt: Long,
        val tail: String,
    )

    @Serializable
    data class Container(
        val version: Int,
        val savedAt: Long,
        val sessions: List<Snapshot>,
    )

    companion object {
        const val FILE_NAME = "sessions.json"
        const val VERSION = 1
        const val MAX_SESSIONS = 20
        const val MAX_TAIL_CHARS = 64 * 1024
        const val DEFAULT_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
