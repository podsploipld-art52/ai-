package com.aiagent.android.agent

import java.net.HttpURLConnection
import java.net.URL

/**
 * Small synchronous HTTP fetcher used by the `http_fetch` tool. We deliberately keep this
 * away from the Ktor-based [com.aiagent.android.llm.LlmClient] so that the agent's network
 * calls don't share connection pools with the LLM client (otherwise a slow target server
 * could block model requests).
 *
 * The body is decoded as UTF-8 and capped at [maxBytes] characters (post-decode). If the
 * server doesn't tell us the encoding, we still fall back to UTF-8 — which is fine for
 * 99% of the modern web.
 */
object HttpFetch {

    data class Response(
        val statusCode: Int,
        val statusText: String,
        val headers: Map<String, String>,
        val body: String,
    )

    fun fetch(
        url: String,
        method: String = "GET",
        headers: Map<String, String>? = null,
        body: String? = null,
        maxBytes: Int = 65_536,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 20_000,
    ): Response {
        val parsed = URL(url)
        val connection = (parsed.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            // Reasonable defaults for being a polite agent.
            setRequestProperty("User-Agent", "ai-agent-android/0.2 (+https://github.com/podsploipld-art52/ai-)")
            setRequestProperty("Accept", "*/*")
            instanceFollowRedirects = true
            headers?.forEach { (k, v) -> setRequestProperty(k, v) }
            if (!body.isNullOrEmpty() && method in setOf("POST", "PUT", "PATCH", "DELETE")) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        try {
            val code = connection.responseCode
            val text = connection.responseMessage ?: ""
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            val raw = bytes.toString(Charsets.UTF_8)
            val truncated = if (raw.length > maxBytes) {
                raw.substring(0, maxBytes) + "\n…[truncated, full size=${raw.length} chars]"
            } else raw
            val responseHeaders = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { it.key!! }
                .mapValues { it.value.joinToString(", ") }
            return Response(code, text, responseHeaders, truncated)
        } finally {
            runCatching { connection.disconnect() }
        }
    }
}
