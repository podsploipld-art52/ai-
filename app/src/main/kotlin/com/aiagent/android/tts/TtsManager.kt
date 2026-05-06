package com.aiagent.android.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale

/**
 * Thin wrapper around the platform [TextToSpeech] API.
 *
 * Lazy-initialised; the first call to [speak] waits for the engine to finish initialising,
 * speaks the requested utterance, and resumes once the audio has finished playing.
 */
class TtsManager(context: Context) {
    private val app = context.applicationContext
    private var tts: TextToSpeech? = null
    private val ready = CompletableDeferred<Boolean>()

    init {
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                // Pick a voice for the device's primary language; fall back to English.
                val locale = Locale.getDefault()
                val res = engine.setLanguage(locale)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.US)
                }
                ready.complete(true)
            } else {
                Log.w(TAG, "TTS init failed: status=$status")
                ready.complete(false)
            }
        }
    }

    fun setRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.5f))
    }

    /** Speak [text] and suspend until the utterance finishes (or fails). */
    suspend fun speak(text: String, rate: Float = 1.0f): Boolean {
        if (text.isBlank()) return false
        val ok = ready.await()
        if (!ok) return false
        val engine = tts ?: return false
        engine.setSpeechRate(rate.coerceIn(0.5f, 2.5f))

        val done = CompletableDeferred<Boolean>()
        val utteranceId = "agent-" + System.nanoTime().toString()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (!done.isCompleted) done.complete(true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (!done.isCompleted) done.complete(false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (!done.isCompleted) done.complete(false)
            }
        })
        val res = engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        if (res != TextToSpeech.SUCCESS) {
            return false
        }
        return done.await()
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
    }

    companion object {
        private const val TAG = "TtsManager"
    }
}
