package dev.relay.agent.mcp

import dev.relay.agent.tools.Risk
import dev.relay.agent.tools.ToolCall
import dev.relay.agent.tools.ToolExecutor
import dev.relay.agent.tools.ToolResult
import dev.relay.agent.tools.ToolSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** JSON-RPC side of the Model Context Protocol: exposes the device tools to any MCP client. */
class McpHandler(
    private val callTool: (ToolCall) -> ToolResult,
    // attach_file only makes sense inside a Relay chat.
    private val specs: List<ToolSpec> = ToolExecutor.SPECS.filter { it.name != "attach_file" },
    private val serverName: String = "relay-phone",
    private val serverVersion: String = "0.1.0",
) {
    fun handle(msg: JSONObject): JSONObject? {
        // Notifications (no id) never get a response.
        if (!msg.has("id")) return null
        val id = msg.get("id")
        val params = msg.optJSONObject("params") ?: JSONObject()
        return when (val method = msg.optString("method")) {
            "initialize" -> {
                val requested = params.optString("protocolVersion")
                val version = if (requested in SUPPORTED_VERSIONS) requested else SUPPORTED_VERSIONS.first()
                result(
                    id,
                    JSONObject()
                        .put("protocolVersion", version)
                        .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                        .put("serverInfo", JSONObject().put("name", serverName).put("version", serverVersion))
                        .put("instructions", "Инструменты управляют Android-устройством пользователя. ~ = общая память, загрузки — ~/Download."),
                )
            }
            "ping" -> result(id, JSONObject())
            "tools/list" -> result(id, JSONObject().put("tools", toolsJson()))
            "tools/call" -> {
                val name = params.optString("name")
                if (specs.none { it.name == name }) return error(id, -32602, "Unknown tool: $name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                // JSON-RPC ids repeat across clients and sessions; the UI and approvals need a unique call id.
                val r = callTool(ToolCall("mcp-" + UUID.randomUUID().toString().take(8), name, args))
                result(
                    id,
                    JSONObject()
                        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", r.output)))
                        .put("isError", !r.ok),
                )
            }
            else -> error(id, -32601, "Method not found: $method")
        }
    }

    fun toolsJson(): JSONArray = JSONArray().also { arr ->
        for (s in specs) {
            val props = JSONObject()
            val required = JSONArray()
            for (p in s.params) {
                props.put(p.name, JSONObject().put("type", p.type).put("description", p.description))
                if (p.required) required.put(p.name)
            }
            arr.put(
                JSONObject()
                    .put("name", s.name)
                    .put("description", s.description)
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", props).put("required", required))
                    .put(
                        "annotations",
                        JSONObject()
                            .put("readOnlyHint", s.risk == Risk.READ)
                            .put("destructiveHint", s.risk == Risk.DANGER),
                    ),
            )
        }
    }

    companion object {
        val SUPPORTED_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

        fun result(id: Any, result: JSONObject): JSONObject =
            JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)

        fun error(id: Any?, code: Int, message: String): JSONObject =
            JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
                .put("error", JSONObject().put("code", code).put("message", message))
    }
}

/**
 * Minimal Streamable-HTTP MCP endpoint (`POST /mcp`, JSON responses, no SSE) with bearer-token auth.
 * Dependency-free so it runs both on Android and in JVM tests.
 */
class McpServer(
    private val port: Int,
    private val token: String,
    private val handler: McpHandler,
    private val bindAddress: InetAddress? = null,
) {
    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null

    val boundPort: Int get() = server?.localPort ?: -1
    val isRunning: Boolean get() = server?.isClosed == false

    fun start() {
        val s = ServerSocket(port, 50, bindAddress)
        val p = Executors.newCachedThreadPool { r -> Thread(r, "mcp-conn").apply { isDaemon = true } }
        server = s
        pool = p
        Thread({
            while (!s.isClosed) {
                val conn = try {
                    s.accept()
                } catch (e: Exception) {
                    break
                }
                p.execute { serve(conn) }
            }
        }, "mcp-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { server?.close() }
        pool?.shutdownNow()
        server = null
        pool = null
    }

    private class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: ByteArray)
    private class Response(val status: Int, val body: String = "", val contentType: String = "application/json", val extra: Map<String, String> = emptyMap())

    private fun serve(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 30_000
                val req = readRequest(BufferedInputStream(s.getInputStream())) ?: return
                write(s.getOutputStream(), route(req))
            } catch (_: Exception) {
            }
        }
    }

    private fun route(req: Request): Response {
        if (req.method == "OPTIONS") return Response(204)
        if (req.path != "/mcp") return Response(404, """{"error":"not found"}""")
        val header = req.headers["authorization"]?.trim()
        // The auth scheme name is case-insensitive (RFC 9110), so "bearer x" must work too.
        val auth = header?.let { if (it.startsWith("bearer ", ignoreCase = true)) it.substring(7).trim() else it } ?: req.query["token"]
        if (auth != token) {
            return Response(401, """{"error":"unauthorized"}""", extra = mapOf("WWW-Authenticate" to "Bearer"))
        }
        if (req.method != "POST") return Response(405, "", extra = mapOf("Allow" to "POST"))
        val text = String(req.body, Charsets.UTF_8).trim()
        return try {
            if (text.startsWith("[")) {
                val arr = JSONArray(text)
                val out = JSONArray()
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { handler.handle(it) }?.let(out::put)
                if (out.length() == 0) Response(202) else Response(200, out.toString())
            } else {
                handler.handle(JSONObject(text))?.let { Response(200, it.toString()) } ?: Response(202)
            }
        } catch (e: Exception) {
            Response(400, McpHandler.error(null, -32700, "Parse error: ${e.message}").toString())
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var matched = 0
        val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (matched < 4) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            matched = if (b.toByte() == terminator[matched]) matched + 1 else if (b.toByte() == terminator[0]) 1 else 0
            if (head.size() > 64 * 1024) return null
        }
        val lines = String(head.toByteArray(), Charsets.ISO_8859_1).split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > 8 * 1024 * 1024) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val target = parts[1]
        val qIdx = target.indexOf('?')
        val path = if (qIdx >= 0) target.substring(0, qIdx) else target
        val query = if (qIdx >= 0) {
            target.substring(qIdx + 1).split('&').mapNotNull {
                val kv = it.split('=', limit = 2)
                if (kv.size == 2) URLDecoder.decode(kv[0], "UTF-8") to URLDecoder.decode(kv[1], "UTF-8") else null
            }.toMap()
        } else {
            emptyMap()
        }
        return Request(parts[0].uppercase(), path, query, headers, body)
    }

    private fun write(out: OutputStream, r: Response) {
        val body = r.body.toByteArray(Charsets.UTF_8)
        val reason = when (r.status) {
            200 -> "OK"; 202 -> "Accepted"; 204 -> "No Content"; 400 -> "Bad Request"
            401 -> "Unauthorized"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Status"
        }
        val sb = StringBuilder("HTTP/1.1 ${r.status} $reason\r\n")
        if (body.isNotEmpty()) sb.append("Content-Type: ${r.contentType}; charset=utf-8\r\n")
        sb.append("Content-Length: ${body.size}\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Headers: authorization, content-type, mcp-protocol-version, mcp-session-id\r\n")
        sb.append("Access-Control-Allow-Methods: POST, OPTIONS\r\n")
        r.extra.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }
}
