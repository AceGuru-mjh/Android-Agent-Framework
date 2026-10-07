package com.androidguru.agent.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 聊天流水卡片模型 —— yl-ai `dev.aiterm.core.chat.ChatItem` 的纯 JVM 重写。
 *
 * 设计要点（与 yl-ai 对齐 + 框架化取舍）：
 * - **sealed + @Serializable**：一张卡片就是一条可持久化的 UI 事实。用 kotlinx
 *   多态序列化（`type` 判别字段 + [SerialName] 短名）替代 yl-ai 手写的
 *   JSONObject↔ChatItem 双向映射 —— 新增卡片类型只需加一个子类，存取层零改动；
 * - **纯 JVM**：yl-ai 版本依赖 `androidx.compose.runtime.Immutable` 与
 *   `CommandResult` / `ApprovalCenter` 等宿主类型；本模型不感知任何 UI 框架与
 *   shell 模块，输出尾部直接用 [String]，裁决用宿主词典字符串；
 * - **ActionState 保留 5 态**：RUNNING / OK / FAILED / BLOCKED / DENIED。
 *   框架事件流里被拦截 / 被拒绝的调用通常以"失败工具结果"形式到达
 *   （PreToolUse 钩子在事件之前工作），但宿主的直连执行路径（输入面板直接
 *   跑命令、控制 API 写终端）仍需要区分"策略拦截"与"用户拒绝"，模型层
 *   完整保留这两种终态。
 */
@Serializable
sealed interface ChatItem {

    /** 卡片唯一标识（UI 增量渲染 / 精确修补的 key）。 */
    val id: String

    /** 产生时间（epoch ms）。 */
    val ts: Long

    /** 用户输入。 */
    @Serializable
    @SerialName("user")
    data class UserMessage(
        override val id: String = newId(),
        override val ts: Long = now(),
        val text: String,
    ) : ChatItem

    /**
     * 助手回复气泡。
     *
     * [streaming] 表示该气泡仍在被 [com.androidguru.agent.core.engine.AgentEvent.ResponseChunk]
     * 增量喂入。持久化重放时一律置 false —— 重启后不存在"仍在流式"的气泡
     * （yl-ai ChatStore.fromJson 的既定语义）。
     */
    @Serializable
    @SerialName("assistant")
    data class AssistantMessage(
        override val id: String = newId(),
        override val ts: Long = now(),
        val text: String,
        val streaming: Boolean = false,
    ) : ChatItem

    /**
     * 工具 / 命令动作卡。
     *
     * @param toolName  工具标识（terminal_exec / fs_write / …）。
     * @param summary   卡片副标题：创建时是参数摘要（要干什么），终态对账时
     *                  会被 [ChatItem.ActionState.FAILED] 的原因说明覆盖或补充
     *                  （"已被停止" / "作业已随上一次运行结束" / "退出码 N"）。
     * @param outputTail 工具输出尾部；仅作展示，持久化前由 ChatHistoryStore 截到 4000 字符。
     * @param jobId     关联的后台作业（ShellJob id）。job 类工具以
     *                  `ToolResult.data = {"jobId":…}` 声明，转译器据此把动作卡
     *                  转为作业卡并进入对账通道。
     */
    @Serializable
    @SerialName("action")
    data class ActionItem(
        override val id: String = newId(),
        override val ts: Long = now(),
        val toolName: String,
        val summary: String = "",
        val state: ActionState = ActionState.RUNNING,
        val outputTail: String? = null,
        val jobId: String? = null,
    ) : ChatItem

    enum class ActionState { RUNNING, OK, FAILED, BLOCKED, DENIED }

    /**
     * 审批确认卡（未决时用户可放行 / 拒绝）。
     *
     * [verdict] 是宿主词典字符串而非枚举：agent-chat 不 import agent-shell，
     * ApprovalGate.Verdict 的名字（ALLOW_ONCE / ALLOW_ALWAYS / DENY）由桥接层
     * 字段拷贝进来；自定义宿主可以定义自己的裁决词典。
     */
    @Serializable
    @SerialName("approval")
    data class ApprovalItem(
        override val id: String = newId(),
        override val ts: Long = now(),
        val requestId: String,
        val toolName: String,
        val title: String,
        val detail: String,
        val impact: String,
        val resolved: Boolean = false,
        val verdict: String? = null,
    ) : ChatItem

    /** 系统提示（错误 / 中止 / 过程说明等非正文信息）。 */
    @Serializable
    @SerialName("note")
    data class SystemNote(
        override val id: String = newId(),
        override val ts: Long = now(),
        val text: String,
        val level: Level = Level.INFO,
    ) : ChatItem

    enum class Level { INFO, WARN, ERROR }

    companion object {
        fun newId(): String = UUID.randomUUID().toString().take(10)
        fun now(): Long = System.currentTimeMillis()
    }
}
