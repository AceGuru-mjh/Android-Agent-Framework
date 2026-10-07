package com.androidguru.agent.shell.control

import com.androidguru.agent.shell.audit.AuditLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 控制 API 协议自检 —— yl-ai `ApiSelfTest` 的框架移植（C 组缺口）。
 *
 * 与 yl-ai 的对照与取舍：
 * - yl-ai 通过 `LocalControlServer.dispatchForTest()` 在进程内直调协议分发器，
 *   不经网络；本类直接调用 [LocalControlServer.dispatch]，语义一致 ——
 *   自检聚焦「协议方法是否可用、审计是否闭环」，不依赖端口监听 / WS 握手 /
 *   令牌鉴权（网络层正确性由 [WsConnection] 与 [LocalControlServerTest] 覆盖）；
 * - yl-ai 会实际执行 `session.exec`（echo 一条命令）；本框架的 CONFIRM 级命令
 *   会走 [com.androidguru.agent.shell.policy.ApprovalGate.request] **挂起等待
 *   用户决策**，自检无法也不应该自动批准 —— 因此执行类与写文件类方法
 *   跳过并标注 [SKIP_NOTE]，由宿主（设置页 / 诊断入口）引导用户手动触发；
 * - 审计往返是自检的核心价值：写入一条 test 事件 → [AuditLog.recent] 能读回 →
 *   [AuditLog.exportJson] 结构含 `"events"` —— 证明「发生了什么都被记账」的
 *   安全承诺真实成立。
 *
 * @param server 已组装好的本地控制服务（无需 [LocalControlServer.start]）
 * @param audit  与 server 同源的审计日志（往返验证写入/读回/导出同一份）
 */
class ControlApiSelfTest(
    private val server: LocalControlServer,
    private val audit: AuditLog,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 逐个验证协议方法并做审计闭环检查。
     *
     * 挂起函数：内部只调用 [LocalControlServer.dispatch]（其本身挂起但
     * 对无副作用的方法立即返回），不会触发审批等待。
     */
    suspend fun run(): ControlSelfTestReport {
        val results = mutableListOf<CheckResult>()

        // ---- 1. server.info：协议分发 + 元信息 ----
        run {
            val (ok, result) = dispatchOk("server.info")
            val version = result.str("version")
            val port = result.str("port")
            results += CheckResult(
                method = "server.info",
                passed = ok && version != null,
                note = if (ok) "version=$version port=$port" else "响应异常",
            )
        }

        // ---- 2. session.list：会话清单可读 ----
        run {
            val (ok, result) = dispatchOk("session.list")
            val n = (result["sessions"] as? kotlinx.serialization.json.JsonArray)?.size ?: -1
            results += CheckResult(
                method = "session.list",
                passed = ok && n >= 0,
                note = if (ok) "返回 $n 个会话" else "响应异常",
            )
        }

        // ---- 3. approval.pending：审批状态可查询（无副作用） ----
        run {
            val (ok, result) = dispatchOk("approval.pending")
            val pending = result.str("pending") ?: "?"
            results += CheckResult(
                method = "approval.pending",
                passed = ok,
                note = "pending=$pending（当前无等待中的审批请求）",
            )
        }

        // ---- 4. 未知方法必须报错（协议负路径） ----
        run {
            val error = dispatchError("""{"id":"selftest","method":"no.such.method","params":{}}""")
            results += CheckResult(
                method = "no.such.method（应报错）",
                passed = error.contains("未知方法"),
                note = if (error.isNotEmpty()) "error=$error" else "未返回错误",
            )
        }

        // ---- 5. 非法 JSON 必须报错且不崩溃（协议健壮性） ----
        run {
            val error = dispatchError("not-a-json{{{")
            results += CheckResult(
                method = "非法 JSON（应报错）",
                passed = error.isNotEmpty(),
                note = if (error.isNotEmpty()) "error=$error" else "未返回错误",
            )
        }

        // ---- 6/7. 执行与写文件：涉及审批挂起，跳过 ----
        results += CheckResult(
            method = "session.exec",
            passed = true,
            note = SKIP_NOTE,
        )
        results += CheckResult(
            method = "fs.write",
            passed = true,
            note = SKIP_NOTE,
        )

        // ---- 8. 审计往返：写入 → 读回 → 导出 ----
        results += auditRoundtrip()

        return ControlSelfTestReport(results.toList(), allPassed = results.all { it.passed })
    }

    /** 审计闭环：test 事件落盘 → recent() 读回 → exportJson() 结构正确。 */
    private fun auditRoundtrip(): CheckResult {
        val marker = "audit-selftest-${System.nanoTime()}"
        return try {
            audit.record(
                AuditLog.TYPE_SETTING,
                AuditLog.Source.SYSTEM,
                linkedMapOf("what" to "api.selftest", "detail" to marker),
            )
            val readBack = audit.recent(limit = 50)
                .any { it.type == AuditLog.TYPE_SETTING && it.payload["detail"] == marker }
            val exported = audit.exportJson(limit = 100)
            // 结构要求：含 "events" 数组键（事件内容是否完整取决于实现，recent() 已验读写闭环）
            val exportOk = exported.contains("\"events\"")
            CheckResult(
                method = "audit.roundtrip",
                passed = readBack && exportOk,
                note = when {
                    readBack && exportOk -> "写入 → recent() 读回 → exportJson() 含 events，闭环成立"
                    !readBack -> "recent() 读不回刚写入的事件"
                    else -> "exportJson() 结构异常（缺 events）"
                },
            )
        } catch (t: Throwable) {
            CheckResult("audit.roundtrip", false, "异常：${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ---------------- 内部工具 ----------------

    /** 调用一个期望成功的方法，返回 (ok, result 对象)。 */
    private suspend fun dispatchOk(method: String): Pair<Boolean, JsonObject> {
        val resp = server.dispatch("""{"id":"selftest","method":"$method","params":{}}""")
        val obj = parse(resp)
        val ok = obj.bool("ok") == true
        val result = obj["result"] as? JsonObject ?: JsonObject(emptyMap())
        return ok to result
    }

    /** 调用一个期望失败的原始请求，返回 error 文案（无错误时返回空串）。 */
    private suspend fun dispatchError(rawRequest: String): String {
        val obj = parse(server.dispatch(rawRequest))
        return if (obj.bool("ok") == false) obj.str("error").orEmpty() else ""
    }

    private fun parse(response: String): JsonObject =
        runCatching { json.parseToJsonElement(response).jsonObject }.getOrDefault(JsonObject(emptyMap()))

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    companion object {
        /** 跳过标注前缀（宿主/测试可据此识别「未验证但不失败」的项）。 */
        const val SKIP_MARKER = "SKIPPED_NEEDS_APPROVAL"

        /** 跳过标注：执行/写文件会挂起等审批（yl-ai 自检同样不自动触发这两类）。 */
        const val SKIP_NOTE = "$SKIP_MARKER：该操作会挂起等待用户审批，自检不自动触发（请在终端/文件工具里手动验证）"
    }
}

/** 单项检查结果。跳过的项 [passed] 记 true（不算失败），由 [CheckResult.note] 显式标注。 */
@Serializable
data class CheckResult(
    val method: String,
    val passed: Boolean,
    val note: String,
)

/**
 * 自检报告。
 *
 * @param results 全部检查项（含跳过项）
 * @param allPassed 是否没有任何失败项（跳过不算失败）
 */
@Serializable
data class ControlSelfTestReport(
    val results: List<CheckResult>,
    val allPassed: Boolean,
) {
    /** 渲染为设置页 / 诊断弹窗可直接展示的文本（yl-ai ApiSelfTest 的字符串输出形态）。 */
    fun render(): String = buildString {
        appendLine("== 本地控制 API 自检 ==")
        results.forEach { r ->
            val mark = when {
                r.note.startsWith(ControlApiSelfTest.SKIP_MARKER) -> "⏭"
                r.passed -> "✓"
                else -> "✗"
            }
            appendLine("$mark ${r.method.padEnd(24)} ${r.note}")
        }
        appendLine(if (allPassed) "结论：全部通过" else "结论：存在失败项，请检查上方 ✗ 行")
    }
}
