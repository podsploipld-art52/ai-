package com.aiagent.android.llm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/** Thin OpenAI-compatible chat-completions client. */
@OptIn(ExperimentalSerializationApi::class)
class LlmClient(
    private val baseUrl: String,
    private val apiKey: String,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
    }

    suspend fun chat(request: ChatRequest): ChatResponse {
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        var lastError: String = ""
        repeat(MAX_RETRIES) { attempt ->
            val response: HttpResponse = client.post(url) {
                contentType(ContentType.Application.Json)
                if (apiKey.isNotBlank()) {
                    header("Authorization", "Bearer $apiKey")
                }
                setBody(request)
            }
            if (response.status == HttpStatusCode.OK) {
                return response.body()
            }
            val body = response.bodyAsText()
            val parsed = runCatching { json.decodeFromString(ApiErrorBody.serializer(), body) }
                .getOrNull()
            lastError = parsed?.error?.message ?: body.take(500)

            // Honour Retry-After / retry-after for 429 and 5xx, else throw.
            val statusCode = response.status.value
            val retryable = statusCode == 429 || statusCode in 500..599
            if (!retryable || attempt == MAX_RETRIES - 1) {
                throw LlmException("HTTP $statusCode: $lastError")
            }
            val retryAfterHeader = response.headers["Retry-After"] ?: response.headers["retry-after"]
            val delaySec = retryAfterHeader?.toDoubleOrNull() ?: extractRetryDelay(lastError) ?: DEFAULT_RETRY_DELAY_SEC
            delay((delaySec * 1000).toLong().coerceAtMost(60_000))
        }
        throw LlmException("HTTP retries exhausted: $lastError")
    }

    /** Some providers (e.g. Groq) embed "try again in N.Ns" inside the error message body. */
    private fun extractRetryDelay(message: String): Double? {
        val regex = Regex("""try again in ([0-9]+(?:\.[0-9]+)?)s""", RegexOption.IGNORE_CASE)
        return regex.find(message)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    /**
     * GET `{baseUrl}/models` (OpenAI-compatible). Returns model IDs.
     */
    suspend fun listModels(): List<String> {
        val url = baseUrl.trimEnd('/') + "/models"
        val response: HttpResponse = client.get(url) {
            if (apiKey.isNotBlank()) {
                header("Authorization", "Bearer $apiKey")
            }
        }
        if (response.status != HttpStatusCode.OK) {
            val body = response.bodyAsText()
            throw LlmException("HTTP ${response.status.value}: ${body.take(500)}")
        }
        val parsed = response.body<ModelListResponse>()
        return parsed.data.map { it.id }.distinct().sorted()
    }

    fun close() = client.close()

    companion object {
        private const val MAX_RETRIES = 4
        private const val DEFAULT_RETRY_DELAY_SEC = 5.0
    }
}

class LlmException(message: String) : RuntimeException(message)
