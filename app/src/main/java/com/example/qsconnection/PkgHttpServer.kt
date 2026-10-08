package com.example.qsconnection

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Servidor HTTP mínimo que sirve un PKG desde el teléfono hacia la PS4.
 *
 * La PS4 (BGFT / Remote Package Installer / etaHEN) descarga el paquete con
 * peticiones GET que pueden incluir "Range", así que este servidor soporta
 * 200 (archivo completo) y 206 (parcial), keep-alive y avisa con callbacks
 * del progreso real de bytes servidos.
 */
class PkgHttpServer(
    private val onProgress: (bytesSent: Long, totalBytes: Long) -> Unit = { _, _ -> },
    private val onComplete: () -> Unit = {},
    private val onClientConnected: () -> Unit = {}
) {
    companion object {
        const val DEFAULT_PORT = 9898
        private const val MAX_HEADER_LINE = 8192

        /** IP local alcanzable desde la PS4 (misma red). */
        fun localIpAddress(targetHost: String?): String? {
            if (!targetHost.isNullOrBlank()) {
                try {
                    DatagramSocket().use { s ->
                        s.connect(InetAddress.getByName(targetHost), 9)
                        val address = s.localAddress?.hostAddress
                        if (!address.isNullOrBlank() && !address.startsWith("0.") && address != "127.0.0.1") {
                            return address
                        }
                    }
                } catch (_: Exception) {
                }
            }

            try {
                NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { networkInterface ->
                    if (!networkInterface.isUp || networkInterface.isLoopback) return@forEach
                    networkInterface.inetAddresses?.toList()?.forEach { address ->
                        if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                            return address.hostAddress
                        }
                    }
                }
            } catch (_: Exception) {
            }

            return null
        }
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>)

    private var context: Context? = null
    private var uri: Uri? = null
    private var contentLength = 0L
    private var fileName = "paquete.pkg"

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val sockets = ArrayList<Socket>()

    @Volatile
    var boundPort: Int = DEFAULT_PORT
        private set

    @Volatile
    private var maxOffset = 0L

    @Volatile
    var clientConnected = false
        private set

    @Volatile
    private var completed = false

    val isCompleted: Boolean
        get() = completed

    /** Offset máximo servido hasta ahora (para detectar descargas estancadas). */
    val progressBytes: Long
        get() = maxOffset

    fun start(
        context: Context,
        uri: Uri,
        totalBytes: Long,
        fileName: String,
        preferredPort: Int = DEFAULT_PORT
    ): Boolean {
        if (running.get()) stop()

        this.context = context.applicationContext
        this.uri = uri
        this.contentLength = totalBytes
        this.fileName = fileName.replace("\"", "")
        this.maxOffset = 0L
        this.clientConnected = false
        this.completed = false

        var socket: ServerSocket? = null
        try {
            socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(preferredPort))
        } catch (_: Exception) {
            try {
                socket?.close()
                socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(0))
            } catch (_: Exception) {
                return false
            }
        }

        serverSocket = socket
        boundPort = socket.localPort
        running.set(true)

        Thread({
            acceptLoop()
        }, "PkgHttpAccept").apply {
            isDaemon = true
            start()
        }

        return true
    }

    fun stop() {
        running.set(false)

        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null

        synchronized(sockets) {
            sockets.forEach { socket ->
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
            sockets.clear()
        }
    }

    private fun acceptLoop() {
        while (running.get()) {
            val server = serverSocket ?: break
            try {
                val client = server.accept()
                client.tcpNoDelay = true
                synchronized(sockets) { sockets.add(client) }

                Thread({
                    handleClient(client)
                }, "PkgHttpClient").apply {
                    isDaemon = true
                    start()
                }
            } catch (_: Exception) {
                if (!running.get()) break
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 60_000
            val input = socket.getInputStream().buffered()
            val output = socket.getOutputStream()

            while (running.get()) {
                val request = readRequest(input) ?: break
                val keepAlive = serve(request, output)
                if (!keepAlive) break
            }
        } catch (_: Exception) {
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            synchronized(sockets) { sockets.remove(socket) }
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val line = readLine(input) ?: return null
        if (line.isBlank()) return null

        val parts = line.split(" ")
        if (parts.size < 3) return null

        val headers = HashMap<String, String>()
        while (true) {
            val header = readLine(input) ?: return null
            if (header.isEmpty()) break
            val separator = header.indexOf(':')
            if (separator > 0) {
                headers[header.substring(0, separator).trim().lowercase()] =
                    header.substring(separator + 1).trim()
            }
        }

        return Request(parts[0], parts[1], headers)
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (builder.length <= MAX_HEADER_LINE) {
            val value = input.read()
            if (value == -1) return if (builder.isEmpty()) null else builder.toString()
            if (value == '\n'.code) break
            if (value != '\r'.code) builder.append(value.toChar())
        }
        return builder.toString()
    }

    private fun serve(request: Request, output: java.io.OutputStream): Boolean {
        val keepAliveHeader = request.headers["connection"]
        val wantsKeepAlive = keepAliveHeader == null || !keepAliveHeader.contains("close", true)

        if (!request.path.startsWith("/pkg")) {
            val body = "Not Found".toByteArray()
            output.write(
                ("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()
            )
            output.write(body)
            output.flush()
            return false
        }

        if (request.method != "GET" && request.method != "HEAD") {
            output.write(
                "HTTP/1.1 405 Method Not Allowed\r\nAllow: GET, HEAD\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
            )
            output.flush()
            return false
        }

        val total = contentLength
        if (total <= 0) {
            output.write(
                "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
            )
            output.flush()
            return false
        }

        var start = 0L
        var end = total - 1
        var partial = false

        val range = request.headers["range"]
        if (!range.isNullOrBlank() && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").split(",").first().trim()
            val dash = spec.indexOf('-')
            if (dash >= 0) {
                val first = spec.substring(0, dash).trim()
                val last = spec.substring(dash + 1).trim()
                try {
                    if (first.isNotEmpty()) {
                        start = first.toLong()
                        if (last.isNotEmpty()) end = last.toLong()
                    } else if (last.isNotEmpty()) {
                        val suffix = last.toLong()
                        start = (total - suffix).coerceAtLeast(0)
                        end = total - 1
                    }
                } catch (_: NumberFormatException) {
                    start = 0
                    end = total - 1
                }
                partial = true
            }
        }

        if (start >= total || start > end) {
            output.write(
                ("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()
            )
            output.flush()
            return false
        }

        end = end.coerceAtMost(total - 1)
        val length = end - start + 1

        if (!clientConnected) {
            clientConnected = true
            try {
                onClientConnected()
            } catch (_: Exception) {
            }
        }

        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(if (partial) "206 Partial Content" else "200 OK").append("\r\n")
        head.append("Content-Type: application/octet-stream\r\n")
        head.append("Content-Length: ").append(length).append("\r\n")
        head.append("Accept-Ranges: bytes\r\n")
        head.append("Content-Disposition: attachment; filename=\"").append(fileName).append("\"\r\n")
        if (partial || start > 0 || length != total) {
            head.append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(total).append("\r\n")
        }
        head.append(if (wantsKeepAlive) "Connection: keep-alive\r\nKeep-Alive: timeout=30, max=10\r\n" else "Connection: close\r\n")
        head.append("Server: qsconnection\r\n\r\n")

        val headerBytes = head.toString().toByteArray(Charsets.US_ASCII)
        output.write(headerBytes)
        output.flush()

        if (request.method == "HEAD") {
            return wantsKeepAlive
        }

        val stream = context?.contentResolver?.openInputStream(uri!!)
            ?: throw IllegalStateException("No se pudo abrir el PKG")

        try {
            skipFully(stream, start)

            val buffer = ByteArray(512 * 1024)
            var sent = 0L
            var lastNotify = System.currentTimeMillis()

            while (sent < length && running.get()) {
                val wanted = minOf(buffer.size.toLong(), length - sent).toInt()
                val read = stream.read(buffer, 0, wanted)
                if (read <= 0) break

                output.write(buffer, 0, read)
                sent += read

                val absolute = start + sent
                if (absolute > maxOffset) maxOffset = absolute

                val now = System.currentTimeMillis()
                if (now - lastNotify >= 250) {
                    lastNotify = now
                    try {
                        onProgress(maxOffset, total)
                    } catch (_: Exception) {
                    }
                }
            }

            output.flush()

            try {
                onProgress(maxOffset, total)
            } catch (_: Exception) {
            }

            if (maxOffset >= total && !completed) {
                completed = true
                try {
                    onComplete()
                } catch (_: Exception) {
                }
            }
        } finally {
            try {
                stream.close()
            } catch (_: Exception) {
            }
        }

        return wantsKeepAlive && running.get()
    }

    private fun skipFully(stream: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            if (stream.read() == -1) break
            remaining--
        }
    }
}
