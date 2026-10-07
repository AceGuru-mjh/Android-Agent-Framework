package com.androidguru.agent.chat

import com.androidguru.agent.core.engine.AgentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * 审批请求的轻量视图 —— 宿主审批闸门的**字段拷贝**。
 *
 * agent-chat 不 import 任何 shell / 审批模块（聊天层可被任意宿主复用）；
 * agent-shell 的 ApprovalGate.Request 由 agent-shell-tools 的桥接层逐字段
 * 拷贝成此类型，自定义宿主同样可以直接构造它。
 */
data class ApprovalUi(
    val id: String,
    val toolName: String,
    val title: String,
    val detail: String,
    val impact: String,
)

/**
 * 事件→卡片转译器 —— yl-ai `ChatController` 聊天侧职责的纯 JVM 重写。
 *
 * 结构：**纯函数核心 + 有状态会话**。
 * - 纯函数核心 [ChatReducer]：`(List<ChatItem>, 事件) -> List<ChatItem>`，
 *   不读时钟、不碰共享状态、可直接单测；
 * - 有状态会话（本类）：持有 [items] 流、callId→卡片映射与 dirty 防抖管道。
 *
 * 与 yl-ai ChatController 的对应关系：
 * - StateFlow 卡片流 → [items]；
 * - 审批 watcher → [onApprovalRequest] / [onApprovalResolved]（去重按 requestId）；
 * - dirty debounce 500ms 持久化 → 内容版本计数 + debounce（比 yl-ai 的 SharedFlow
 *   方案多一层保障：订阅前发出的 dirty 不会丢失）；
 * - ReplyComplete 权威对齐 → [ChatReducer.alignFinal]（见其 KDoc）；
 * - cancel 全量 DENY 回填 → [cancel]（幂等：Aborted 事件与宿主调用双路径汇入）。
 *
 * 框架事件的处理取舍：
 * - [AgentEvent.ResponseChunk]：粘到最后一条流式气泡，末尾不是流式气泡则开新气泡
 *   （工具卡会截断正文段，正文分段成多个气泡）；
 * - [AgentEvent.ThinkingChunk]：忽略 —— 推理内容不属于正文，混入气泡会污染
 *   回复与持久化（yl-ai 的 Thinking 事件同样只占位不落文本）；
 * - [AgentEvent.ToolCallStart] / [ToolCallComplete]：动作卡生命周期；
 *   `ToolResult.data = {"jobId":…}` 是 job 类工具的宿主约定，命中时动作卡
 *   转为 RUNNING 作业卡并携带 jobId，进入作业对账通道；
 * - [AgentEvent.Complete]：权威对齐（"正文只和正文比"）；
 * - [AgentEvent.Error]：流式气泡收口 + ERROR 系统注；
 * - [AgentEvent.Aborted]：与 [cancel] 同一归约（收口 + 未决审批全 DENY + "已取消"）；
 * - [AgentEvent.LlmRetryScheduled]：忽略 —— 瞬时重试高频出现，持久化卡片会刷屏，
 *   过程可见性由宿主以 toast / 状态条呈现更合适。
 *
 * @param scope   转译器协程作用域（仅用于持久化收集器；事件入口本身无协程要求）。
 * @param store   历史存储；为 null 时不持久化（纯内存会话 / 测试）。
 * @param debounceMs dirty 后延迟落盘的窗口（yl-ai 500ms）。
 * @param clock   时钟注入（测试确定性）。
 */
class ChatPresenter(
    scope: CoroutineScope,
    private val store: ChatHistoryStore? = null,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()

    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())

    /** 当前卡片流（UI / 桥接层的数据源）。 */
    val items: StateFlow<List<ChatItem>> = _items.asStateFlow()

    /** 引擎 callId → 动作卡 id。完成事件据此回填正确的卡片。 */
    private val toolCards = ConcurrentHashMap<String, String>()

    /**
     * 内容变更版本计数（每次真实变更 +1）。
     * 用 StateFlow 而非 SharedFlow：订阅前发生的变更也会被保留，
     * 首轮事件不会因收集器尚未启动而丢持久化信号（yl-ai 方案的竞态隐患）。
     * version == 0 是构造初值，收集器跳过它以免空内容覆盖盘上历史。
     */
    private val dirty = MutableStateFlow(0L)

    init {
        if (store != null) {
            scope.launch {
                dirty.debounce(debounceMs).collect { version ->
                    if (version > 0L) {
                        // 保存的是收集时刻的快照：连发的变更只落一次盘、且是最新内容
                        runCatching { store.save(items.value) }
                    }
                }
            }
        }
    }

    // ---- 会话入口 ------------------------------------------------------

    /** 追加用户消息（空串忽略）。 */
    fun addUserMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        update { ChatReducer.addUser(it, trimmed, clock()) }
    }

    /** 引擎事件主入口。 */
    suspend fun onAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.ResponseChunk ->
                update { ChatReducer.appendResponse(it, event.text, clock()) }

            is AgentEvent.ToolCallStart -> {
                val summary = summarizeArguments(event.arguments)
                val cardId = updateAndReturn { list ->
                    ChatReducer.startTool(list, event.toolName, summary, clock())
                }
                toolCards[event.callId] = cardId
            }

            is AgentEvent.ToolCallComplete -> {
                val cardId = toolCards.remove(event.callId)
                val ok = event.result.ok
                val tail = event.result.content.ifBlank { null }
                val jobId = extractJobId(event.result.data)
                if (cardId != null) {
                    update { ChatReducer.completeTool(it, cardId, ok, tail, jobId) }
                } else {
                    // 未知 callId（宿主重放 / 上游丢 Start）：兜底补一张结果卡
                    update { ChatReducer.orphanTool(it, event.toolName, ok, tail, jobId, clock()) }
                }
            }

            is AgentEvent.Complete ->
                update { ChatReducer.alignFinal(it, event.summary) }

            is AgentEvent.Error ->
                update { ChatReducer.failTurn(it, "出错了：${event.message}", clock()) }

            is AgentEvent.Aborted ->
                update { ChatReducer.cancelTurn(it, clock()) }

            // 过程事件：不出卡片（理由见类 KDoc）
            is AgentEvent.IterationStart,
            is AgentEvent.ThinkingChunk,
            is AgentEvent.UsageUpdated,
            is AgentEvent.LlmRetryScheduled,
            is AgentEvent.UserInputRequired,
            -> Unit
        }
    }

    // ---- 审批 ----------------------------------------------------------

    /** 审批请求上卡（同一 requestId 重复上卡会被去重，与 yl-ai watcher 一致）。 */
    fun onApprovalRequest(request: ApprovalUi) {
        update { ChatReducer.addApproval(it, request, clock()) }
    }

    /** 审批裁决回填：把对应卡片标记已决。找不到未决卡则忽略。 */
    fun onApprovalResolved(requestId: String, verdict: String) {
        update { ChatReducer.resolveApproval(it, requestId, verdict) }
    }

    // ---- 中止与对账 ----------------------------------------------------

    /**
     * 宿主中止当前回合：流式气泡收口、未决审批全部 `DENY`、追加"已取消"。
     * 幂等 —— [AgentEvent.Aborted] 事件与宿主主动调用双路径汇入同一归约，
     * 尾注去重避免重复提示（yl-ai cancel 的回填语义 + Aborted 去重）。
     */
    fun cancel() {
        update { ChatReducer.cancelTurn(it, clock()) }
    }

    /**
     * 供桥接层 / 宿主做批量修补（作业状态对账、历史注入等）。
     * 内容无变化时不触发持久化。
     */
    fun patch(transform: (List<ChatItem>) -> List<ChatItem>) {
        update(transform)
    }

    // ---- 历史装载与清理 ------------------------------------------------

    /**
     * 装载持久化历史。仅在当前会话为空且盘上有内容时生效（yl-ai attach 的
     * 一次性装载语义，避免覆盖进行中的会话）。返回是否实际装载。
     */
    suspend fun loadHistory(): Boolean {
        val s = store ?: return false
        val saved = s.load()
        if (saved.isEmpty()) return false
        synchronized(lock) {
            if (_items.value.isNotEmpty()) return false
            _items.value = saved
        }
        return true
    }

    /** 立即持久化当前内容（关机钩子 / 测试用），绕过 debounce。 */
    suspend fun flush() {
        store?.save(items.value)
    }

    /** 清空会话并删除历史文件。 */
    suspend fun clear() {
        synchronized(lock) { _items.value = emptyList() }
        toolCards.clear()
        store?.clear()
    }

    // ---- 内部 ----------------------------------------------------------

    private fun markDirty() {
        if (store != null) dirty.value = dirty.value + 1
    }

    private fun update(transform: (List<ChatItem>) -> List<ChatItem>) {
        val changed = synchronized(lock) {
            val current = _items.value
            val next = transform(current)
            if (next != current) {
                _items.value = next
                true
            } else {
                false
            }
        }
        if (changed) markDirty()
    }

    /** [update] 的变体：把归约过程中产生的卡片 id 带出来（startTool 用）。 */
    private inline fun updateAndReturn(transform: (List<ChatItem>) -> Pair<List<ChatItem>, String>): String {
        var result = ""
        synchronized(lock) {
            val current = _items.value
            val (next, id) = transform(current)
            if (next != current) _items.value = next
            result = id
        }
        markDirty()
        return result
    }

    /**
     * 动作卡摘要：从工具参数 JSON 里挑一个最能说明"要干什么"的字段。
     * 只影响展示 —— 参数原文仍完整进入引擎上下文，这里不做任何语义加工。
     */
    private fun summarizeArguments(arguments: String): String {
        val parsed = runCatching { argsJson.parseToJsonElement(arguments) }.getOrNull()
        val text = when (parsed) {
            is JsonObject -> SUMMARY_KEYS.firstNotNullOfOrNull { key ->
                (parsed[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            } ?: arguments
            is JsonPrimitive -> parsed.content
            else -> arguments
        } ?: arguments
        return text.trim().take(MAX_SUMMARY_CHARS)
    }

    private val argsJson = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        const val DEBOUNCE_MS = 500L
        const val MAX_SUMMARY_CHARS = 200

        /** 摘要候选字段：命令类参数优先，覆盖 shell / fs / 网络 / 交互四类工具的惯例命名。 */
        private val SUMMARY_KEYS = listOf("command", "path", "input", "prompt", "url", "query")

        /**
         * 宿主约定：job 类工具（job_start 等）在 [com.androidguru.agent.tools.ToolResult.data]
         * 里声明后台作业 id。转译器据此把动作卡转为作业卡（见 [ChatItem.ActionItem.jobId]）。
         */
        fun extractJobId(data: String?): String? {
            if (data.isNullOrBlank()) return null
            return runCatching {
                val obj = Json.parseToJsonElement(data) as? JsonObject ?: return null
                (obj["jobId"] as? JsonPrimitive)?.content
            }.getOrNull()
        }
    }
}

/**
 * 纯函数归约核心：`(List<ChatItem>, 输入) -> List<ChatItem>`。
 * 不读时钟（ts 由调用方注入）、不碰可变状态 —— 每个函数都是可独立单测的变换。
 */
internal object ChatReducer {

    const val VERDICT_DENY = "DENY"
    const val CANCEL_NOTE = "已取消"

    /** 追加用户消息。 */
    fun addUser(items: List<ChatItem>, text: String, ts: Long): List<ChatItem> =
        items + ChatItem.UserMessage(text = text, ts = ts)

    /**
     * 回复增量：粘到最后一条流式气泡；末尾不是流式气泡（首句 / 工具卡之后）
     * 则开新气泡。正文因此按工具调用自然分段 —— 与 yl-ai 单占位符 + 换行拼接
     * 的差异是框架事件模型（无全局占位符）下的等价实现。
     */
    fun appendResponse(items: List<ChatItem>, delta: String, ts: Long): List<ChatItem> {
        if (delta.isEmpty()) return items
        val last = items.lastOrNull()
        return if (last is ChatItem.AssistantMessage && last.streaming) {
            items.dropLast(1) + last.copy(text = last.text + delta)
        } else {
            items + ChatItem.AssistantMessage(text = delta, streaming = true, ts = ts)
        }
    }

    /**
     * 开工具卡：先收口当前流式气泡（后续增量不应再粘进这段正文），再追加
     * RUNNING 动作卡。返回 (新列表, 卡片 id)。
     */
    fun startTool(items: List<ChatItem>, toolName: String, summary: String, ts: Long): Pair<List<ChatItem>, String> {
        val card = ChatItem.ActionItem(toolName = toolName, summary = summary, ts = ts)
        return (closeStreaming(items) + card) to card.id
    }

    /**
     * 工具完成：RUNNING → OK / FAILED。若结果以宿主约定声明了 jobId
     * （job 类工具），动作卡转为 RUNNING 作业卡 —— 工具调用已成功返回，
     * 此后卡片追踪的是后台作业本身（对账见 agent-shell-tools 的 ShellChatBridge）。
     */
    fun completeTool(
        items: List<ChatItem>,
        cardId: String,
        ok: Boolean,
        outputTail: String?,
        jobId: String? = null,
    ): List<ChatItem> = patchById(items, cardId) { item ->
        when {
            item is ChatItem.ActionItem && item.state == ChatItem.ActionState.RUNNING && jobId != null && ok ->
                item.copy(jobId = jobId, outputTail = outputTail ?: item.outputTail)

            item is ChatItem.ActionItem && item.state == ChatItem.ActionState.RUNNING ->
                item.copy(
                    state = if (ok) ChatItem.ActionState.OK else ChatItem.ActionState.FAILED,
                    outputTail = outputTail ?: item.outputTail,
                )

            else -> item
        }
    }

    /** 未知 callId 的完成事件：兜底补一张结果卡（yl-ai Executed 的 fallback）。 */
    fun orphanTool(
        items: List<ChatItem>,
        toolName: String,
        ok: Boolean,
        outputTail: String?,
        jobId: String?,
        ts: Long,
    ): List<ChatItem> = items + ChatItem.ActionItem(
        toolName = toolName,
        state = if (ok) ChatItem.ActionState.OK else ChatItem.ActionState.FAILED,
        outputTail = outputTail,
        jobId = jobId,
        ts = ts,
    )

    /**
     * **权威对齐（yl-ai ReplyComplete 的"正文只和正文比"）**。
     *
     * 引擎在 Complete 里给出最终正文（权威文本）；流式增量可能因分段、截断
     * 或模型改口与最终正文不一致。对齐规则：
     * - 权威文本为空 → 保留已显示内容（有总比没有强）；
     * - 与已显示一致 → 不动；
     * - 权威文本以已显示文本为**前缀** → 补齐尾部（正常路径：只是最后一窗增量丢了）；
     * - 其余 → 整体覆盖（流式期间内容走样，以权威为准）。
     *
     * 前缀比较在**文本对文本**之间进行 —— 不与动作卡、审批卡比较，故称
     * "正文只和正文比"（yl-ai 的原话，防止把正文对到命令卡上）。
     * 不存在流式气泡且权威文本非空 → 追加定稿气泡（覆盖非流式宿主）。
     */
    fun alignFinal(items: List<ChatItem>, authoritative: String): List<ChatItem> {
        val idx = items.indexOfLast { it is ChatItem.AssistantMessage && it.streaming }
        if (idx < 0) {
            return if (authoritative.isNotBlank()) {
                items + ChatItem.AssistantMessage(text = authoritative, streaming = false)
            } else {
                items
            }
        }
        val bubble = items[idx] as ChatItem.AssistantMessage
        val aligned = when {
            authoritative.isBlank() -> bubble.text
            bubble.text == authoritative -> bubble.text
            authoritative.startsWith(bubble.text) -> authoritative
            else -> authoritative
        }
        val next = items.toMutableList()
        next[idx] = bubble.copy(text = aligned, streaming = false)
        return next
    }

    /** 错误收口：流式气泡置终态 + 追加 ERROR 系统注。 */
    fun failTurn(items: List<ChatItem>, message: String, ts: Long): List<ChatItem> =
        closeStreaming(items) + ChatItem.SystemNote(text = message, level = ChatItem.Level.ERROR, ts = ts)

    /**
     * 中止回合：流式气泡收口、未决审批全部 DENY 回填、追加"已取消"。
     * 尾注去重 —— Aborted 事件与宿主 cancel() 双路径汇入时只提示一次。
     */
    fun cancelTurn(items: List<ChatItem>, ts: Long): List<ChatItem> {
        var next = closeStreaming(items)
        next = next.map { item ->
            if (item is ChatItem.ApprovalItem && !item.resolved) {
                item.copy(resolved = true, verdict = VERDICT_DENY)
            } else {
                item
            }
        }
        val last = next.lastOrNull()
        if (last is ChatItem.SystemNote && last.text == CANCEL_NOTE) return next
        return next + ChatItem.SystemNote(text = CANCEL_NOTE, level = ChatItem.Level.WARN, ts = ts)
    }

    /** 审批上卡（requestId 去重：StateFlow 重放 / 双订阅不会产生重复卡）。 */
    fun addApproval(items: List<ChatItem>, request: ApprovalUi, ts: Long): List<ChatItem> {
        if (items.any { it is ChatItem.ApprovalItem && it.requestId == request.id }) return items
        return items + ChatItem.ApprovalItem(
            requestId = request.id,
            toolName = request.toolName,
            title = request.title,
            detail = request.detail,
            impact = request.impact,
            ts = ts,
        )
    }

    /** 裁决回填：最近一张该 request 的未决卡置已决。 */
    fun resolveApproval(items: List<ChatItem>, requestId: String, verdict: String): List<ChatItem> =
        items.map { item ->
            if (item is ChatItem.ApprovalItem && item.requestId == requestId && !item.resolved) {
                item.copy(resolved = true, verdict = verdict)
            } else {
                item
            }
        }

    /** 收口所有流式气泡（正常情况至多一张；防御性处理全部）。 */
    fun closeStreaming(items: List<ChatItem>): List<ChatItem> =
        if (items.any { it is ChatItem.AssistantMessage && it.streaming }) {
            items.map { item ->
                if (item is ChatItem.AssistantMessage && item.streaming) item.copy(streaming = false) else item
            }
        } else {
            items
        }

    private inline fun patchById(
        items: List<ChatItem>,
        id: String,
        transform: (ChatItem) -> ChatItem,
    ): List<ChatItem> {
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return items
        val next = items.toMutableList()
        next[idx] = transform(next[idx])
        return next
    }
}
