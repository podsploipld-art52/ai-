package com.aiagent.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.aiagent.android.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that captures the device screen via [MediaProjection] and writes it as an
 * MP4 file under the app's external Movies directory.
 *
 * Started by [com.aiagent.android.ui.MainViewModel] once the user has granted projection consent
 * through the system dialog.
 */
class ScreenRecorderService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (data == null) {
                    Log.w(TAG, "Missing projection intent")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startInForeground()
                startRecording(resultCode, data)
            }
            ACTION_STOP -> {
                stopRecording()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Запись экрана",
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(channel)
        }
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Agent")
            .setContentText("Запись экрана идёт...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, data) ?: run {
                Log.e(TAG, "Failed to obtain MediaProjection")
                stopSelf()
                return
            }

            val moviesDir = File(
                getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir,
                "AI Agent",
            ).apply { mkdirs() }
            val name = "ai-agent-" +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".mp4"
            outputFile = File(moviesDir, name)

            val metrics = DisplayMetrics()
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            // Cap to 720x1280 to keep encoder happy across devices.
            val (width, height) = scaleDownToHd(metrics.widthPixels, metrics.heightPixels)
            val density = metrics.densityDpi

            val recorder = if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder = recorder
            recorder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(4 * 1024 * 1024)
                setVideoFrameRate(24)
                setVideoSize(width, height)
                setOutputFile(outputFile!!.absolutePath)
                prepare()
            }

            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "ai-agent-record",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null,
                null,
            )
            recorder.start()
            isRecording = true
            lastFile = outputFile?.absolutePath
            Log.i(TAG, "Screen recording started: ${outputFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            lastError = e.message ?: e.toString()
            stopRecording()
            stopSelf()
        }
    }

    private fun stopRecording() {
        runCatching { mediaRecorder?.stop() }
        runCatching { mediaRecorder?.reset() }
        runCatching { mediaRecorder?.release() }
        runCatching { virtualDisplay?.release() }
        runCatching { mediaProjection?.stop() }
        mediaRecorder = null
        virtualDisplay = null
        mediaProjection = null
        isRecording = false

        val file = outputFile
        if (file != null && file.exists()) {
            // Make the new clip discoverable in the Files / Gallery app.
            sendBroadcast(
                Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                    setData(Uri.fromFile(file))
                },
            )
        }
    }

    private fun scaleDownToHd(w: Int, h: Int): Pair<Int, Int> {
        val maxSide = 1280
        val longSide = maxOf(w, h)
        if (longSide <= maxSide) return Pair(roundEven(w), roundEven(h))
        val scale = maxSide.toDouble() / longSide
        return Pair(roundEven((w * scale).toInt()), roundEven((h * scale).toInt()))
    }

    /** H264 encoder requires even width/height. */
    private fun roundEven(v: Int): Int = if (v % 2 == 0) v else v - 1

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
    }

    companion object {
        private const val TAG = "ScreenRecorder"
        private const val CHANNEL_ID = "ai-agent-record"
        private const val NOTIFICATION_ID = 0xA17E

        const val ACTION_START = "com.aiagent.android.ACTION_RECORD_START"
        const val ACTION_STOP = "com.aiagent.android.ACTION_RECORD_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "result_data"

        @Volatile var isRecording: Boolean = false
            private set

        @Volatile var lastFile: String? = null
            private set

        @Volatile var lastError: String? = null
            private set
    }
}
