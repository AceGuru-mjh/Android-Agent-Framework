package com.androidguru.agent.mcp

import com.androidguru.agent.mcp.protocol.JsonRpc
import com.androidguru.agent.mcp.protocol.McpRemoteException
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonRpcTest {

    @Test
    fun `request 序列化结构正确`() {
        val req = JsonRpc.request(1L, "tools/list", kotlinx.serialization.json.buildJsonObject { })
        assertEquals("2.0", (req["jsonrpc"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("1", (req["id"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("tools/list", (req["method"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `notification 无 id 字段`() {
        val notice = JsonRpc.notification("notifications/initialized")
        assertNull(notice["id"])
    }

    @Test
    fun `解析请求与通知`() {
        val call = JsonRpc.parseIncoming("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"x"}}""")
        assertNotNull(call)
        assertTrue(call is JsonRpc.Incoming.Call)
        assertEquals("tools/call", (call as JsonRpc.Incoming.Call).method)
        assertEquals("7", (call.id as kotlinx.serialization.json.JsonPrimitive).content)

        val notice = JsonRpc.parseIncoming("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertTrue(notice is JsonRpc.Incoming.Notice)
    }

    @Test
    fun `宽容解析 - jsonrpc 缺失不拒收`() {
        val incoming = JsonRpc.parseIncoming("""{"id":1,"method":"ping"}""")
        assertNotNull(incoming)
    }

    @Test
    fun `宽容解析 - 非法 JSON 返回 null 而非抛异常`() {
        assertNull(JsonRpc.parseIncoming("not json"))
        assertNull(JsonRpc.parseIncoming("""{"no_method": 1}"""))
    }

    @Test
    fun `resultOf 在 error 帧时抛 McpRemoteException`() {
        val resp = JsonRpc.errorResponse(kotlinx.serialization.json.JsonPrimitive(1), JsonRpc.METHOD_NOT_FOUND, "no such method")
        val e = try {
            JsonRpc.resultOf(resp)
            null
        } catch (ex: McpRemoteException) {
            ex
        }
        assertEquals(-32601, e?.code)
    }

    @Test
    fun `roundtrip - successResponse 可被 parseIncoming 对端理解`() {
        // 服务器响应 → 客户端 resultOf
        val resp = JsonRpc.successResponse(kotlinx.serialization.json.JsonPrimitive(3), kotlinx.serialization.json.buildJsonObject {
            put("ok", true)
        })
        val result = JsonRpc.resultOf(resp)
        assertTrue((result as kotlinx.serialization.json.JsonObject)["ok"]!!.let {
            (it as kotlinx.serialization.json.JsonPrimitive).content == "true"
        })
    }
}
