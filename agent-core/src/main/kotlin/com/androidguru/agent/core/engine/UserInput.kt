package com.androidguru.agent.core.engine

/** 一次用户输入（文本 + 可选图片）。 */
data class UserInput(
    val text: String,
    val images: List<com.androidguru.agent.llm.ImageContent> = emptyList(),
) {
    constructor(text: String) : this(text, emptyList())
}
