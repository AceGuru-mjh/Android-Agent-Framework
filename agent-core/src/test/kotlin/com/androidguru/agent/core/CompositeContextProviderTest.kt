package com.androidguru.agent.core

import com.androidguru.agent.core.context.CompositeContextProvider
import com.androidguru.agent.core.engine.SystemContextProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组合上下文提供者：拼接 / 空值过滤 / 异常隔离 / 取消传播。
 */
class CompositeContextProviderTest {

    private class Fixed(private val text: String?) : SystemContextProvider {
        override suspend fun provideContext(sessionId: String): String = text ?: ""
    }

    private class Throwing(private val error: Exception = RuntimeException("boom")) : SystemContextProvider {
        override suspend fun provideContext(sessionId: String): String = throw error
    }

    @Test
    fun `多个提供者按序拼接`() = runTest {
        val provider = CompositeContextProvider(
            listOf(Fixed("# 计划\n- t1"), Fixed("# 记忆\n- 事实A")),
        )
        assertEquals("# 计划\n- t1\n\n# 记忆\n- 事实A", provider.provideContext("s1"))
    }

    @Test
    fun `空串提供者被过滤`() = runTest {
        val provider = CompositeContextProvider(
            listOf(Fixed(""), Fixed("# 记忆"), Fixed(null)),
        )
        assertEquals("# 记忆", provider.provideContext("s1"))
    }

    @Test
    fun `单点异常隔离不影响其余提供者`() = runTest {
        val provider = CompositeContextProvider(
            listOf(Fixed("# 计划"), Throwing(), Fixed("# 记忆")),
        )
        assertEquals("# 计划\n\n# 记忆", provider.provideContext("s1"))
    }

    @Test
    fun `全部为空时返回空串`() = runTest {
        val provider = CompositeContextProvider(listOf(Fixed(""), Throwing()))
        assertTrue(provider.provideContext("s1").isEmpty())
    }

    @Test
    fun `空提供者列表返回空串`() = runTest {
        val provider = CompositeContextProvider(emptyList())
        assertTrue(provider.provideContext("s1").isEmpty())
    }
}
