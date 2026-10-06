package com.androidguru.agent.tools

/**
 * 工具错误码。`retryableWithBetterArgs = true` 表示换参数重试有望成功，
 * 模型读到错误提示后应调整策略而不是原样重试。
 */
enum class ToolErrorCode(val retryableWithBetterArgs: Boolean) {
    /** 参数不合法。 */
    VALIDATION(true),

    /** 目标不存在。 */
    NOT_FOUND(true),

    /** 权限不足 / 需要用户确认。 */
    PERMISSION(false),

    /** 执行超时。 */
    TIMEOUT(true),

    /** 触发限流。 */
    RATE_LIMITED(true),

    /** 依赖暂时不可用（网络断开、下游故障）。 */
    UNAVAILABLE(true),

    /** 工具内部错误。 */
    INTERNAL(false),

    /** 调用被取消。 */
    CANCELLED(false),

    /** 未分类错误。 */
    UNKNOWN(false),
}

/** 结构化错误描述。 */
data class ToolError(
    val code: ToolErrorCode,
    val message: String,
    /** 出错的参数名（可选）。 */
    val field: String? = null,
    /** 给模型的重试建议（可选）。 */
    val suggestion: String? = null,
)

/**
 * 结构化工具结果 —— 本框架把 [ToolResult] 作为一等返回类型，
 * 错误信息带错误码与建议，模型可据此换策略，而不是原样重试。
 */
data class ToolResult(
    val ok: Boolean,
    val content: String,
    val error: ToolError? = null,
    /** 可选的机器可读附加数据（JSON 字符串），供宿主程序消费，不直接进入提示词。 */
    val data: String? = null,
) {
    val isError: Boolean get() = !ok

    companion object {
        fun success(content: String, data: String? = null): ToolResult =
            ToolResult(ok = true, content = content, data = data)

        fun failure(
            message: String,
            code: ToolErrorCode = ToolErrorCode.INTERNAL,
            field: String? = null,
            suggestion: String? = null,
        ): ToolResult = ToolResult(
            ok = false,
            content = message,
            error = ToolError(code = code, message = message, field = field, suggestion = suggestion),
        )

        /** 快捷构造：参数校验失败。 */
        fun invalid(field: String, message: String, suggestion: String? = null): ToolResult =
            failure(message, ToolErrorCode.VALIDATION, field, suggestion)
    }
}
