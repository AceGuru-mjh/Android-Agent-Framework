package com.androidguru.agent.workflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 工作流 JSON 编解码 —— 模型类型保持纯 data class（仓库既有风格：手工编解码，
 * 对齐 FileTaskPlanStore / FileMemoryStore）。
 *
 * 节点多态经 `kind` 字段：`tool` | `agent` | `human` | `switch` | `setvar` | `delay`。
 * 解码纪律：**宽容但不静默** —— 未知 kind / 缺关键字段抛 [WorkflowCodecException]
 * （调用方决定拒绝入库），未知**可选**字段忽略（前向兼容）。
 */
object WorkflowCodec {

    class WorkflowCodecException(message: String) : Exception(message)

    // ------------------------------------------------------------------
    // 定义
    // ------------------------------------------------------------------

    fun encodeDefinition(def: WorkflowDefinition): JsonObject = JsonObject(
        buildMap {
            put("schemaVersion", JsonPrimitive(def.schemaVersion))
            put("id", JsonPrimitive(def.id))
            put("name", JsonPrimitive(def.name))
            put("description", JsonPrimitive(def.description))
            put("maxSteps", JsonPrimitive(def.maxSteps))
            put("params", JsonArray(def.params.map { encodeParamSpec(it) }))
            put("nodes", JsonArray(def.nodes.map { encodeNode(it) }))
            put("edges", JsonArray(def.edges.map { encodeEdge(it) }))
        },
    )

    fun decodeDefinition(obj: JsonObject): WorkflowDefinition {
        val schemaVersion = obj.intOrNull("schemaVersion") ?: WorkflowDefinition.SCHEMA_VERSION
        if (schemaVersion > WorkflowDefinition.SCHEMA_VERSION) {
            throw WorkflowCodecException("定义 schemaVersion=$schemaVersion 高于当前支持的 ${WorkflowDefinition.SCHEMA_VERSION}，请升级框架")
        }
        val id = obj.string("id") ?: throw WorkflowCodecException("定义缺少 id")
        val name = obj.string("name") ?: throw WorkflowCodecException("定义缺少 name")
        val nodes = (obj["nodes"] as? JsonArray)?.mapNotNull { el ->
            (el as? JsonObject)?.let { decodeNode(it) }
        } ?: throw WorkflowCodecException("定义缺少 nodes 数组")
        val edges = (obj["edges"] as? JsonArray)?.mapNotNull { el ->
            (el as? JsonObject)?.let { decodeEdge(it) }
        } ?: throw WorkflowCodecException("定义缺少 edges 数组")
        val params = (obj["params"] as? JsonArray)?.mapNotNull { el ->
            (el as? JsonObject)?.let { decodeParamSpec(it) }
        } ?: emptyList()
        return WorkflowDefinition(
            id = id,
            name = name,
            description = obj.string("description") ?: "",
            schemaVersion = schemaVersion,
            params = params,
            nodes = nodes,
            edges = edges,
            maxSteps = obj.intOrNull("maxSteps") ?: 100,
        )
    }

    fun definitionToJson(def: WorkflowDefinition): String = encodeDefinition(def).toString()

    fun definitionFromJson(text: String): WorkflowDefinition = try {
        decodeDefinition(Json.parseToJsonElement(text).jsonObject)
    } catch (e: WorkflowCodecException) {
        throw e
    } catch (e: Exception) {
        throw WorkflowCodecException("不是合法 JSON: ${e.message}")
    }

    private fun encodeParamSpec(p: ParamSpec): JsonObject = JsonObject(
        buildMap {
            put("name", JsonPrimitive(p.name))
            put("description", JsonPrimitive(p.description))
            put("required", JsonPrimitive(p.required))
            if (p.defaultValue != null) put("default", JsonPrimitive(p.defaultValue))
        },
    )

    private fun decodeParamSpec(obj: JsonObject): ParamSpec = ParamSpec(
        name = obj.string("name") ?: throw WorkflowCodecException("参数缺少 name"),
        description = obj.string("description") ?: "",
        required = obj["required"]?.jsonPrimitive?.booleanOrNull ?: false,
        defaultValue = obj.string("default"),
    )

    private fun encodeNode(n: WorkflowNode): JsonObject = JsonObject(
        buildMap {
            put("id", JsonPrimitive(n.id))
            put("description", JsonPrimitive(n.description))
            n.retry?.let { put("retry", encodeRetry(it)) }
            n.timeoutMs?.let { put("timeoutMs", JsonPrimitive(it)) }
            put("onError", JsonPrimitive(n.onError.name))
            when (n) {
                is WorkflowNode.Tool -> {
                    put("kind", JsonPrimitive("tool"))
                    put("toolId", JsonPrimitive(n.toolId))
                    put("arguments", n.arguments)
                }
                is WorkflowNode.Agent -> {
                    put("kind", JsonPrimitive("agent"))
                    put("prompt", JsonPrimitive(n.prompt))
                    n.systemPrompt?.let { put("systemPrompt", JsonPrimitive(it)) }
                    n.temperature?.let { put("temperature", JsonPrimitive(it)) }
                    n.maxTokens?.let { put("maxTokens", JsonPrimitive(it)) }
                }
                is WorkflowNode.Human -> {
                    put("kind", JsonPrimitive("human"))
                    put("prompt", JsonPrimitive(n.prompt))
                }
                is WorkflowNode.Switch -> {
                    put("kind", JsonPrimitive("switch"))
                    put("expression", JsonPrimitive(n.expression))
                    put("cases", JsonArray(n.cases.map {
                        JsonObject(mapOf("value" to JsonPrimitive(it.value), "branch" to JsonPrimitive(it.branch)))
                    }))
                    n.defaultBranch?.let { put("defaultBranch", JsonPrimitive(it)) }
                }
                is WorkflowNode.SetVar -> {
                    put("kind", JsonPrimitive("setvar"))
                    put("assignments", JsonObject(n.assignments.mapValues { JsonPrimitive(it.value) }))
                }
                is WorkflowNode.Delay -> {
                    put("kind", JsonPrimitive("delay"))
                    put("delayMs", JsonPrimitive(n.delayMs))
                }
            }
        },
    )

    private fun decodeNode(obj: JsonObject): WorkflowNode {
        val id = obj.string("id") ?: throw WorkflowCodecException("节点缺少 id")
        val description = obj.string("description") ?: ""
        val retry = (obj["retry"] as? JsonObject)?.let { decodeRetry(it) }
        val timeoutMs = obj["timeoutMs"]?.jsonPrimitive?.longOrNull
        val onError = runCatching { NodeOnError.valueOf(obj.string("onError") ?: "FAIL_RUN") }
            .getOrDefault(NodeOnError.FAIL_RUN)
        return when (val kind = obj.string("kind") ?: throw WorkflowCodecException("节点 $id 缺少 kind")) {
            "tool" -> WorkflowNode.Tool(
                id = id,
                toolId = obj.string("toolId") ?: throw WorkflowCodecException("tool 节点 $id 缺少 toolId"),
                arguments = obj["arguments"] as? JsonObject ?: JsonObject(emptyMap()),
                description = description,
                retry = retry,
                timeoutMs = timeoutMs,
                onError = onError,
            )
            "agent" -> WorkflowNode.Agent(
                id = id,
                prompt = obj.string("prompt") ?: throw WorkflowCodecException("agent 节点 $id 缺少 prompt"),
                systemPrompt = obj.string("systemPrompt"),
                temperature = obj["temperature"]?.jsonPrimitive?.doubleOrNull,
                maxTokens = obj["maxTokens"]?.jsonPrimitive?.longOrNull?.let { it.toInt().coerceAtLeast(1) },
                description = description,
                retry = retry,
                timeoutMs = timeoutMs,
                onError = onError,
            )
            "human" -> WorkflowNode.Human(
                id = id,
                prompt = obj.string("prompt") ?: throw WorkflowCodecException("human 节点 $id 缺少 prompt"),
                description = description,
                retry = retry,
                timeoutMs = timeoutMs,
                onError = onError,
            )
            "switch" -> {
                val cases = (obj["cases"] as? JsonArray)?.mapNotNull { el ->
                    val c = el as? JsonObject ?: return@mapNotNull null
                    val value = c.string("value") ?: return@mapNotNull null
                    val branch = c.string("branch") ?: return@mapNotNull null
                    WorkflowNode.Switch.Case(value = value, branch = branch)
                } ?: emptyList()
                WorkflowNode.Switch(
                    id = id,
                    expression = obj.string("expression") ?: throw WorkflowCodecException("switch 节点 $id 缺少 expression"),
                    cases = cases,
                    defaultBranch = obj.string("defaultBranch"),
                    description = description,
                    retry = retry,
                    timeoutMs = timeoutMs,
                    onError = onError,
                )
            }
            "setvar" -> WorkflowNode.SetVar(
                id = id,
                assignments = (obj["assignments"] as? JsonObject)?.mapNotNull { (k, v) ->
                    val s = (v as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    k to s
                }?.toMap() ?: emptyMap(),
                description = description,
                retry = retry,
                timeoutMs = timeoutMs,
                onError = onError,
            )
            "delay" -> WorkflowNode.Delay(
                id = id,
                delayMs = obj["delayMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                description = description,
                retry = retry,
                timeoutMs = timeoutMs,
                onError = onError,
            )
            else -> throw WorkflowCodecException("节点 $id 的 kind「$kind」未知")
        }
    }

    private fun encodeRetry(r: RetryPolicy): JsonObject = JsonObject(
        buildMap {
            put("maxAttempts", JsonPrimitive(r.maxAttempts))
            put("initialDelayMs", JsonPrimitive(r.initialDelayMs))
            put("backoffFactor", JsonPrimitive(r.backoffFactor))
            put("maxDelayMs", JsonPrimitive(r.maxDelayMs))
            put("jitter", JsonPrimitive(r.jitter))
        },
    )

    private fun decodeRetry(obj: JsonObject): RetryPolicy = RetryPolicy(
        maxAttempts = obj.intOrNull("maxAttempts") ?: 3,
        initialDelayMs = obj["initialDelayMs"]?.jsonPrimitive?.longOrNull ?: 500,
        backoffFactor = obj["backoffFactor"]?.jsonPrimitive?.doubleOrNull ?: 2.0,
        maxDelayMs = obj["maxDelayMs"]?.jsonPrimitive?.longOrNull ?: 60_000,
        jitter = obj["jitter"]?.jsonPrimitive?.booleanOrNull ?: true,
    )

    private fun encodeEdge(e: WorkflowEdge): JsonObject = JsonObject(
        buildMap {
            put("from", JsonPrimitive(e.from))
            put("to", JsonPrimitive(e.to))
            e.branch?.let { put("branch", JsonPrimitive(it)) }
        },
    )

    private fun decodeEdge(obj: JsonObject): WorkflowEdge = WorkflowEdge(
        from = obj.string("from") ?: throw WorkflowCodecException("边缺少 from"),
        to = obj.string("to") ?: throw WorkflowCodecException("边缺少 to"),
        branch = obj.string("branch"),
    )

    // ------------------------------------------------------------------
    // 运行态
    // ------------------------------------------------------------------

    fun encodeRun(run: WorkflowRunState): JsonObject = JsonObject(
        buildMap {
            put("runId", JsonPrimitive(run.runId))
            put("definitionId", JsonPrimitive(run.definitionId))
            put("definition", encodeDefinition(run.definition))
            put("status", JsonPrimitive(run.status.name))
            put("params", run.params)
            put("vars", run.vars)
            put("nodeStates", JsonObject(run.nodeStates.mapValues { encodeNodeState(it.value) }))
            put("stepCounter", JsonPrimitive(run.stepCounter))
            run.waitingNodeId?.let { put("waitingNodeId", JsonPrimitive(it)) }
            put("createdAtMs", JsonPrimitive(run.createdAtMs))
            run.startedAtMs?.let { put("startedAtMs", JsonPrimitive(it)) }
            run.finishedAtMs?.let { put("finishedAtMs", JsonPrimitive(it)) }
            run.message?.let { put("message", JsonPrimitive(it)) }
        },
    )

    fun decodeRun(obj: JsonObject): WorkflowRunState {
        val statusName = obj.string("status") ?: throw WorkflowCodecException("运行态缺少 status")
        val status = runCatching { RunStatus.valueOf(statusName) }.getOrThrow()
        val definitionObj = obj["definition"] as? JsonObject
            ?: throw WorkflowCodecException("运行态缺少 definition 快照")
        val nodeStates = (obj["nodeStates"] as? JsonObject)?.mapNotNull { (k, v) ->
            val n = v as? JsonObject ?: return@mapNotNull null
            k to decodeNodeState(n)
        }?.toMap() ?: emptyMap()
        return WorkflowRunState(
            runId = obj.string("runId") ?: throw WorkflowCodecException("运行态缺少 runId"),
            definitionId = obj.string("definitionId") ?: throw WorkflowCodecException("运行态缺少 definitionId"),
            definition = decodeDefinition(definitionObj),
            status = status,
            params = obj["params"] as? JsonObject ?: JsonObject(emptyMap()),
            vars = obj["vars"] as? JsonObject ?: JsonObject(emptyMap()),
            nodeStates = nodeStates,
            stepCounter = obj.intOrNull("stepCounter") ?: 0,
            waitingNodeId = obj.string("waitingNodeId"),
            createdAtMs = obj["createdAtMs"]?.jsonPrimitive?.longOrNull ?: 0L,
            startedAtMs = obj["startedAtMs"]?.jsonPrimitive?.longOrNull,
            finishedAtMs = obj["finishedAtMs"]?.jsonPrimitive?.longOrNull,
            message = obj.string("message"),
        )
    }

    private fun encodeNodeState(n: NodeRunState): JsonObject = JsonObject(
        buildMap {
            put("status", JsonPrimitive(n.status.name))
            put("attempt", JsonPrimitive(n.attempt))
            n.output?.let { put("output", it) }
            n.branch?.let { put("branch", JsonPrimitive(it)) }
            n.errorText?.let { put("errorText", JsonPrimitive(it)) }
            n.finishedStep?.let { put("finishedStep", JsonPrimitive(it)) }
            n.startedAtMs?.let { put("startedAtMs", JsonPrimitive(it)) }
            n.finishedAtMs?.let { put("finishedAtMs", JsonPrimitive(it)) }
        },
    )

    private fun decodeNodeState(obj: JsonObject): NodeRunState {
        val status = runCatching { NodeStatus.valueOf(obj.string("status") ?: "PENDING") }
            .getOrDefault(NodeStatus.PENDING)
        return NodeRunState(
            status = status,
            attempt = obj.intOrNull("attempt") ?: 0,
            output = obj["output"] ?: null,
            branch = obj.string("branch"),
            errorText = obj.string("errorText"),
            finishedStep = obj.intOrNull("finishedStep"),
            startedAtMs = obj["startedAtMs"]?.jsonPrimitive?.longOrNull,
            finishedAtMs = obj["finishedAtMs"]?.jsonPrimitive?.longOrNull,
        )
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }

    private fun JsonObject.intOrNull(key: String): Int? =
        this[key]?.jsonPrimitive?.longOrNull?.let { it.toInt() }

    /** [JsonNull] 规避：内容为字面 "null" 的 primitive 视为缺失。 */
    fun safeString(el: JsonElement?): String? =
        (el as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }
}
