package com.androidguru.agent.core.engine

import kotlinx.coroutines.flow.Flow

/**
 * Agent 引擎抽象。
 *
 * 引擎把「LLM + 工具」驱动为一个事件流：宿主 collect [AgentEvent] 即可获得
 * 思考 / 工具 / 回复的全过程可见性。
 */
interface AgentEngine {

    /** 执行一次任务。同一时刻仅允许一个活跃任务（并发调用会被拒绝并收到 Error 事件）。 */
    fun execute(input: UserInput): Flow<AgentEvent>

    /** 文本快捷入口。 */
    fun execute(text: String): Flow<AgentEvent> = execute(UserInput(text))

    /** 中止当前任务（协作 + 硬取消双保险）。 */
    suspend fun abort()

    /**
     * 向挂起的 [AgentEvent.UserInputRequired] 投递用户回复。
     * 返回 false 表示当前没有等待中的输入请求（或已超时）。
     */
    suspend fun submitUserInput(answer: String): Boolean

    /** 当前是否有任务在运行。 */
    val isRunning: Boolean
}
