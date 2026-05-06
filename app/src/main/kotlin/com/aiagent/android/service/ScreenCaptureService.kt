package com.aiagent.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.aiagent.android.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * Foreground service that holds a long-lived [MediaProjection] + [ImageReader] pair so the agent
 * can grab single screen frames on demand. This is the universal fallback when the Accessibility
 * `takeScreenshot()` API fails or returns null (which happens on a number of OEM ROMs — Realme /
 * realme UI in particular blocks it for non-system services).
 *
 * Lifecycle:
 *   - The user grants MediaProjection consent once (same system dialog as video recording).
 *   - We start this service with the consent intent. It stays alive in the foreground.
 *   - The agent calls [captureFrame] whenever it needs a screenshot. We pull the most recent
 *     frame from the [ImageReader], copy it into a software [Bitmap], and return it.
 *   - The service auto-stops when the projection is revoked (system "Casting stopped" toast) or
 *     when [stop] is invoked.
 */
class ScreenCaptureService : Service() {

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
                start(resultCode, data)
            }
            ACTION_STOP -> {
                tearDown()
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
                "Захват экрана",
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(channel)
        }
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Agent")
            .setContentText("Захват экрана для агента активен")
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

    private fun start(resultCode: Int, data: Intent) {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(resultCode, data)
            if (mp == null) {
                Log.e(TAG, "Failed to obtain MediaProjection")
                lastError = "Не удалось получить MediaProjection"
                stopSelf()
                return
            }
            mediaProjection = mp

            // From Android 14 the system requires registering a callback before creating any
            // VirtualDisplay; otherwise getMediaProjection() throws SecurityException at runtime.
            mp.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.i(TAG, "MediaProjection stopped by system")
                        tearDown()
                        stopSelf()
                    }
                },
                Handler(handlerThread.looper),
            )

            val metrics = DisplayMetrics()
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi

            val reader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                /* maxImages= */ 2,
            )
            imageReader = reader

            virtualDisplay = mp.createVirtualDisplay(
                "AI Agent Capture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                /* callback= */ null,
                Handler(handlerThread.looper),
            )

            isRunning = true
            lastError = null
            Log.i(TAG, "Screen capture started: ${screenWidth}x$screenHeight @ $screenDensity dpi")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start screen capture", t)
            lastError = t.message ?: t::class.java.simpleName
            tearDown()
            stopSelf()
        }
    }

    private fun tearDown() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        isRunning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        tearDown()
    }

    companion object {
        private const val TAG = "ScreenCapture"
        private const val NOTIFICATION_ID = 4711
        private const val CHANNEL_ID = "ai-agent-capture"

        const val ACTION_START = "com.aiagent.android.ACTION_CAPTURE_START"
        const val ACTION_STOP = "com.aiagent.android.ACTION_CAPTURE_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var lastError: String? = null
            private set

        // Worker thread for ImageReader / MediaProjection callbacks.
        private val handlerThread by lazy {
            HandlerThread("ai-agent-capture").apply { start() }
        }

        private var mediaProjection: MediaProjection? = null
        private var virtualDisplay: VirtualDisplay? = null
        private var imageReader: ImageReader? = null
        private var screenWidth: Int = 0
        private var screenHeight: Int = 0
        private var screenDensity: Int = 0

        /**
         * Pull the most recent frame from the [ImageReader] and convert it into a software
         * [Bitmap]. Returns null if the service isn't running or no frame is available yet.
         *
         * This call is safe to invoke from any thread but does block briefly while it waits for
         * the next frame to arrive (max ~600ms).
         */
        suspend fun captureFrame(): Bitmap? {
            val reader = imageReader ?: return null
            // The first call after start may race the VirtualDisplay producer, so retry briefly
            // until at least one frame has been latched.
            repeat(6) {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    try {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * screenWidth
                        val bmpWidth = screenWidth + rowPadding / pixelStride
                        val bitmap = Bitmap.createBitmap(
                            bmpWidth,
                            screenHeight,
                            Bitmap.Config.ARGB_8888,
                        )
                        bitmap.copyPixelsFromBuffer(buffer)
                        // Crop off the padding column so the bitmap matches the real screen size.
                        return if (rowPadding == 0) {
                            bitmap
                        } else {
                            Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight).also {
                                bitmap.recycle()
                            }
                        }
                    } finally {
                        image.close()
                    }
                }
                delay(100)
            }
            return null
        }
    }
}
