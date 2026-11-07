// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.dns

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * 只监听 127.0.0.1 的 HTTP 代理：CONNECT 走字节隧道（不解密），
 * 普通 HTTP 请求按原样转发；域名解析优先走 DoH。
 */
class LocalHttpProxy(private val provider: () -> String) {

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    var running: Boolean = false
        private set

    fun start(): Boolean {
        if (running) return true
        return runCatching {
            val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            server = socket
            port = socket.localPort
            running = true
            Thread {
                while (running) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    pool.execute { handle(client) }
                }
            }.apply {
                name = "sii-doh-proxy"
                isDaemon = true
            }.start()
            true
        }.getOrDefault(false)
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        pool.shutdownNow()
    }

    private fun handle(client: Socket) {
        runCatching {
            client.use { socket ->
                socket.soTimeout = 15_000
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 3) return
                val method = parts[0]
                val target = parts[1]
                val headers = mutableListOf<String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    headers.add(line)
                }
                if (method.equals("CONNECT", true)) {
                    tunnel(target, socket, input, output, headers)
                } else if (target.startsWith("http://", true)) {
                    forward(method, target, headers, socket, input, output)
                } else {
                    writeResponse(output, 400, "Bad Request")
                }
            }
        }
    }

    private fun tunnel(
        target: String,
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        headers: List<String>
    ) {
        val hostPort = target.removePrefix("http://")
        val separator = hostPort.lastIndexOf(':')
        val host = if (separator > 0) hostPort.substring(0, separator) else hostPort
        val port = if (separator > 0) {
            hostPort.substring(separator + 1).toIntOrNull() ?: 443
        } else {
            443
        }
        val remote = open(host, port) ?: run {
            writeResponse(clientOut, 502, "Bad Gateway")
            return
        }
        remote.use { server ->
            val ok = runCatching {
                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                clientOut.flush()
                true
            }.getOrDefault(false)
            if (!ok) return
            val pumpUp = Thread {
                pump(clientIn, server.getOutputStream())
                runCatching { server.shutdownOutput() }
            }
            pumpUp.isDaemon = true
            pumpUp.start()
            pump(server.getInputStream(), clientOut)
            runCatching { client.shutdownOutput() }
            runCatching { pumpUp.join(500) }
        }
    }

    private fun forward(
        method: String,
        url: String,
        headers: List<String>,
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream
    ) {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: run {
            writeResponse(clientOut, 400, "Bad Request")
            return
        }
        val host = uri.host ?: run {
            writeResponse(clientOut, 400, "Bad Request")
            return
        }
        val port = if (uri.port > 0) uri.port else 80
        val remote = open(host, port) ?: run {
            writeResponse(clientOut, 502, "Bad Gateway")
            return
        }
        val path = buildString {
            append(uri.encodedPath?.takeIf { it.isNotEmpty() } ?: "/")
            uri.encodedQuery?.let { append('?').append(it) }
        }
        remote.use { server ->
            val out = server.getOutputStream()
            val builder = StringBuilder()
            builder.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            headers.forEach { line ->
                val lower = line.substringBefore(':').trim().lowercase()
                if (lower == "proxy-connection" || lower == "connection") return@forEach
                builder.append(line).append("\r\n")
            }
            builder.append("Connection: close\r\n\r\n")
            runCatching {
                out.write(builder.toString().toByteArray())
                out.flush()
            }.onFailure {
                writeResponse(clientOut, 502, "Bad Gateway")
                return
            }
            val hasBody = headers.any { line ->
                val lower = line.lowercase()
                (lower.startsWith("content-length:") &&
                    (lower.substringAfter(':').trim().toIntOrNull() ?: 0) > 0) ||
                    (lower.startsWith("transfer-encoding:") && lower.contains("chunked"))
            }
            val pumpDown = Thread { pump(server.getInputStream(), clientOut) }
            pumpDown.isDaemon = true
            pumpDown.start()
            if (hasBody) {
                pump(clientIn, out)
            }
            runCatching { pumpDown.join() }
        }
    }

    private fun open(host: String, port: Int): Socket? {
        val addresses = if (isLocalHost(host)) {
            runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
        } else {
            DohClient.resolve(host, provider())
        }
        for (address in addresses) {
            val socket = Socket()
            val connected = runCatching {
                socket.connect(InetSocketAddress(address, port), 10_000)
                socket.soTimeout = 15_000
                true
            }.getOrDefault(false)
            if (connected) return socket
            runCatching { socket.close() }
        }
        return null
    }

    /** 本地/内网域名直接交给系统解析，避免 DoH 解析不到。 */
    private fun isLocalHost(host: String): Boolean {
        val value = host.lowercase()
        if (value == "localhost" || value.endsWith(".local")) return true
        if (!value.contains('.')) return true
        if (value.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) {
            val parts = value.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size == 4) {
                return parts[0] == 10 ||
                    (parts[0] == 192 && parts[1] == 168) ||
                    (parts[0] == 172 && parts[1] in 16..31) ||
                    parts[0] == 127
            }
        }
        return false
    }

    private fun pump(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        runCatching {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val value = input.read()
            if (value < 0) return if (builder.isEmpty()) null else builder.toString()
            if (value == '\n'.code) break
            if (value != '\r'.code) builder.append(value.toChar())
            if (builder.length > 8192) return null
        }
        return builder.toString()
    }

    private fun writeResponse(output: OutputStream, code: Int, message: String) {
        runCatching {
            output.write(
                "HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .toByteArray()
            )
            output.flush()
        }
    }
}
