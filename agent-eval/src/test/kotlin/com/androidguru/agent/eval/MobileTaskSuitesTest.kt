package com.androidguru.agent.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 移动端任务套件完整性测试：id 唯一 / 判据非空 / 冒烟子集有效 / 类别覆盖。
 */
class MobileTaskSuitesTest {

    @Test
    fun `默认套件非空且 id 唯一`() {
        val suite = MobileTaskSuites.defaultSuite()
        assertTrue(suite.size >= 15)
        assertEquals(suite.size, suite.map { it.id }.toSet().size)
    }

    @Test
    fun `每个任务都有可判据`() {
        for (task in MobileTaskSuites.defaultSuite()) {
            assertTrue("任务 ${task.id} 缺少必做调用", task.criteria.requiredCalls.isNotEmpty())
        }
    }

    @Test
    fun `参数约束的期望值不含非法正则`() {
        for (task in MobileTaskSuites.defaultSuite()) {
            for (required in task.criteria.requiredCalls + task.criteria.forbiddenCalls) {
                for (check in required.argChecks) {
                    if (check.op == MatchOp.REGEX) {
                        // 构造期已校验；这里再走一遍确保套件内无坏正则
                        Regex(check.expected)
                    }
                }
            }
        }
    }

    @Test
    fun `冒烟套件覆盖全部类别`() {
        val smoke = MobileTaskSuites.smokeSuite()
        assertTrue(smoke.isNotEmpty())
        val categories = smoke.map { it.category }.toSet()
        for (expected in listOf(
            MobileTaskSuites.CATEGORY_SYSTEM,
            MobileTaskSuites.CATEGORY_MESSAGING,
            MobileTaskSuites.CATEGORY_MEDIA,
            MobileTaskSuites.CATEGORY_APPS,
            MobileTaskSuites.CATEGORY_FILES,
            MobileTaskSuites.CATEGORY_CALENDAR,
            MobileTaskSuites.CATEGORY_INFO,
            MobileTaskSuites.CATEGORY_NAVIGATION,
        )) {
            assertTrue("冒烟套件缺类别 $expected", expected in categories)
        }
    }

    @Test
    fun `按类别筛选`() {
        val messaging = MobileTaskSuites.byCategory(MobileTaskSuites.CATEGORY_MESSAGING)
        assertTrue(messaging.isNotEmpty())
        assertTrue(messaging.all { it.category == MobileTaskSuites.CATEGORY_MESSAGING })
    }

    @Test
    fun `条件任务判据包含必查与条件执行`() {
        // sys-battery-power-saver：必须先查电量，且不允许「关省电模式」
        val task = MobileTaskSuites.defaultSuite().first { it.id == "sys-battery-power-saver" }
        assertTrue(task.criteria.requiredCalls.any { it.tool == "battery_status" })
        assertTrue(task.criteria.forbiddenCalls.isNotEmpty())
        assertTrue(task.criteria.finalTextPatterns.isNotEmpty())
    }
}
