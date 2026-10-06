package com.androidguru.agent.tools.hook

import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicLong

/**
 * 钩子注册表与派发器。
 *
 * - 链式派发：按 (order, 注册序) 依次执行；[HookEvent.PreToolUse] 的
 *   [HookDecision.Modify] 会改写参数传给下一个钩子，[HookDecision.Block] 立即短路。
 * - 异常隔离：单个钩子抛异常视为 Proceed，链继续走完（CancellationException 区分
 *   调用方取消，必须重抛）。
 * - 未注册任何钩子时 [dispatch] 零开销直返 Proceed。
 */
class HookRegistry {

    private val lock = Any()
    private val hooks = LinkedHashMap<String, HookEntry>()
    private val seq = AtomicLong(0)

    private data class HookEntry(val hook: ToolHook, val seqNo: Long)

    fun register(hook: ToolHook) {
        synchronized(lock) { hooks[hook.name] = HookEntry(hook, seq.getAndIncrement()) }
    }

    fun unregister(name: String): Boolean = synchronized(lock) { hooks.remove(name) != null }

    fun hookCount(): Int = synchronized(lock) { hooks.size }

    /**
     * 派发事件。返回最终决策（只有 PreToolUse 的 Block / Modify 有实际效果）。
     */
    suspend fun dispatch(event: HookEvent): HookDecision {
        val snapshot = synchronized(lock) { hooks.values.sortedWith(compareBy({ it.hook.order }, { it.seqNo })).map { it.hook } }
        if (snapshot.isEmpty()) return HookDecision.Proceed

        var currentEvent = event
        for (hook in snapshot) {
            val decision = try {
                hook.onEvent(currentEvent)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                // 单钩子异常隔离：视作不介入
                HookDecision.Proceed
            }
            if (decision is HookDecision.Block) return decision
            if (decision is HookDecision.Modify && currentEvent is HookEvent.PreToolUse) {
                currentEvent = currentEvent.copy(arguments = decision.arguments)
            }
        }
        return if (currentEvent is HookEvent.PreToolUse && currentEvent !== event) {
            HookDecision.Modify(currentEvent.arguments)
        } else {
            HookDecision.Proceed
        }
    }
}
