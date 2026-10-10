package com.androidguru.agent.tasks.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 父任务状态自动收拢测试（PR #26）：
 * - 子任务推进 → 父任务 PENDING → IN_PROGRESS；
 * - 子任务全部完成 → 父任务自动 DONE；
 * - 有失败 → 父任务 FAILED；
 * - 人工置位终态不被回退；
 * - 收拢可关闭（行为兼容开关）。
 */
class TaskPlanParentAggregationTest {

    private fun manager(autoAggregate: Boolean = true): TaskPlanManager =
        TaskPlanManager(InMemoryTaskPlanStore(), "agg", autoAggregateParent = autoAggregate)

    private fun twoPhasePlan(m: TaskPlanManager): TaskPlan = m.rewrite(
        goal = "目标",
        rawTasks = listOf(
            RawTask(id = "p1", title = "阶段一"),
            RawTask(id = "p1s1", title = "步骤1", parent = "p1"),
            RawTask(id = "p1s2", title = "步骤2", parent = "p1"),
            RawTask(id = "p2", title = "阶段二"),
            RawTask(id = "p2s1", title = "步骤3", parent = "p2"),
        ),
    )

    @Test
    fun `子任务开始推进时父任务自动进入进行中`() {
        val m = manager()
        twoPhasePlan(m)

        m.applyOps(listOf(TaskPlanManager.PlanOp.UpdateStatus("p1s1", TaskStatus.IN_PROGRESS, null)))

        assertEquals(TaskStatus.IN_PROGRESS, m.plan()!!.tasks.first { it.id == "p1" }.status)
        // 其他父任务不动
        assertEquals(TaskStatus.PENDING, m.plan()!!.tasks.first { it.id == "p2" }.status)
    }

    @Test
    fun `子任务全部完成时父任务自动完成`() {
        val m = manager()
        twoPhasePlan(m)

        m.applyOps(
            listOf(
                TaskPlanManager.PlanOp.UpdateStatus("p1s1", TaskStatus.DONE, "ok1"),
                TaskPlanManager.PlanOp.UpdateStatus("p1s2", TaskStatus.DONE, "ok2"),
            ),
        )

        assertEquals(TaskStatus.DONE, m.plan()!!.tasks.first { it.id == "p1" }.status)
    }

    @Test
    fun `部分跳过部分完成也算父任务完成`() {
        val m = manager()
        twoPhasePlan(m)

        m.applyOps(
            listOf(
                TaskPlanManager.PlanOp.UpdateStatus("p1s1", TaskStatus.DONE, null),
                TaskPlanManager.PlanOp.UpdateStatus("p1s2", TaskStatus.SKIPPED, "不需要"),
            ),
        )

        assertEquals(TaskStatus.DONE, m.plan()!!.tasks.first { it.id == "p1" }.status)
    }

    @Test
    fun `子任务全终态且有失败时父任务失败`() {
        val m = manager()
        twoPhasePlan(m)

        m.applyOps(
            listOf(
                TaskPlanManager.PlanOp.UpdateStatus("p1s1", TaskStatus.DONE, null),
                TaskPlanManager.PlanOp.UpdateStatus("p1s2", TaskStatus.FAILED, "卡住"),
            ),
        )

        assertEquals(TaskStatus.FAILED, m.plan()!!.tasks.first { it.id == "p1" }.status)
    }

    @Test
    fun `人工置位的 DONE 不被后续子任务状态回退`() {
        val m = manager()
        twoPhasePlan(m)

        // 模型显式把父任务标 DONE（提前收口）
        m.applyOps(listOf(TaskPlanManager.PlanOp.UpdateStatus("p1", TaskStatus.DONE, "人工收口")))
        // 之后子任务又被标 FAILED（迟到的失败）
        m.applyOps(listOf(TaskPlanManager.PlanOp.UpdateStatus("p1s2", TaskStatus.FAILED, "迟到")))

        assertEquals(TaskStatus.DONE, m.plan()!!.tasks.first { it.id == "p1" }.status)
    }

    @Test
    fun `叶子任务不受收拢影响`() {
        val m = manager()
        twoPhasePlan(m)

        m.applyOps(listOf(TaskPlanManager.PlanOp.UpdateStatus("p1s1", TaskStatus.DONE, "ok")))

        // p1s2 仍 PENDING（只收拢父层级）
        assertEquals(TaskStatus.PENDING, m.plan()!!.tasks.first { it.id == "p1s2" }.status)
    }

    @Test
    fun `关闭收拢保持旧语义`() {
        val m = manager(autoAggregate = false)
        twoPhasePlan(m)

        m.applyOps(
            listOf(
                TaskPlanManager.PlanOp.UpdateStatus("p1s1", TaskStatus.DONE, null),
                TaskPlanManager.PlanOp.UpdateStatus("p1s2", TaskStatus.DONE, null),
            ),
        )

        // 旧行为：父任务不自动推进
        assertEquals(TaskStatus.PENDING, m.plan()!!.tasks.first { it.id == "p1" }.status)
    }

    @Test
    fun `进度统计与收拢一致`() {
        val m = manager()
        twoPhasePlan(m)

        m.applyOps(
            listOf(
                TaskPlanManager.PlanOp.UpdateStatus("p1s1", TaskStatus.DONE, null),
                TaskPlanManager.PlanOp.UpdateStatus("p1s2", TaskStatus.DONE, null),
            ),
        )

        val progress = m.progress()!!
        assertEquals(3, progress.done) // p1s1 + p1s2 + 自动收拢的 p1
        assertTrue(progress.percent > 0)
        // 渲染里父任务显示 [x]
        assertTrue(m.renderForModel().contains("[x] p1 阶段一"))
    }
}
