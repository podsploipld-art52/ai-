package com.aiagent.android.web

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Tiny HTTP server bound to 127.0.0.1 that serves files out of the agent's projects folder
 * (`Documents/AI-Agent/projects/<project>/...` with fallback to app-private storage). The
 * point is to give the user a real `http://` URL the system browser can open — modern
 * Chrome blocks `file://` and `content://` URLs from third-party apps, so we have no choice
 * but to host the page locally.
 *
 * Singleton to avoid leaking sockets across runs. Started on demand from
 * [com.aiagent.android.agent.Agent.executeTool] when the agent calls
 * `open_project_in_browser`.
 *
 * Security notes:
 *  - Bound to loopback only — not reachable from other devices on the LAN.
 *  - Path traversal (`..`) is rejected.
 *  - Random port (chosen by the OS) so other apps can't easily target a known port.
 *  - Only serves files inside the projects root (and other agent-folder candidates).
 */
class LocalProjectServer private constructor(context: Context) {

    private val context = context.applicationContext

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var thread: Thread? = null
    @Volatile var port: Int = 0
        private set

    @Synchronized
    fun start(): Int {
        val existing = serverSocket
        if (existing != null && !existing.isClosed && port != 0) return port
        val sock = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        sock.reuseAddress = true
        port = sock.localPort
        serverSocket = sock
        thread = Thread({ acceptLoop(sock) }, "agent-http-server").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "Local project server listening on 127.0.0.1:$port")
        return port
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        thread?.interrupt()
        serverSocket = null
        thread = null
        port = 0
    }

    private fun acceptLoop(sock: ServerSocket) {
        while (!Thread.currentThread().isInterrupted && !sock.isClosed) {
            val client = try {
                sock.accept()
            } catch (_: IOException) {
                break
            }
            Thread({ runCatching { handle(client) } }, "agent-http-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { c ->
            c.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.US_ASCII))
            val requestLine = reader.readLine() ?: return
            // Drain headers.
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                writeStatus(c.getOutputStream(), 405, "Method Not Allowed", "Only GET is supported")
                return
            }
            val rawPath = parts[1].substringBefore('?')
            val decoded = runCatching { URLDecoder.decode(rawPath, "UTF-8") }.getOrDefault(rawPath)
            // Special "list projects" page so the user can land on / and see what's there.
            if (decoded == "/" || decoded.isEmpty()) {
                writeIndex(c.getOutputStream())
                return
            }
            val target = resolveSafe(decoded)
            if (target == null) {
                writeStatus(c.getOutputStream(), 403, "Forbidden", "Path выходит за пределы папки проектов")
                return
            }
            if (target.isDirectory) {
                // Try index.html / index.htm
                val candidates = listOf("index.html", "index.htm").map { File(target, it) }
                val existing = candidates.firstOrNull { it.exists() && it.isFile }
                if (existing != null) {
                    writeFile(c.getOutputStream(), existing)
                } else {
                    writeDirListing(c.getOutputStream(), target, decoded)
                }
                return
            }
            if (!target.exists() || !target.isFile) {
                writeStatus(c.getOutputStream(), 404, "Not Found", "no such file: ${target.absolutePath}")
                return
            }
            writeFile(c.getOutputStream(), target)
        }
    }

    /** Resolves an HTTP path to a file under one of the agent-folder roots, blocking traversal. */
    private fun resolveSafe(httpPath: String): File? {
        val rel = httpPath.trimStart('/')
        if (rel.contains("..") || rel.startsWith("/")) return null
        for (root in roots()) {
            val candidate = File(root, rel).absoluteFile
            // Ensure candidate is inside `root`.
            val rootAbs = root.absoluteFile.absolutePath
            if (candidate.absolutePath.startsWith(rootAbs)) {
                return candidate
            }
        }
        return null
    }

    private fun roots(): List<File> {
        val publicDocs = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOCUMENTS,
        )
        return listOfNotNull(
            File(publicDocs, "AI-Agent/projects"),
            context.getExternalFilesDir(null)?.let { File(it, "projects") },
            File(context.filesDir, "projects"),
        ).filter { runCatching { it.mkdirs() }.getOrDefault(false) || it.exists() }
    }

    private fun writeIndex(out: OutputStream) {
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta charset=utf-8><title>AI-Agent projects</title></head><body>")
        sb.append("<h1>AI-Agent: проекты</h1>")
        for (root in roots()) {
            sb.append("<h3>${root.absolutePath}</h3><ul>")
            val children = root.listFiles()?.sortedBy { it.name } ?: emptyList()
            if (children.isEmpty()) {
                sb.append("<li><i>(пусто)</i></li>")
            } else {
                for (c in children) {
                    if (c.isDirectory) {
                        sb.append("<li><a href=\"/${c.name}/\">${c.name}/</a></li>")
                    } else {
                        sb.append("<li><a href=\"/${c.name}\">${c.name}</a></li>")
                    }
                }
            }
            sb.append("</ul>")
        }
        sb.append("</body></html>")
        writeBody(out, 200, "OK", "text/html; charset=utf-8", sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun writeDirListing(out: OutputStream, dir: File, httpPath: String) {
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta charset=utf-8><title>$httpPath</title></head><body>")
        sb.append("<h1>$httpPath</h1><ul>")
        val children = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
        if (children.isEmpty()) sb.append("<li><i>(пусто)</i></li>")
        for (c in children) {
            val href = if (c.isDirectory) "${c.name}/" else c.name
            sb.append("<li><a href=\"$href\">$href</a></li>")
        }
        sb.append("</ul></body></html>")
        writeBody(out, 200, "OK", "text/html; charset=utf-8", sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun writeFile(out: OutputStream, file: File) {
        val mime = mimeFor(file.name)
        val bytes = file.readBytes()
        writeBody(out, 200, "OK", mime, bytes)
    }

    private fun writeStatus(out: OutputStream, code: Int, status: String, msg: String) {
        writeBody(out, code, status, "text/plain; charset=utf-8", msg.toByteArray(Charsets.UTF_8))
    }

    private fun writeBody(
        out: OutputStream,
        code: Int,
        status: String,
        contentType: String,
        body: ByteArray,
    ) {
        val header = buildString {
            append("HTTP/1.1 $code $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "txt", "md", "log" -> "text/plain; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "wasm" -> "application/wasm"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        "ogg" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    companion object {
        private const val TAG = "LocalProjectServer"

        @Volatile private var instance: LocalProjectServer? = null

        fun get(context: Context): LocalProjectServer {
            return instance ?: synchronized(this) {
                instance ?: LocalProjectServer(context).also { instance = it }
            }
        }
    }
}
