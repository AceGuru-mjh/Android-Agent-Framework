package com.androidguru.agent.tools

/**
 * 工具分类。
 *
 * 框架只保留少量核心分类；宿主特有的领域请归入 [UTILITY] / [SYSTEM] 并用
 * [ToolMetadata.tags] 表达细分语义 —— 避免封闭枚举把业务概念固化进框架。
 */
enum class ToolCategory {
    FILE, SHELL, NETWORK, DATA, UI, SYSTEM, UTILITY,
}

/** 风险等级：HIGH 风险工具默认需要会话级确认（见执行门控）。 */
enum class ToolRisk { LOW, MEDIUM, HIGH }

/**
 * MCP 对齐的能力注解（只作提示，不改变执行语义）。
 */
data class ToolAnnotations(
    /** 只读工具（无副作用）。 */
    val readOnlyHint: Boolean = false,
    /** 具有破坏性（删除 / 覆盖）。 */
    val destructiveHint: Boolean = false,
    /** 幂等：重复执行结果一致。 */
    val idempotentHint: Boolean = false,
    /** 开放世界：结果可能依赖外部环境。 */
    val openWorldHint: Boolean = false,
)

/** 工具元数据。 */
data class ToolMetadata(
    val id: String,
    val category: ToolCategory = ToolCategory.UTILITY,
    val risk: ToolRisk = ToolRisk.LOW,
    val annotations: ToolAnnotations = ToolAnnotations(),
    val tags: Set<String> = emptySet(),
) {
    companion object {

        private val prefixRules: List<Pair<String, ToolCategory>> = listOf(
            "file_" to ToolCategory.FILE,
            "read_" to ToolCategory.FILE,
            "write_" to ToolCategory.FILE,
            "list_" to ToolCategory.FILE,
            "search_" to ToolCategory.NETWORK,
            "fetch_" to ToolCategory.NETWORK,
            "http_" to ToolCategory.NETWORK,
            "shell_" to ToolCategory.SHELL,
            "exec_" to ToolCategory.SHELL,
            "run_" to ToolCategory.SHELL,
            "sql_" to ToolCategory.DATA,
            "db_" to ToolCategory.DATA,
            "json_" to ToolCategory.DATA,
            "mcp__" to ToolCategory.NETWORK,
        )

        private val riskyPrefixes = listOf("shell_", "exec_", "run_", "delete_", "write_", "remove_")

        /** 按 id 前缀族推断默认元数据（first-match-wins，兜底 UTILITY / LOW）。 */
        fun forId(id: String): ToolMetadata {
            val category = prefixRules.firstOrNull { (prefix, _) -> id.startsWith(prefix) }?.second
                ?: ToolCategory.UTILITY
            val risk = if (riskyPrefixes.any { id.startsWith(it) }) ToolRisk.MEDIUM else ToolRisk.LOW
            return ToolMetadata(id = id, category = category, risk = risk)
        }
    }
}
