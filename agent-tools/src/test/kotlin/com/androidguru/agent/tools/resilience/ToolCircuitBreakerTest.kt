package com.androidguru.agent.tools.resilience

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCircuitBreakerTest {

    @Test
    fun `连续失败达到阈值后打开`() {
        var now = 0L
        val breaker = ToolCircuitBreaker(failureThreshold = 3, openCooldownMs = 1000) { now }
        repeat(3) {
            assertTrue(breaker.tryAcquire("t"))
            breaker.recordFailure("t")
        }
        assertEquals(ToolCircuitBreaker.State.OPEN, breaker.stateOf("t"))
        assertFalse(breaker.tryAcquire("t"))
    }

    @Test
    fun `冷却后进入半开探测`() {
        var now = 0L
        val breaker = ToolCircuitBreaker(failureThreshold = 1, openCooldownMs = 1000) { now }
        breaker.tryAcquire("t")
        breaker.recordFailure("t")
        assertFalse(breaker.tryAcquire("t"))

        now = 1500
        assertTrue(breaker.tryAcquire("t")) // half-open probe
        assertEquals(ToolCircuitBreaker.State.HALF_OPEN, breaker.stateOf("t"))
        assertFalse(breaker.tryAcquire("t")) // 探测中不放行第二次
    }

    @Test
    fun `半开探测成功后恢复闭合`() {
        var now = 0L
        val breaker = ToolCircuitBreaker(failureThreshold = 1, openCooldownMs = 1000) { now }
        breaker.tryAcquire("t")
        breaker.recordFailure("t")

        now = 1500
        breaker.tryAcquire("t")
        breaker.recordSuccess("t")
        assertEquals(ToolCircuitBreaker.State.CLOSED, breaker.stateOf("t"))
        assertTrue(breaker.tryAcquire("t"))
    }

    @Test
    fun `半开探测失败后重新打开且冷却指数延长`() {
        var now = 0L
        val breaker = ToolCircuitBreaker(failureThreshold = 1, openCooldownMs = 1000, maxCooldownMs = 8000) { now }
        breaker.tryAcquire("t")
        breaker.recordFailure("t")

        now = 1000
        breaker.tryAcquire("t")
        val opened = breaker.recordFailure("t")
        assertTrue(opened)
        // 冷却从 1000 翻倍到 2000
        assertFalse(breaker.tryAcquire("t"))
        now = 2000
        assertFalse(breaker.tryAcquire("t"))
        now = 3000
        assertTrue(breaker.tryAcquire("t"))
    }
}
