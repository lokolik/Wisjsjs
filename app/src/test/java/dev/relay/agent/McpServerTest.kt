package dev.relay.agent

import dev.relay.agent.mcp.McpHandler
import dev.relay.agent.mcp.McpServer
import dev.relay.agent.tools.ToolCall
import dev.relay.agent.tools.ToolResult
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class McpServerTest {
    private val calls = mutableListOf<ToolCall>()
    private lateinit var server: McpServer

    @Before
    fun start() {
        val handler = McpHandler({ c -> calls += c; ToolResult(true, "ok:${c.tool}") })
        server = McpServer(0, "secret", handler, InetAddress.getLoopbackAddress())
        server.start()
    }

    @After
    fun stop() = server.stop()

    private fun post(body: String, token: String? = "secret", scheme: String = "Bearer"): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:${server.boundPort}/mcp").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json, text/event-stream")
        if (token != null) conn.setRequestProperty("Authorization", "$scheme $token")
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
        return code to text
    }

    @Test
    fun rejectsMissingToken() {
        assertEquals(401, post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", token = null).first)
        assertEquals(401, post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", token = "wrong").first)
    }

    @Test
    fun initializeListAndCall() {
        val (c1, init) = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""")
        assertEquals(200, c1)
        val initRes = JSONObject(init).getJSONObject("result")
        assertEquals("2025-06-18", initRes.getString("protocolVersion"))
        assertEquals("relay-phone", initRes.getJSONObject("serverInfo").getString("name"))

        assertEquals(202, post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").first)

        val tools = JSONObject(post("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""").second)
            .getJSONObject("result").getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertTrue(names.containsAll(listOf("mkdir", "list_dir", "shell")))
        val mkdir = (0 until tools.length()).map { tools.getJSONObject(it) }.first { it.getString("name") == "mkdir" }
        assertEquals("path", mkdir.getJSONObject("inputSchema").getJSONArray("required").getString(0))

        val call = JSONObject(post("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"mkdir","arguments":{"path":"~/Download/привет"}}}""").second)
        val res = call.getJSONObject("result")
        assertFalse(res.getBoolean("isError"))
        assertEquals("ok:mkdir", res.getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("~/Download/привет", calls.single().args.getString("path"))
    }

    @Test
    fun unknownMethodAndToolReturnErrors() {
        val m = JSONObject(post("""{"jsonrpc":"2.0","id":"a","method":"resources/list"}""").second)
        assertEquals(-32601, m.getJSONObject("error").getInt("code"))
        assertEquals("a", m.getString("id"))
        val t = JSONObject(post("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"rm_rf"}}""").second)
        assertEquals(-32602, t.getJSONObject("error").getInt("code"))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun handlerIgnoresNotifications() {
        assertNull(McpHandler({ ToolResult(true, "") }).handle(JSONObject("""{"jsonrpc":"2.0","method":"notifications/cancelled"}""")))
    }

    @Test
    fun bearerSchemeIsCaseInsensitive() {
        assertEquals(200, post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", scheme = "bearer").first)
        assertEquals(200, post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", scheme = "BEARER").first)
    }

    @Test
    fun repeatedRpcIdsGetDistinctCallIds() {
        // Two clients (or two sessions of one client) both start counting at id 1.
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"mkdir","arguments":{"path":"~/a"}}}"""
        post(body)
        post(body)
        assertEquals(2, calls.size)
        assertTrue(calls[0].id != calls[1].id)
        assertTrue(calls.all { it.id.startsWith("mcp-") })
    }
}
