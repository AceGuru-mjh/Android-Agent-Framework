package com.androidguru.agent.memory

import com.androidguru.agent.core.context.TokenEstimator

/**
 * 召回查询 —— 一次 [MemoryRecall.recall] 的全部参数。
 *
 * [text] 是召回信号（通常是当前用户输入 / 最近一条用户消息），
 * 留空时退化为纯「重要性 + 新近度 + 频率」排名（无关键词匹配项）。
 *
 * [minKeywordCoverage]：关键词覆盖率下限 —— [text] 非空且 > 0 时生效，
 * 用于「精确检索」场景（memory_search 工具）；自动召回用 0（宽容降级，
 * 无命中时仍按重要度 / 新近度注入）。
 */
data class MemoryQuery(
    val text: String? = null,
    /** 只召回这些类别（null = 全部）。 */
    val kinds: Set<MemoryKind>? = null,
    /** 只召回带任一标签的记忆（null = 全部）。 */
    val tags: Set<String>? = null,
    /** 最多返回条数。 */
    val limit: Int = 6,
    /** 注入 token 预算（按内容估算，超出即停止追加）。 */
    val maxTokens: Int = 500,
    /** 低于该分数的记忆不值得注入。 */
    val minScore: Double = 0.05,
    /** 关键词覆盖率下限（text 非空且 > 0 时生效；检索用，自动召回用 0）。 */
    val minKeywordCoverage: Double = 0.0,
)

/**
 * 记忆召回 —— 相关性评分 + 预算选择 + 访问回写。
 *
 * 评分四因子（可解释、可离线测试；语义向量检索留给宿主按需接入）：
 *
 * ```
 * score = 0.45 * importance/100          // 静态权重：写入时声明的重要度
 *       + 0.30 * recency                 // 指数衰减：2^(-ageDays / halfLifeDays)
 *       + 0.10 * frequency               // 对数饱和：ln(1+n)/ln(17)，16 次访问 ≈ 1.0
 *       + 0.15 * keywordCoverage         // 关键词覆盖：|query∩record| / |query|
 * ```
 *
 * 访问回写带**冷却**（[accessCooldownMs]）：注入器每轮求值，
 * 若每次命中都 +1 / 刷新时间戳，频率与新近度会被热循环快速灌满
 * （「越召回越新鲜」正反馈）。冷却间隔内的命中不回写，保持统计真实。
 *
 * token 预算：按 [TokenEstimator] 估算内容长度逐条累加，超预算即止 ——
 * 召回结果永远小于 [MemoryQuery.maxTokens]，不挤压工作记忆。
 */
class MemoryRecall(
    private val store: MemoryStore,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 新近度半衰期（天）。默认 14 天：两周前的记忆时间权重减半。 */
    private val halfLifeDays: Double = 14.0,
    /** 访问回写冷却（毫秒）；默认 1 小时。 */
    private val accessCooldownMs: Long = DEFAULT_ACCESS_COOLDOWN_MS,
    private val estimator: (String) -> Int = TokenEstimator::estimate,
) {

    /** 召回并按分数降序返回；命中结果回写访问统计（受冷却约束）。 */
    fun recall(query: MemoryQuery): List<MemoryRecord> {
        val now = clock()
        val candidates = store.all()
            .filter { r -> query.kinds == null || r.kind in query.kinds }
            .filter { r -> query.tags == null || r.tags.any { it in query.tags } }
            .let { list ->
                // 检索模式的关键词过滤（自动召回 minKeywordCoverage=0 时不过滤）
                if (query.text.isNullOrBlank() || query.minKeywordCoverage <= 0.0) {
                    list
                } else {
                    list.filter { keywordCoverage(it, query.text) >= query.minKeywordCoverage }
                }
            }

        val scored = candidates
            .map { it to score(it, query, now) }
            .filter { (_, s) -> s >= query.minScore }
            .sortedByDescending { (_, s) -> s }
            .take(query.limit.coerceAtLeast(1))

        val selected = mutableListOf<MemoryRecord>()
        var tokens = 0
        for ((record, _) in scored) {
            val cost = estimator(record.content)
            if (tokens + cost > query.maxTokens) break
            selected += record
            tokens += cost
        }

        writeBackAccess(selected, now)
        return selected
    }

    /** 单条评分（权重与公式见类注释）。 */
    fun score(record: MemoryRecord, query: MemoryQuery, nowMs: Long = clock()): Double {
        val importance = record.importance / 100.0

        val ageDays = ((nowMs - record.createdAtMs).coerceAtLeast(0)) / DAY_MS.toDouble()
        val recency = Math.pow(2.0, -ageDays / halfLifeDays)

        val frequency = kotlin.math.ln(1.0 + record.accessCount) / kotlin.math.ln(17.0)

        val coverage = keywordCoverage(record, query.text)

        return 0.45 * importance + 0.30 * recency + 0.10 * frequency.coerceIn(0.0, 1.0) + 0.15 * coverage
    }

    /** 关键词覆盖：记忆内容覆盖查询信号的比例（0..1；查询为空时 0）。 */
    private fun keywordCoverage(record: MemoryRecord, text: String?): Double {
        if (text.isNullOrBlank()) return 0.0
        val queryTokens = tokenize(text)
        if (queryTokens.isEmpty()) return 0.0
        val recordTokens = tokenize(record.content).toHashSet()
        val hit = queryTokens.count { it in recordTokens }
        return hit.toDouble() / queryTokens.size
    }

    /** 访问回写：首次访问必计；之后受冷却约束（防注入器每轮求值的热循环灌统计）。 */
    private fun writeBackAccess(selected: List<MemoryRecord>, now: Long) {
        for (record in selected) {
            if (record.accessCount > 0 && now - record.lastAccessedAtMs < accessCooldownMs) continue
            try {
                store.update(record.accessed(now))
            } catch (e: Exception) {
                // 回写是尽力而为：存储故障不阻断召回
            }
        }
    }

    /**
     * 分词（双语）：CJK 字符切 bigram，拉丁词小写化。
     * 既有 [TokenEstimator.isCjk] 判别逻辑的轻量复刻 —— 记忆模块不依赖其内部实现。
     */
    internal companion object {
        const val DAY_MS = 24 * 3600_000L
        const val DEFAULT_ACCESS_COOLDOWN_MS = 3600_000L

        fun tokenize(text: String): List<String> {
            val tokens = mutableListOf<String>()
            val cjk = StringBuilder()
            val latin = StringBuilder()

            fun flushCjk() {
                if (cjk.length >= 2) {
                    for (i in 0 until cjk.length - 1) tokens += cjk.substring(i, i + 2)
                } else if (cjk.isNotEmpty()) {
                    tokens += cjk.toString()
                }
                cjk.setLength(0)
            }

            fun flushLatin() {
                if (latin.isNotEmpty()) {
                    tokens += latin.toString().lowercase()
                    latin.setLength(0)
                }
            }

            for (ch in text) {
                when {
                    TokenEstimator.isCjk(ch) -> {
                        flushLatin()
                        cjk.append(ch)
                    }

                    ch.isLetterOrDigit() -> {
                        flushCjk()
                        latin.append(ch)
                    }

                    else -> {
                        flushCjk()
                        flushLatin()
                    }
                }
            }
            flushCjk()
            flushLatin()
            return tokens
        }
    }
}

/**
 * 召回结果的模型渲染（注入器与 memory_search 工具共用）。
 *
 * 无记录返回空串（调用方据此跳过注入，不产生空头衔）。
 */
fun renderMemories(records: List<MemoryRecord>): String {
    if (records.isEmpty()) return ""
    return buildString {
        for (r in records) {
            append("- ")
            append(r.renderForModel())
            if (r.tags.isNotEmpty()) append("  标签: ").append(r.tags.joinToString(","))
            append("\n")
        }
    }.trimEnd()
}
