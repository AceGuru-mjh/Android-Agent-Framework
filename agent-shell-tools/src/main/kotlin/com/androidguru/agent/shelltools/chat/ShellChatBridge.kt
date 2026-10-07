package com.androidguru.agent.shelltools.chat

import com.androidguru.agent.chat.ApprovalUi
import com.androidguru.agent.chat.ChatItem
import com.androidguru.agent.chat.ChatPresenter
import com.androidguru.agent.shell.job.ShellJob
import com.androidguru.agent.shell.policy.ApprovalGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * Shell 宿主 ↔ 聊天层桥 —— 把框架组件接到 [ChatPresenter]。
 *
 * 职责边界：agent-chat 不依赖 agent-shell（聊天层可被任意宿主复用），所有
 * 类型适配都在这里做**字段拷贝** —— `ApprovalGate.Request` 逐字段拷贝成
 * [ApprovalUi]，`ApprovalGate.Verdict` 以名字字符串进入裁决词典。不做类型桥、
 * 不让壳对象穿过聊天层边界。
 *
 * 三条通道：
 * - **审批**：订阅 [ApprovalGate.requests]（StateFlow）→ 审批卡上屏；
 *   [respond] 把用户裁决转发回闸门并回填卡片；
 * - **作业**：[observeJobStore] 按 yl-ai ChatStore 加载重放 + ChatController.syncJobs
 *   的合并语义对账 RUNNING 作业卡（live→保持/改判，消失→FAILED）；
 * - **中止**：[cancel] 统一入口 —— 先释放闸门等待者（全部 DENY），再让聊天层回填。
 */
class ShellChatBridge(
    private val presenter: ChatPresenter,
    private val gate: ApprovalGate,
    private val scope: CoroutineScope,
) {

    private var watcher: Job? = null

    /** 开始监听审批闸门（幂等）。闸门单请求挂起，StateFlow 发 null 表示当前无请求。 */
    fun start() {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            gate.requests.collect { req ->
                if (req != null) {
                    // 字段拷贝：壳类型不越过本层
                    presenter.onApprovalRequest(
                        ApprovalUi(
                            id = req.id,
                            toolName = req.toolName,
                            title = req.title,
                            detail = req.detail,
                            impact = req.impact,
                        ),
                    )
                }
            }
        }
    }

    /** 停止监听（宿主退出 / 换会话时调用）。 */
    fun stop() {
        watcher?.cancel()
        watcher = null
    }

    /**
     * 提交用户裁决：转发 [ApprovalGate.respond]，仅当闸门确实收到（返回 true）
     * 才把卡片标记为已决 —— 超时自动 DENY 的请求闸门已不再接受意见，卡片不能
     * 显示成用户选过的结果。返回值即 [ApprovalGate.respond] 的结果。
     */
    fun respond(requestId: String, verdict: ApprovalGate.Verdict): Boolean {
        val delivered = gate.respond(requestId, verdict)
        if (delivered) {
            presenter.onApprovalResolved(requestId, verdict.name)
        }
        return delivered
    }

    /**
     * 宿主中止当前回合的统一入口：先 [ApprovalGate.cancelAll] 释放所有等待者
     * （全部 DENY —— fail-closed），再 [ChatPresenter.cancel] 回填聊天卡
     * （未决审批标 DENY + "已取消"系统注）。
     */
    fun cancel() {
        gate.cancelAll()
        presenter.cancel()
    }

    /**
     * 作业状态对账 —— yl-ai ChatStore 加载重放（fromJson 的 live/jobId 判定）
     * 与 ChatController.syncJobs（运行期对账）的合并版。
     *
     * 只处理 RUNNING 且带 jobId 的动作卡，其余卡片原样保留：
     * - **live 作业**（本进程内可操作）：RUNNING / STOPPING → 卡片保持 RUNNING；
     *   EXITED → 退出码 0 → OK、否则 FAILED（附"退出码 N"与日志输出尾部）；
     *   STOPPED → FAILED"已被停止"；INTERRUPTED → FAILED"App 进程结束，作业随之中断"；
     * - **仅存磁盘记录**（跨进程重启，作业已死）：记录状态改判 —— EXITED 按
     *   退出码定 OK / FAILED 并尽力从日志文件补输出尾部，其余一律 FAILED；
     * - **彻底消失**（live 与记录都没有）→ FAILED"作业已随上一次运行结束"
     *   （yl-ai 的原句：卡片如实反映"没盯到结果"，不猜成功）。
     *
     * 宿主在会话装载后、回到前台时、以及作业类工具调用结束后调用
     * （即 yl-ai syncJobs 的时机），无需轮询。
     */
    fun observeJobStore(store: ShellJob.ShellJobStore) {
        presenter.patch { list -> list.map { item -> reconcile(item, store) } }
    }

    private fun reconcile(item: ChatItem, store: ShellJob.ShellJobStore): ChatItem {
        val card = item as? ChatItem.ActionItem ?: return item
        val jobId = card.jobId ?: return item
        if (card.state != ChatItem.ActionState.RUNNING) return item

        val live = store.get(jobId)
        if (live != null) {
            return when (live.state) {
                ShellJob.State.RUNNING, ShellJob.State.STOPPING -> card
                ShellJob.State.EXITED -> card.copy(
                    state = if (live.exitCode == 0) ChatItem.ActionState.OK else ChatItem.ActionState.FAILED,
                    summary = if (live.exitCode == 0) card.summary else "退出码 ${live.exitCode}",
                    outputTail = live.readOutput(OUTPUT_TAIL_CHARS).ifBlank { card.outputTail },
                )
                ShellJob.State.STOPPED -> card.copy(
                    state = ChatItem.ActionState.FAILED,
                    summary = "已被停止",
                )
                ShellJob.State.INTERRUPTED -> card.copy(
                    state = ChatItem.ActionState.FAILED,
                    summary = "App 进程结束，作业随之中断",
                )
            }
        }

        // 无 live：看磁盘记录（跨进程重启场景，作业进程必已消亡）
        val record = store.getRecord(jobId)
        return when {
            record == null -> card.copy(
                state = ChatItem.ActionState.FAILED,
                summary = GONE_NOTE,
            )

            record.state == ShellJob.State.EXITED.name -> {
                val ok = record.exitCode == 0
                card.copy(
                    state = if (ok) ChatItem.ActionState.OK else ChatItem.ActionState.FAILED,
                    summary = if (ok) card.summary else "退出码 ${record.exitCode ?: -1}",
                    outputTail = readLogTail(record.log).ifBlank { card.outputTail },
                )
            }

            else ->
                // RUNNING/STOPPING 残留会被 ShellJobStore.init 改判为 INTERRUPTED；
                // STOPPED/INTERRUPTED 与其余状态：作业都不在了，如实回填
                card.copy(
                    state = ChatItem.ActionState.FAILED,
                    summary = if (record.state == ShellJob.State.STOPPED.name) "已被停止" else GONE_NOTE,
                )
        }
    }

    private fun readLogTail(path: String): String = runCatching {
        val f = File(path)
        if (!f.isFile) ""
        else f.readText().takeLast(OUTPUT_TAIL_CHARS).trim()
    }.getOrDefault("")

    companion object {
        const val OUTPUT_TAIL_CHARS = 4000

        /** yl-ai 的原句：作业随上一次运行结束，结果没有盯到。 */
        const val GONE_NOTE = "作业已随上一次运行结束"
    }
}
