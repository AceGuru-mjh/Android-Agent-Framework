package com.androidguru.agent.shell.session

import com.androidguru.agent.shell.process.ProcessChannelFactory
import com.androidguru.agent.shell.runtime.ShellEnvironment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

/**
 * 会话管理器 —— 终端会话的注册表。
 *
 * 与 yl-ai 的差异：移除 Android 前台服务拉起（保活是宿主职责，框架只管会话本身）；
 * 容器 / Termux 会话通过 [ShellSessionManager.createInContainer] 与容器模块协作。
 */
class ShellSessionManager(
    private val environment: ShellEnvironment,
    private val channelFactory: ProcessChannelFactory,
) {

    private val sessions = AtomicReference(LinkedHashMap<String, ShellSession>())
    private val counter = AtomicInteger(0)

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    /** 恢复提示（上次未干净退出时由检查点设置，宿主可展示给用户）。 */
    data class RestoreHint(val sessionId: String, val cwd: String)

    private val _restoreHint = MutableStateFlow<RestoreHint?>(null)
    val restoreHint: StateFlow<RestoreHint?> = _restoreHint.asStateFlow()

    /** 新建本地 shell 会话。 */
    fun create(title: String? = null, cwd: String? = null): ShellSession {
        val n = counter.incrementAndGet()
        val session = ShellSession(
            id = newSessionId(),
            title = title ?: "终端 $n",
            environment = environment,
            channelFactory = channelFactory,
        )
        session.start(cwd = cwd ?: environment.home.absolutePath)
        register(session)
        return session
    }

    /** 注册外部启动的会话（容器 / Termux / 宿主自定义通道）。 */
    fun registerExternal(session: ShellSession) = register(session)

    private fun register(session: ShellSession) {
        sessions.updateAndGet { cur ->
            LinkedHashMap(cur).apply { put(session.id, session) }
        }
        if (_activeId.value == null) _activeId.value = session.id
    }

    fun get(id: String): ShellSession? = sessions.get()[id]

    val active: ShellSession?
        get() = _activeId.value?.let { get(it) }

    fun setActive(id: String): Boolean {
        if (get(id) == null) return false
        _activeId.value = id
        return true
    }

    val sessionList: List<SessionInfo>
        get() = sessions.get().values.map { it.toInfo() }

    fun close(id: String): Boolean {
        val s = get(id) ?: return false
        s.close()
        sessions.updateAndGet { cur ->
            LinkedHashMap(cur).apply { remove(id) }
        }
        if (_activeId.value == id) {
            _activeId.value = sessions.get().keys.firstOrNull()
        }
        return true
    }

    fun closeAll() {
        sessions.get().values.forEach { it.close() }
        sessions.set(LinkedHashMap())
        _activeId.value = null
    }

    val count: Int get() = sessions.get().size

    /** 记录「未干净退出」（宿主崩溃恢复流程使用）。 */
    fun noteUncleanExit(id: String, cwd: String) {
        _restoreHint.value = RestoreHint(id, cwd)
    }

    fun clearRestoreHint() {
        _restoreHint.value = null
    }

    private fun newSessionId(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(8)

    private fun ShellSession.toInfo() = SessionInfo(
        id = id,
        title = title,
        state = state.value,
        busy = busy.value,
        cwd = currentDirectory,
    )
}

/** 会话摘要（UI / 控制 API 共用）。 */
data class SessionInfo(
    val id: String,
    val title: String,
    val state: SessionState,
    val busy: Boolean,
    val cwd: String,
)
