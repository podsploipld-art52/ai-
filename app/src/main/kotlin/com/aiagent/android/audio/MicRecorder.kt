package com.aiagent.android.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
