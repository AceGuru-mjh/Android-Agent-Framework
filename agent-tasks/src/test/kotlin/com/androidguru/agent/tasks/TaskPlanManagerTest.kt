package com.androidguru.agent.tasks

import com.androidguru.agent.tasks.plan.FileTaskPlanStore
import com.androidguru.agent.tasks.plan.InMemoryTaskPlanStore
import com.androidguru.agent.tasks.plan.RawTask
import com.androidguru.agent.tasks.plan.TaskPlanManager
import com.androidguru.agent.tasks.plan.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 计划状态机测试：rewrite / patch 原子性 / 单一活跃纪律 / 进度 / 渲染 / 持久化。
 */
class TaskPlanManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun manager(store: InMemoryTaskPlanStore = InMemoryTaskPlanStore()) =
        TaskPlanManager(store, "s1") to store

    @Test
    fun `rewrite 创建计划并自动分配 id`() {
        val (m, _) = manager()
        val plan = m.rewrite(
            goal = "产出对比报告",
            rawTasks = listOf(
                RawTask(title = "收集资料"),
                RawTask(title = "整理数据"),
                RawTask(title = "撰写报告"),
            ),
        )
        assertEquals(listOf("t1", "t2", "t3"), plan.tasks.map { it.id })
        assertEquals(3, m.progress()!!.total)
        assertEquals(0, m.progress()!!.done)
        assertNull(m.progress()!!.currentTask)
        assertEquals("t1", m.progress()!!.nextTask?.id)
    }

    @Test
    fun `rewrite 空标题被拒绝且状态不变`() {
        val (m, _) = manager()
        try {
            m.rewrite("goal", listOf(RawTask(title = "  ")))
            throw AssertionError("应当抛 PlanOpException")
        } catch (e: TaskPlanManager.PlanOpException) {
            assertNull(m.plan()) // 原子性：验证失败不半写
        }
    }

    @Test
    fun `patch 更新状态与单一活跃纪律`() {
        val (m, _) = manager()
        m.rewrite("goal", listOf(RawTask(title = "a"), RawTask(title = "b"), RawTask(title = "c")))

        m.applyOps(
            listOf(
                TaskPlanManager.PlanOp.UpdateStatus("t1", TaskStatus.IN_PROGRESS, null),
                TaskPlanManager.PlanOp.UpdateStatus("t1", TaskStatus.DONE, "完成于第 3 轮"),
                TaskPlanManager.PlanOp.UpdateStatus("t2", TaskStatus.IN_PROGRESS, null),
                TaskPlanManager.PlanOp.UpdateStatus("t3", TaskStatus.IN_PROGRESS, null), // t2 应被自动回退 PENDING
            ),
        )

        val plan = m.plan()!!
        assertEquals(TaskStatus.DONE, plan.tasks[0].status)
        assertEquals(TaskStatus.PENDING, plan.tasks[1].status) // 单一活跃纪律回退
        assertEquals(TaskStatus.IN_PROGRESS, plan.tasks[2].status)
        assertEquals(1, m.progress()!!.done)
        assertEquals(1, m.progress()!!.inProgress)
        assertEquals("t3", m.progress()!!.currentTask?.id)
        assertTrue(plan.tasks[0].notes.contains("第 3 轮"))
    }

    @Test
    fun `patch 任一操作失败全部不生效（原子性）`() {
        val (m, _) = manager()
        m.rewrite("goal", listOf(RawTask(title = "a"), RawTask(title = "b")))

        try {
            m.applyOps(
                listOf(
                    TaskPlanManager.PlanOp.UpdateStatus("t1", TaskStatus.DONE, null),
                    TaskPlanManager.PlanOp.UpdateStatus("t99", TaskStatus.DONE, null), // 未知 id
                ),
            )
            throw AssertionError("应当抛 PlanOpException")
        } catch (e: TaskPlanManager.PlanOpException) {
            // 第一条也不生效
            assertEquals(TaskStatus.PENDING, m.plan()!!.tasks[0].status)
        }
    }

    @Test
    fun `patch insert 与 remove 连带子任务`() {
        val (m, _) = manager()
        m.rewrite("goal", listOf(RawTask(title = "调研"), RawTask(title = "写报告")))
        m.applyOps(
            listOf(
                TaskPlanManager.PlanOp.Insert(afterId = "t1", id = null, title = "补充实验", notes = null),
            ),
        )
        assertEquals(3, m.plan()!!.tasks.size)
        assertEquals("补充实验", m.plan()!!.tasks[1].title)
        // 新插入的 id 自动分配为 maxSeq+1
        assertEquals("t3", m.plan()!!.tasks[1].id)

        // remove 父任务连带子任务
        m.rewrite(
            "goal",
            listOf(
                RawTask(id = "phase1", title = "阶段一"),
                RawTask(id = "step1", title = "步骤 1", parent = "phase1"),
                RawTask(id = "step2", title = "步骤 2", parent = "phase1"),
                RawTask(id = "phase2", title = "阶段二"),
            ),
        )
        m.applyOps(listOf(TaskPlanManager.PlanOp.Remove("phase1")))
        assertEquals(listOf("phase2"), m.plan()!!.tasks.map { it.id })
    }

    @Test
    fun `进度百分比与 isAllDone`() {
        val (m, _) = manager()
        m.rewrite(
            "goal",
            listOf(
                RawTask(title = "a"),
                RawTask(title = "b"),
                RawTask(title = "c"),
                RawTask(title = "d"),
            ),
        )
        m.applyOps(
            listOf(
                TaskPlanManager.PlanOp.UpdateStatus("t1", TaskStatus.DONE, null),
                TaskPlanManager.PlanOp.UpdateStatus("t2", TaskStatus.SKIPPED, "不需要"),
            ),
        )
        val p = m.progress()!!
        assertEquals(1, p.done)
        assertEquals(33, p.percent) // 1 done / (4-1 skipped) = 33%
        assertEquals(1, p.skipped)
        assertTrue(!p.isAllDone)
    }

    @Test
    fun `渲染包含目标进度与纪律说明`() {
        val (m, _) = manager()
        m.rewrite("数据迁移", listOf(RawTask(title = "导出")))
        m.applyOps(listOf(TaskPlanManager.PlanOp.UpdateStatus("t1", TaskStatus.IN_PROGRESS, null)))

        val rendered = m.renderForModel()
        assertTrue(rendered.contains("数据迁移"))
        assertTrue(rendered.contains("[~] t1 导出"))
        assertTrue(rendered.contains("计划纪律"))
        assertTrue(rendered.contains("0/1"))
        assertTrue(rendered.contains("进行中 1"))
    }

    @Test
    fun `监听器收到变更通知`() {
        val (m, _) = manager()
        val seen = mutableListOf<String>()
        val unsubscribe = m.addListener { plan -> seen.add(plan.goal) }
        m.rewrite("goal-1", listOf(RawTask(title = "a")))
        unsubscribe()
        m.rewrite("goal-2", listOf(RawTask(title = "a")))
        assertEquals(listOf("goal-1"), seen) // 退订后不再收到
    }

    @Test
    fun `文件存储崩溃恢复`() {
        val dir = tmp.newFolder().toPath()
        val store = FileTaskPlanStore(dir)

        val m1 = TaskPlanManager(store, "session-42")
        m1.rewrite(
            "长目标",
            listOf(
                RawTask(title = "步骤一"),
                RawTask(title = "步骤二"),
            ),
        )
        m1.applyOps(
            listOf(
                TaskPlanManager.PlanOp.UpdateStatus("t1", TaskStatus.DONE, "验收通过"),
            ),
        )

        // 模拟进程重启：新 Manager 从文件恢复
        val m2 = TaskPlanManager(store, "session-42")
        val plan = m2.plan()
        assertNotNull(plan)
        assertEquals("长目标", plan!!.goal)
        assertEquals(TaskStatus.DONE, plan.tasks[0].status)
        assertEquals("验收通过", plan.tasks[0].notes)
        assertEquals(1, m2.progress()!!.done)

        // 删除后为空
        assertTrue(store.delete("session-42"))
        assertNull(TaskPlanManager(store, "session-42").plan())
    }

    @Test
    fun `文件存储会话 id 特殊字符被消毒`() {
        val dir = tmp.newFolder().toPath()
        val store = FileTaskPlanStore(dir)
        val m = TaskPlanManager(store, "../../evil/session")
        m.rewrite("goal", listOf(RawTask(title = "a")))
        // 目录内只应出现消毒后的文件名：无目录穿越、无点号特殊名
        val names = dir.toFile().list()!!.toList()
        assertEquals(1, names.size)
        assertTrue(names[0].startsWith("___")) // ../.. 被整体消毒
        assertTrue(!names[0].contains("/") && !names[0].contains(".."))
    }
}
