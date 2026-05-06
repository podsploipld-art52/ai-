package com.aiagent.android.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aiagent.android.audio.MicRecorder
import com.aiagent.android.audio.VadStop
import com.aiagent.android.camera.CameraTool
import com.aiagent.android.data.Settings
import com.aiagent.android.stt.SpeechToText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One iteration of the agent's "live mode" — captures a camera frame and a short mic chunk in
 * parallel, transcribes the mic, and returns both for the agent loop to feed into the model.
 *
 * Live mode is a polling approximation of Gemini Live: Groq has no realtime streaming API, so
 * we run frequent fixed-duration turns. Each turn is bounded by [Settings.liveTurnSeconds] and
 * cancellation-aware (the running coroutine can be cancelled at any time and the mic/camera
 * resources will be released).
 */
class LiveTurnCapturer(
    private val context: Context,
    private val settings: Settings,
    private val stt: SpeechToText,
    private val mic: MicRecorder,
) {

    data class Result(
        val cameraJpegPath: String?,
        val cameraError: String?,
        val transcript: String,
        val transcriptError: String?,
    )

    /**
     * Capture a single live turn. Roughly:
     *  1. Snap a camera frame (in parallel — finishes in ~1s).
     *  2. Record from the microphone with energy-based VAD, so the recording stops as soon as
     *     the user finishes speaking. Hard-capped at 30s to defend against hammer noise / wind.
     *  3. Transcribe the WAV via [SpeechToText].
     *
     * The whole thing is cancellation-aware: if the outer coroutine is cancelled, the VAD loop
     * will notice via `isActive` and tear down the AudioRecord within ~50ms.
     */
    suspend fun captureOne(): Result = coroutineScope {
        val agentRoot = preferredAgentRoot()
        val tmpDir = File(agentRoot, "live").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val wavTarget = File(tmpDir, "live-$ts.wav")

        // Start camera in parallel — typically finishes in ~1s, much faster than the mic.
        val cameraJob = async(Dispatchers.IO) {
            CameraTool.takePhoto(context, settings.liveCameraFacing)
        }

        // Capture the user's utterance with VAD. We block this coroutine on Dispatchers.IO
        // so the rest of the agent can keep going (e.g. `cancelAgent` cancels this scope).
        val scope = this
        val vad = withContext(Dispatchers.IO) {
            mic.recordUntilSilence(
                targetWav = wavTarget,
                maxMs = 30_000L,
                initialSilenceTimeoutMs = 6_000L,
                silenceTrailMs = 800L,
                isCancelled = { !scope.isActive },
            )
        }

        var transcript = ""
        var transcriptError: String? = null
        when (vad.stopReason) {
            VadStop.SPOKEN_AND_FINISHED, VadStop.MAX_DURATION -> {
                val wav = vad.wav
                if (wav != null) {
                    val res = runCatching { stt.transcribeFile(wav, language = null) }
                    if (res.isSuccess) {
                        transcript = res.getOrThrow().trim()
                    } else {
                        transcriptError = res.exceptionOrNull()?.message ?: "stt failed"
                    }
                    runCatching { wav.delete() }
                } else {
                    transcriptError = vad.errorMessage ?: "mic returned no wav"
                }
            }
            VadStop.NO_SPEECH -> {
                // No utterance — transcript stays blank, no error needed.
                transcriptError = null
            }
            VadStop.CANCELLED -> {
                transcriptError = "cancelled"
            }
        }

        val cameraResult = cameraJob.await()
        val (cameraPath, cameraError) = if (cameraResult.isSuccess) {
            cameraResult.getOrThrow() to null
        } else {
            null to (cameraResult.exceptionOrNull()?.message ?: "camera failed")
        }
        Result(cameraPath, cameraError, transcript, transcriptError)
    }

    private fun preferredAgentRoot(): File {
        val publicDocs = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOCUMENTS,
        )
        val candidates = listOfNotNull(
            File(publicDocs, "AI-Agent"),
            context.getExternalFilesDir(null),
            context.filesDir,
        )
        for (dir in candidates) {
            val ok = runCatching { dir.mkdirs() }.getOrDefault(false) || dir.exists()
            if (ok && dir.canWrite()) return dir
        }
        return context.filesDir
    }
}

/**
 * Downscale a camera JPEG before stuffing into a base64 image_url so we don't blow up the LLM
 * token budget. ~512px on the long side is plenty for a "what do I see" check.
 */
fun downscaleCameraJpeg(path: String, longSidePx: Int = 512, jpegQuality: Int = 80): ByteArray? {
    val raw = File(path).takeIf { it.exists() }?.readBytes() ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0) return null
    val long = maxOf(w, h)
    val sample = if (long <= longSidePx) 1 else generateSequence(1) { it * 2 }
        .first { it * longSidePx >= long }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return null
    val out = java.io.ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
    bmp.recycle()
    return out.toByteArray()
}
