package com.aiagent.android.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aiagent.android.audio.MicRecorder
import com.aiagent.android.camera.CameraTool
import com.aiagent.android.data.Settings
import com.aiagent.android.stt.SpeechToText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
     *  1. Start the mic recording (non-blocking).
     *  2. In parallel: take a still photo with the camera (~1-2s).
     *  3. Sleep until the turn duration is up.
     *  4. Stop the mic, transcribe the WAV.
     */
    suspend fun captureOne(): Result = coroutineScope {
        val turnSec = settings.liveTurnSeconds.coerceIn(2, 30)
        val durationMs = turnSec * 1000L
        val agentRoot = preferredAgentRoot()
        val tmpDir = File(agentRoot, "live").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val wavTarget = File(tmpDir, "live-$ts.wav")

        // Start mic; record up to (durationMs - 200ms) so we have head-room for STT.
        val micStarted = mic.start(wavTarget, maxMs = (durationMs - 200L).coerceAtLeast(1500L))

        // Start camera in parallel.
        val cameraJob = async(Dispatchers.IO) {
            CameraTool.takePhoto(context, settings.liveCameraFacing)
        }

        // Wait the budgeted duration. If the coroutine is cancelled during this delay
        // the finally{} below cleans the mic up.
        var transcript = ""
        var transcriptError: String? = null
        try {
            delay(durationMs)
        } finally {
            if (micStarted) {
                val wav = withContext(Dispatchers.IO) { mic.stop() }
                if (wav != null) {
                    val res = runCatching { stt.transcribeFile(wav, language = null) }
                    if (res.isSuccess) {
                        transcript = res.getOrThrow().trim()
                    } else {
                        transcriptError = res.exceptionOrNull()?.message ?: "stt failed"
                    }
                    runCatching { wav.delete() }
                } else {
                    transcriptError = "mic.stop returned null"
                }
            } else {
                transcriptError = "mic.start failed (нет permission или AudioRecord занят)"
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
