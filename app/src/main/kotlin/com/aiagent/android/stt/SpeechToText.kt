package com.aiagent.android.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.aiagent.android.data.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Speech-to-text orchestrator.
 *
 * Two providers are supported:
 *  - "groq": uploads a recorded WAV/MP3 to Groq's Whisper-compatible /audio/transcriptions endpoint.
 *  - "android": uses the platform [SpeechRecognizer] (a one-shot live mic listen). No file needed.
 */
class SpeechToText(private val context: Context, private val settings: Settings) {

    /** Transcribe an existing audio file. Used for system-audio captures or pre-recorded clips. */
    suspend fun transcribeFile(file: File, language: String? = null): String {
        return when (settings.sttProvider) {
            "android" -> "[android STT не работает с файлами; используйте listen]"
            else -> transcribeWithGroq(file, language)
        }
    }

    /** Live one-shot listen via the platform recogniser. Runs on the main thread. */
    suspend fun listenLive(language: String? = null, partial: Boolean = false): String =
        withContext(Dispatchers.Main) { listenLiveImpl(language, partial) }

    private suspend fun listenLiveImpl(language: String?, partial: Boolean): String {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return "[SpeechRecognizer недоступен на устройстве]"
        }
        val deferred = CompletableDeferred<String>()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language ?: Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partial)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (!deferred.isCompleted) deferred.complete("[ошибка распознавания: $error]")
                runCatching { recognizer.destroy() }
            }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull().orEmpty()
                if (!deferred.isCompleted) deferred.complete(text)
                runCatching { recognizer.destroy() }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed", e)
            return "[не удалось начать распознавание: ${e.message}]"
        }
        return try {
            deferred.await()
        } finally {
            Handler(Looper.getMainLooper()).post { runCatching { recognizer.destroy() } }
        }
    }

    private suspend fun transcribeWithGroq(file: File, language: String?): String {
        if (!file.exists()) return "[файл не найден: ${file.absolutePath}]"
        val key = settings.apiKey
        if (key.isBlank()) return "[нужен API-ключ Groq в настройках]"
        val baseUrl = settings.baseUrl.trimEnd('/')
        val url = "$baseUrl/audio/transcriptions"
        val client = HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 120_000
                socketTimeoutMillis = 120_000
            }
        }
        return try {
            val content = MultiPartFormDataContent(formData {
                append("model", "whisper-large-v3")
                append("response_format", "json")
                language?.let { append("language", it) }
                append("file", file.readBytes(), Headers.build {
                    append(HttpHeaders.ContentType, ContentType.parse("audio/mpeg").toString())
                    append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                })
            })
            val response = client.post(url) {
                header("Authorization", "Bearer $key")
                setBody(content)
            }
            val body = response.bodyAsText()
            if (response.status == HttpStatusCode.OK) {
                runCatching {
                    Json { ignoreUnknownKeys = true }.decodeFromString(WhisperResponse.serializer(), body).text
                }.getOrElse { body.take(500) }
            } else {
                "[whisper HTTP ${response.status.value}: ${body.take(300)}]"
            }
        } catch (e: Exception) {
            Log.w(TAG, "groq whisper failed", e)
            "[whisper error: ${e.message}]"
        } finally {
            client.close()
        }
    }

    @Serializable
    private data class WhisperResponse(
        val text: String,
        @SerialName("language") val language: String? = null,
    )

    companion object {
        private const val TAG = "SpeechToText"
    }
}

// Shim to avoid an unused-import warning when callers don't need cancellation.
@Suppress("unused")
private suspend inline fun <T> awaitCallback(crossinline block: ((T) -> Unit) -> Unit): T =
    suspendCancellableCoroutine { cont -> block { value -> cont.resume(value) } }
