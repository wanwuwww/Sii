package com.sii.browser.core.mcp

import com.sii.browser.data.Settings
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 本地 MCP 服务端（JSON-RPC 2.0 over HTTP）。
 *
 * 同时支持两种传输：
 * - Streamable HTTP：POST /mcp 直接返回 JSON；
 * - 旧版 HTTP+SSE：GET /sse 建立事件流，POST /message?sessionId= 发送请求。
 *
 * 默认只绑定回环地址；令牌必须由设置页生成，未生成时拒绝所有请求。
 */
class McpServer(
    private val settingsProvider: () -> Settings,
    private val hostProvider: () -> McpHost?
) {

    data class Config(val port: Int, val lan: Boolean, val token: String)

    data class AuditEntry(
        val time: Long,
        val tool: String,
        val ok: Boolean,
        val durationMs: Long,
        val client: String
    )

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val workers = Executors.newCachedThreadPool()
    private val sessions = ConcurrentHashMap.newKeySet<String>()
    private val sseChannels = ConcurrentHashMap<String, SseChannel>()
    private val audit = ArrayDeque<AuditEntry>()
    private val auditLock = Any()

    @Volatile
    private var config: Config? = null

    @Volatile
    var lastError: String? = null
        private set

    private val tools: McpTools = McpTools(settingsProvider(), hostProvider)

    val isRunning: Boolean get() = running.get()

    val port: Int get() = config?.port ?: 0

    val lanEnabled: Boolean get() = config?.lan == true

    fun currentConfig(): Config? = config

    fun start(newConfig: Config): Boolean {
        stop()
        lastError = null
        val token = newConfig.token
        if (token.isBlank()) {
            lastError = "token missing"
            return false
        }
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            val address = if (newConfig.lan) null else InetAddress.getByName("127.0.0.1")
            socket.bind(InetSocketAddress(address, newConfig.port), 50)
            config = newConfig
            serverSocket = socket
            running.set(true)
            acceptThread = Thread({ acceptLoop(socket) }, "sii-mcp-accept").apply {
                isDaemon = true
                start()
            }
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            runCatching { serverSocket?.close() }
            serverSocket = null
            running.set(false)
            false
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        sseChannels.values.forEach { channel ->
            runCatching { channel.close() }
        }
        sseChannels.clear()
        sessions.clear()
        config = null
    }

    fun endpoint(): String =
        "http://127.0.0.1:${config?.port ?: 0}/mcp"

    fun auditSnapshot(): List<AuditEntry> = synchronized(auditLock) { audit.toList() }

    fun enabledToolCount(): Int = tools.enabledCount()

    fun totalToolCount(): Int = tools.totalCount()

    fun clearAudit() = synchronized(auditLock) { audit.clear() }

    /** 本机 IPv4 地址，用于局域网接入提示。 */
    fun localIpv4(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get() && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: SocketException) {
                break
            } catch (e: Exception) {
                break
            }
            workers.execute {
                runCatching { handle(client) }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            try {
                socket.soTimeout = 15000
                val input = BufferedInputStream(socket.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) {
                    sendJson(socket, 400, jsonError(null, -32600, "bad request"))
                    return
                }
                val method = parts[0].uppercase()
                val target = parts[1]
                val headers = readHeaders(input)
                val body = readBody(input, headers)

                if (method == "OPTIONS") {
                    sendEmpty(socket, 204)
                    return
                }
                val path = target.substringBefore('?')
                val rawQuery = target.substringAfter('?', "")
                val client = runCatching { socket.inetAddress?.hostAddress }.getOrNull().orEmpty()
                if (!authorized(headers, rawQuery)) {
                    sendJson(socket, 401, jsonError(null, -32001, "unauthorized"))
                    return
                }

                when {
                    method == "GET" && (path == "/sse" || path == "/mcp") ->
                        serveSse(socket, announceEndpoint = path == "/sse")
                    method == "GET" -> sendJson(
                        socket,
                        200,
                        JSONObject()
                            .put("name", "sii-mcp")
                            .put("endpoint", "/mcp")
                            .put("protocol", "2025-06-18")
                    )
                    method == "DELETE" -> {
                        val session = headers["mcp-session-id"]
                        if (session != null) sessions.remove(session)
                        sendEmpty(socket, 204)
                    }
                    method == "POST" && (path == "/mcp" || path == "/") ->
                        handlePost(
                            socket, body, null, client,
                            headers["accept"].orEmpty()
                        )
                    method == "POST" && path == "/message" -> {
                        val session = queryParam(rawQuery, "sessionId")
                        val channel = session?.let { sseChannels[it] }
                        if (session == null || channel == null) {
                            sendJson(socket, 404, jsonError(null, -32002, "unknown session"))
                            return
                        }
                        val outcome = processBody(body, session, client)
                        outcome.payload?.let { payload ->
                            channel.send("event: message\ndata: $payload\n\n")
                        }
                        sendEmpty(socket, 202)
                    }
                    else -> sendJson(socket, 404, jsonError(null, -32601, "not found"))
                }
            } catch (e: Exception) {
                runCatching {
                    sendJson(socket, 500, jsonError(null, -32603, e.message ?: "server error"))
                }
            }
        }
    }

    private fun handlePost(
        socket: Socket,
        body: ByteArray,
        existingSession: String?,
        client: String,
        accept: String
    ) {
        val outcome = processBody(body, existingSession, client)
        val payload = outcome.payload
        if (payload == null) {
            sendEmpty(socket, 202)
            return
        }
        if (accept.contains("text/event-stream") && !accept.contains("application/json")) {
            val frame = "event: message\ndata: $payload\n\n"
            writeResponse(
                socket,
                200,
                "text/event-stream; charset=utf-8",
                frame.toByteArray(Charsets.UTF_8),
                outcome.sessionId
            )
        } else {
            sendJson(socket, 200, payload, outcome.sessionId)
        }
    }

    private fun serveSse(socket: Socket, announceEndpoint: Boolean) {
        socket.soTimeout = 0
        val session = UUID.randomUUID().toString()
        val channel = SseChannel(socket.getOutputStream())
        sseChannels[session] = channel
        sessions.add(session)
        try {
            if (announceEndpoint) {
                channel.send("event: endpoint\ndata: /message?sessionId=$session\n\n")
            } else {
                channel.send(": connected\n\n")
            }
            while (running.get() && !socket.isClosed) {
                Thread.sleep(20000L)
                channel.send(": ping\n\n")
            }
        } catch (_: Exception) {
            // 客户端断开
        } finally {
            sseChannels.remove(session)
            runCatching { channel.close() }
        }
    }

    private data class Outcome(val payload: String?, val sessionId: String?)

    private fun processBody(body: ByteArray, existingSession: String?, client: String): Outcome {
        if (body.isEmpty()) return Outcome(jsonError(null, -32700, "parse error"), existingSession)
        val text = String(body, Charsets.UTF_8)
        val payload = runCatching { JSONTokener(text).nextValue() }.getOrNull()
            ?: return Outcome(jsonError(null, -32700, "parse error"), existingSession)
        return runBlocking {
            when (payload) {
                is JSONObject -> handleRpc(payload, existingSession, client)
                is JSONArray -> {
                    val responses = JSONArray()
                    var session = existingSession
                    for (index in 0 until payload.length()) {
                        val item = payload.optJSONObject(index) ?: continue
                        val result = handleRpc(item, session, client)
                        result.sessionId?.let { session = it }
                        result.payload?.let { responses.put(JSONTokener(it).nextValue()) }
                    }
                    if (responses.length() == 0) Outcome(null, session)
                    else Outcome(responses.toString(), session)
                }
                else -> Outcome(jsonError(null, -32600, "invalid request"), existingSession)
            }
        }
    }

    private suspend fun handleRpc(root: JSONObject, session: String?, client: String): Outcome {
        val id = if (root.has("id")) root.opt("id") else null
        val method = root.optString("method")
        if (id == null) {
            // 通知（notifications/initialized 等）不回包。
            return Outcome(null, session)
        }
        val params = root.optJSONObject("params") ?: JSONObject()
        if (method == "initialize") {
            val requested = params.optString("protocolVersion")
            val version = if (requested in SUPPORTED_VERSIONS) requested else SUPPORTED_VERSIONS.first()
            val newSession = session ?: UUID.randomUUID().toString()
            sessions.add(newSession)
            val initialized = JSONObject()
                .put("protocolVersion", version)
                .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                .put("serverInfo", JSONObject().put("name", "sii-browser").put("version", "1.0.0"))
            return Outcome(ok(id, initialized), newSession)
        }
        val result: JSONObject? = when (method) {
            "ping" -> JSONObject()
            "tools/list" -> JSONObject().put("tools", JSONArray(tools.availableTools()))
            "tools/call" -> {
                val name = params.optString("name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                val started = System.currentTimeMillis()
                val output = runCatching {
                    withTimeout(20000L) { tools.call(name, args) }
                }.getOrElse {
                    ToolResult("tool failed: ${it.message ?: it.javaClass.simpleName}", true)
                }
                recordAudit(name, !output.isError, System.currentTimeMillis() - started, client)
                JSONObject()
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject().put("type", "text").put("text", output.text)
                        )
                    )
                    .put("isError", output.isError)
            }
            "resources/list", "prompts/list" -> JSONObject()
            else -> null
        }
        if (result == null) {
            return if (method.isBlank()) {
                Outcome(jsonError(id, -32600, "invalid request"), session)
            } else {
                Outcome(jsonError(id, -32601, "method not found"), session)
            }
        }
        return Outcome(ok(id, result), session)
    }

    private fun ok(id: Any?, result: JSONObject): String =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString()

    private fun jsonError(id: Any?, code: Int, message: String): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))
            .toString()

    private fun recordAudit(tool: String, ok: Boolean, durationMs: Long, client: String) {
        synchronized(auditLock) {
            audit.addFirst(
                AuditEntry(
                    time = System.currentTimeMillis(),
                    tool = tool,
                    ok = ok,
                    durationMs = durationMs,
                    client = client
                )
            )
            while (audit.size > 100) audit.removeLast()
        }
    }

    private fun authorized(headers: Map<String, String>, rawQuery: String): Boolean {
        val expected = config?.token ?: return false
        if (expected.isEmpty()) return false
        val header = headers["authorization"]
        val bearer = if (header != null && header.startsWith("Bearer ", ignoreCase = true)) {
            header.substring(7).trim()
        } else {
            null
        }
        val fromQuery = queryParam(rawQuery, "token")
        return matches(expected, bearer) || matches(expected, fromQuery)
    }

    private fun matches(expected: String, given: String?): Boolean {
        if (given.isNullOrEmpty()) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            given.toByteArray(Charsets.UTF_8)
        )
    }

    private fun queryParam(rawQuery: String, name: String): String? {
        if (rawQuery.isBlank()) return null
        return rawQuery.split('&').firstNotNullOfOrNull { part ->
            val key = part.substringBefore('=')
            if (key != name) return@firstNotNullOfOrNull null
            runCatching {
                URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            }.getOrNull()
        }
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val value = input.read()
            if (value == -1) return if (builder.isEmpty()) null else builder.toString()
            if (value == '\n'.code) break
            if (value != '\r'.code) builder.append(value.toChar())
            if (builder.length > 8192) return null
        }
        return builder.toString()
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        var total = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            total += line.length
            if (total > 32768) break
            val index = line.indexOf(':')
            if (index <= 0) continue
            headers[line.substring(0, index).trim().lowercase()] =
                line.substring(index + 1).trim()
        }
        return headers
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
        val transferEncoding = headers["transfer-encoding"]?.lowercase()
        if (transferEncoding?.contains("chunked") == true) {
            return readChunked(input)
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0) return ByteArray(0)
        val size = minOf(length, MAX_BODY)
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = input.read(buffer, read, size - read)
            if (count < 0) break
            read += count
        }
        return buffer.copyOf(read)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        while (true) {
            val line = readLine(input) ?: break
            val size = line.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size <= 0) break
            var remaining = size
            val buffer = ByteArray(4096)
            while (remaining > 0 && output.size() < MAX_BODY) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                output.write(buffer, 0, count)
                remaining -= count
            }
            readLine(input)
            if (output.size() >= MAX_BODY) break
        }
        return output.toByteArray()
    }

    private fun sendEmpty(socket: Socket, status: Int) {
        writeResponse(socket, status, null, ByteArray(0), null)
    }

    private fun sendJson(socket: Socket, status: Int, payload: Any, session: String? = null) {
        val text = payload.toString()
        writeResponse(
            socket,
            status,
            "application/json; charset=utf-8",
            text.toByteArray(Charsets.UTF_8),
            session
        )
    }

    private fun writeResponse(
        socket: Socket,
        status: Int,
        contentType: String?,
        body: ByteArray,
        session: String?
    ) {
        val output = socket.getOutputStream()
        val builder = StringBuilder()
        builder.append("HTTP/1.1 ").append(status).append(' ')
            .append(reason(status)).append("\r\n")
        if (contentType != null) builder.append("Content-Type: ").append(contentType).append("\r\n")
        builder.append("Content-Length: ").append(body.size).append("\r\n")
        builder.append("Connection: close\r\n")
        builder.append("Access-Control-Allow-Origin: *\r\n")
        builder.append(
            "Access-Control-Allow-Headers: Authorization, Content-Type, Accept, " +
                "Mcp-Session-Id, MCP-Protocol-Version\r\n"
        )
        builder.append("Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n")
        builder.append("Access-Control-Expose-Headers: Mcp-Session-Id\r\n")
        if (session != null) builder.append("Mcp-Session-Id: ").append(session).append("\r\n")
        builder.append("\r\n")
        output.write(builder.toString().toByteArray(Charsets.ISO_8859_1))
        if (body.isNotEmpty()) output.write(body)
        output.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "Internal Server Error"
    }

    private class SseChannel(private val output: OutputStream) {
        private val lock = Any()

        fun send(text: String) = synchronized(lock) {
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
        }

        fun close() = runCatching {
            synchronized(lock) { output.close() }
        }
    }

    private companion object {
        const val MAX_BODY = 1024 * 1024
        val SUPPORTED_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
    }
}
