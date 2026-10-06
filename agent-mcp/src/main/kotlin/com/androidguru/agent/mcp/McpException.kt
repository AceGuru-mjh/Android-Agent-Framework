package com.androidguru.agent.mcp

/**
 * MCP 统一异常。区别于 [McpRemoteException]（远端显式 JSON-RPC error），
 * 本异常表示客户端侧的协议 / 生命周期问题（未握手 / 响应错配 / 分页环等）。
 */
class McpException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
