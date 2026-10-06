package com.androidguru.agent.llm

/**
 * LLM 异常类型化 —— 上层据此精确分类（可重试 / 需降级 / 终态失败）。
 */
sealed class LlmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {

    /** HTTP 状态错误。4xx 除 408/429 外一般不可重试。 */
    class Http(val statusCode: Int, val body: String? = null) :
        LlmException("LLM HTTP $statusCode: ${body?.take(300) ?: "(no body)"}")

    /** 网络层失败（连接 / IO）。 */
    class Network(cause: Throwable) : LlmException("LLM 网络错误: ${cause.message}", cause)

    /** 响应为空（无 choices 且无 usage）。 */
    data object EmptyResponse : LlmException("LLM 返回空响应")

    /** 响应解析失败。 */
    class Parse(message: String, cause: Throwable? = null) : LlmException("LLM 响应解析失败: $message", cause)

    /** 请求被取消。 */
    class Cancelled(cause: Throwable? = null) : LlmException("LLM 请求已取消", cause)

    companion object {
        /** 瞬时错误判定：可退避重试。 */
        fun isTransient(e: Throwable): Boolean = when (e) {
            is Network -> true
            is Http -> e.statusCode in RETRIABLE_STATUS
            else -> false
        }

        private val RETRIABLE_STATUS = setOf(408, 429, 500, 502, 503, 504)
    }
}
