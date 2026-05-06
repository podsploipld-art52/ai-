package com.aiagent.android.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Records audio from the device microphone and writes a 16-bit-PCM WAV file. Designed for
 * Whisper / SpeechRecognizer-style downstream consumers — single channel, 16 kHz.
 *
 * Use [start] to begin recording, [stop] to flush a finalised WAV file, [stopAndGetFile] to
 * combine both. Recording is bounded by [maxMs] for safety.
 */
class MicRecorder {

    @Volatile private var isRecording = false
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    private var rawFile: File? = null

    @SuppressWarnings("MissingPermission")
    fun start(targetWav: File, maxMs: Long = 60_000L): Boolean {
        if (isRecording) return false
        val sampleRate = 16_000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuf <= 0) {
            Log.w(TAG, "Invalid min buffer: $minBuf")
            return false
        }
        val bufSize = (minBuf * 2).coerceAtLeast(4096)

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufSize,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "AudioRecord build failed: ${e.message}")
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }

        targetWav.parentFile?.mkdirs()
        // Write raw PCM first; convert to WAV at stop().
        val raw = File(targetWav.parentFile ?: targetWav, "raw-${System.nanoTime()}.pcm")
        raw.outputStream().use { /* truncate */ }
        rawFile = raw

        recorder = rec
        rec.startRecording()
        isRecording = true

        val deadline = System.currentTimeMillis() + maxMs
        thread = Thread({
            FileOutputStream(raw, true).use { out ->
                val buf = ByteArray(bufSize)
                while (isRecording && System.currentTimeMillis() < deadline) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) out.write(buf, 0, n)
                    else if (n < 0) break
                }
                out.flush()
            }
        }, "mic-recorder").also { it.start() }
        // Save target so stop() can finalise the WAV.
        targetFile = targetWav
        return true
    }

    private var targetFile: File? = null

    fun stop(): File? {
        if (!isRecording) return null
        isRecording = false
        thread?.join(2_000)
        val rec = recorder
        recorder = null
        try {
            rec?.stop()
        } catch (_: IllegalStateException) {
        } finally {
            rec?.release()
        }
        val raw = rawFile
        val target = targetFile
        rawFile = null
        targetFile = null
        if (raw == null || target == null) return null
        return try {
            writeWavFromPcm(raw, target, sampleRate = 16_000, channels = 1)
            raw.delete()
            target
        } catch (e: IOException) {
            Log.w(TAG, "wav writing failed", e)
            null
        }
    }

    fun isRunning(): Boolean = isRecording

    /**
     * Records from the mic, but stops automatically as soon as the user has finished speaking.
     *
     * Energy-based voice-activity detection (VAD) on 50ms frames of 16-bit-PCM:
     *  1. Wait up to [initialSilenceTimeoutMs] for speech to start (RMS above [speechRms]).
     *  2. Once speaking, keep recording until the RMS stays below [silenceRms] continuously for
     *     [silenceTrailMs] — this is the "user has finished talking" signal.
     *  3. Hard cap at [maxMs] so a stuck mic / hammer noise can't record forever.
     *
     * Returns the finalised WAV file (with header) and a numeric reason describing how the
     * recording ended ([VadStop.SPOKEN_AND_FINISHED] / [VadStop.NO_SPEECH] / [VadStop.MAX_DURATION] /
     * [VadStop.CANCELLED]). Returns `null` for the file when no speech was captured.
     *
     * Designed to be called from a background thread (Dispatchers.IO) — it BLOCKS until VAD
     * decides the user is done. [isCancelled] is polled between chunks so an outer coroutine
     * cancel can break the loop within ~50ms.
     */
    @SuppressWarnings("MissingPermission")
    fun recordUntilSilence(
        targetWav: File,
        maxMs: Long = 30_000L,
        initialSilenceTimeoutMs: Long = 6_000L,
        silenceTrailMs: Long = 800L,
        speechRms: Int = 600,
        silenceRms: Int = 350,
        onLevel: ((rms: Int, isSpeaking: Boolean) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ): VadResult {
        if (isRecording) return VadResult(null, VadStop.NO_SPEECH, "already recording")
        val sampleRate = 16_000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuf <= 0) return VadResult(null, VadStop.NO_SPEECH, "AudioRecord buf size $minBuf")
        val bufSize = (minBuf * 2).coerceAtLeast(4096)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufSize,
            )
        } catch (e: SecurityException) {
            return VadResult(null, VadStop.NO_SPEECH, "no RECORD_AUDIO: ${e.message}")
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return VadResult(null, VadStop.NO_SPEECH, "AudioRecord not initialised")
        }
        // 50ms chunks (1600 bytes at 16kHz mono 16-bit) → cheap RMS, ~50ms cancel granularity.
        val chunkBytes = ((sampleRate / 20) * 2).coerceAtLeast(1024)
        val chunk = ByteArray(chunkBytes)
        val pcm = ByteArrayOutputStream(chunkBytes * 64)
        rec.startRecording()
        isRecording = true

        val started = System.currentTimeMillis()
        val deadline = started + maxMs
        val initialDeadline = started + initialSilenceTimeoutMs
        var hasSpoken = false
        var silenceStartedAt = 0L
        var stop = VadStop.MAX_DURATION
        try {
            while (true) {
                if (isCancelled()) {
                    stop = VadStop.CANCELLED
                    break
                }
                val now = System.currentTimeMillis()
                if (now >= deadline) {
                    stop = if (hasSpoken) VadStop.MAX_DURATION else VadStop.NO_SPEECH
                    break
                }
                if (!hasSpoken && now >= initialDeadline) {
                    stop = VadStop.NO_SPEECH
                    break
                }
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) {
                    Thread.sleep(20)
                    continue
                }
                pcm.write(chunk, 0, n)
                val rms = rmsShort(chunk, n)
                val above = rms > if (hasSpoken) silenceRms else speechRms
                if (above) {
                    hasSpoken = true
                    silenceStartedAt = 0L
                } else if (hasSpoken) {
                    if (silenceStartedAt == 0L) silenceStartedAt = now
                    else if (now - silenceStartedAt >= silenceTrailMs) {
                        stop = VadStop.SPOKEN_AND_FINISHED
                        break
                    }
                }
                onLevel?.invoke(rms, above)
            }
        } finally {
            try { rec.stop() } catch (_: IllegalStateException) {}
            rec.release()
            isRecording = false
        }
        if (!hasSpoken || pcm.size() == 0) {
            return VadResult(null, stop, null)
        }
        targetWav.parentFile?.mkdirs()
        return try {
            writeWavFromPcmBytes(pcm.toByteArray(), targetWav, sampleRate, channels = 1)
            VadResult(targetWav, stop, null)
        } catch (e: IOException) {
            VadResult(null, stop, "wav write failed: ${e.message}")
        }
    }

    private fun rmsShort(buf: ByteArray, len: Int): Int {
        // Treat byte buffer as little-endian 16-bit signed PCM.
        var sumSq = 0L
        var samples = 0
        var i = 0
        while (i + 1 < len) {
            val lo = buf[i].toInt() and 0xff
            val hi = buf[i + 1].toInt() // signed
            val s = (hi shl 8) or lo
            sumSq += (s.toLong() * s.toLong())
            samples++
            i += 2
        }
        if (samples == 0) return 0
        return sqrt(sumSq.toDouble() / samples).toInt()
    }

    private fun writeWavFromPcmBytes(pcmBytes: ByteArray, wav: File, sampleRate: Int, channels: Int) {
        val byteRate = sampleRate * channels * 2
        val totalDataLen = pcmBytes.size + 36L
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen.toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * 2).toShort())
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmBytes.size)
        wav.outputStream().use { out ->
            out.write(header.array())
            out.write(pcmBytes)
        }
    }

    private fun writeWavFromPcm(pcm: File, wav: File, sampleRate: Int, channels: Int) {
        val pcmBytes = pcm.readBytes()
        val byteRate = sampleRate * channels * 2
        val totalDataLen = pcmBytes.size + 36L
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen.toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * 2).toShort()) // block align
        header.putShort(16) // bits/sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmBytes.size)
        wav.outputStream().use { out ->
            out.write(header.array())
            out.write(pcmBytes)
        }
    }

    companion object {
        private const val TAG = "MicRecorder"
    }
}

/** Why the VAD recorder stopped. */
enum class VadStop {
    /** User finished speaking and the silence-trail timer expired. */
    SPOKEN_AND_FINISHED,
    /** User never started speaking within the initial-silence window. */
    NO_SPEECH,
    /** [MicRecorder.recordUntilSilence] hit the [maxMs] cap. */
    MAX_DURATION,
    /** The outer coroutine was cancelled. */
    CANCELLED,
}

/** Result of a VAD-bounded recording. [wav] is null when no speech was captured. */
data class VadResult(
    val wav: File?,
    val stopReason: VadStop,
    val errorMessage: String? = null,
)
