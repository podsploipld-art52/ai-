package com.aiagent.android.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Long-lived Camera2 preview session for the "Live" mode.
 *
 * Unlike [CameraTool] which opens / closes the camera per call, this singleton keeps the camera
 * open as long as the preview is visible, so the user sees a real-time feed (not just a snapshot
 * after each turn). Stills for the model are produced by grabbing the current bitmap from the
 * [TextureView] — which is much simpler than juggling a separate ImageReader and is plenty for
 * an LLM that's about to downscale to 512px anyway.
 *
 * Ownership rules:
 *  - The Compose `LiveCameraPreview` composable calls [attachPreview] when its TextureView is
 *    ready and [detachPreview] in DisposableEffect on remove.
 *  - When the live mode toggle is turned off, the composable disappears, [detachPreview] runs,
 *    and the camera is closed.
 *  - [setFacing] re-opens the camera with the new lens.
 *  - [captureStill] grabs the current preview frame as a JPEG file (path returned).
 */
class LiveCameraController private constructor(private val appContext: Context) {

    private val handlerThread = HandlerThread("live-camera").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var textureView: TextureView? = null
    @Volatile private var previewSurface: Surface? = null
    @Volatile private var openCameraId: String? = null
    @Volatile private var requestedFacing: String = "front"
    @Volatile private var sensorOrientation: Int = 0

    /**
     * Attach a [TextureView] for preview. Must be called from the main thread (Compose's
     * AndroidView factory does that for us). Wires up a SurfaceTextureListener so we (re)open
     * the camera when the underlying surface is created or resized.
     */
    fun attachPreview(view: TextureView, facing: String) {
        if (textureView === view && requestedFacing == facing && device != null) return
        // Detach any previous view first.
        if (textureView !== view) {
            runCatching { textureView?.surfaceTextureListener = null }
        }
        textureView = view
        requestedFacing = if (facing == "back") "back" else "front"

        if (view.isAvailable) {
            startCameraOnPreviewThread(view.surfaceTexture, view.width, view.height)
        }
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                startCameraOnPreviewThread(s, w, h)
            }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {
                tearDownSession()
                return true
            }
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    fun setFacing(facing: String) {
        val normalised = if (facing == "back") "back" else "front"
        if (normalised == requestedFacing && device != null) return
        requestedFacing = normalised
        val view = textureView ?: return
        if (view.isAvailable) {
            startCameraOnPreviewThread(view.surfaceTexture, view.width, view.height)
        }
    }

    fun detachPreview(view: TextureView) {
        if (textureView === view) {
            runCatching { view.surfaceTextureListener = null }
            textureView = null
            tearDownSession()
        }
    }

    /**
     * Grab the current preview frame and save it as a JPEG. Returns the absolute path or null
     * if the preview is not currently rendering anything.
     */
    suspend fun captureStill(): String? {
        val view = textureView ?: return null
        // TextureView.getBitmap() is fastest from the main thread because it can read the
        // underlying GL texture without ping-ponging across threads.
        val deferred = CompletableDeferred<Bitmap?>()
        main.post {
            val bmp = runCatching {
                if (view.isAvailable) view.getBitmap() else null
            }.getOrNull()
            deferred.complete(bmp)
        }
        val bitmap = deferred.await() ?: return null

        // Save as JPEG.
        val publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val candidates = listOfNotNull(
            File(publicDocs, "AI-Agent/photos"),
            appContext.getExternalFilesDir(null)?.let { File(it, "photos") },
            File(appContext.filesDir, "photos"),
        )
        val dir = candidates.firstOrNull { runCatching { it.mkdirs() }.getOrDefault(false) || it.exists() }
            ?: return null
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val out = File(dir, "live-${requestedFacing}-$ts.jpg")
        return try {
            FileOutputStream(out).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, fos)
            }
            out.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "captureStill: save failed", e)
            null
        } finally {
            // Don't recycle — TextureView returned us a copy, but it is ours to free.
            runCatching { bitmap.recycle() }
        }
    }

    fun release() {
        textureView = null
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post {
            tearDownSessionOnHandler()
            latch.countDown()
        }
        runCatching { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) }
    }

    @SuppressLint("MissingPermission")
    private fun startCameraOnPreviewThread(surfaceTexture: SurfaceTexture?, w: Int, h: Int) {
        if (surfaceTexture == null) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "no CAMERA permission")
            return
        }
        handler.post {
            tearDownSessionOnHandler()

            val mgr = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return@post
            val targetLens = if (requestedFacing == "front") {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
            val cameraId = mgr.cameraIdList.firstOrNull { id ->
                mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == targetLens
            } ?: mgr.cameraIdList.firstOrNull() ?: return@post

            val ch = mgr.getCameraCharacteristics(cameraId)
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val config = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val previewSize = pickPreviewSize(config?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray(), w, h)
            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val surface = Surface(surfaceTexture)
            previewSurface = surface
            openCameraId = cameraId

            mgr.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    createSession(d, surface)
                }
                override fun onDisconnected(d: CameraDevice) {
                    runCatching { d.close() }
                    if (device === d) device = null
                }
                override fun onError(d: CameraDevice, error: Int) {
                    Log.w(TAG, "Camera error: $error")
                    runCatching { d.close() }
                    if (device === d) device = null
                }
            }, handler)
        }
    }

    private fun pickPreviewSize(sizes: Array<Size>, @Suppress("UNUSED_PARAMETER") viewW: Int, @Suppress("UNUSED_PARAMETER") viewH: Int): Size {
        if (sizes.isEmpty()) return Size(640, 480)
        // Aim for ~720p, never more than 1280×720, and prefer 4:3-or-16:9 sensor sizes.
        val ranked = sizes.sortedWith(
            compareBy(
                { kotlin.math.abs(it.width.toLong() * it.height - 1280L * 720L) },
                { it.width },
            ),
        )
        return ranked.first()
    }

    private fun createSession(d: CameraDevice, surface: Surface) {
        val request = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        request.addTarget(surface)
        request.set(CaptureRequest.CONTROL_MODE, CameraCharacteristics.CONTROL_MODE_AUTO)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val cfg = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(surface)),
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            runCatching { s.setRepeatingRequest(request.build(), null, handler) }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            Log.w(TAG, "session config failed")
                        }
                    },
                )
                d.createCaptureSession(cfg)
            } else {
                @Suppress("DEPRECATION")
                d.createCaptureSession(
                    listOf(surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            runCatching { s.setRepeatingRequest(request.build(), null, handler) }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            Log.w(TAG, "session config failed")
                        }
                    },
                    handler,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "createSession", e)
        }
    }

    private fun tearDownSession() {
        handler.post { tearDownSessionOnHandler() }
    }

    private fun tearDownSessionOnHandler() {
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        runCatching { previewSurface?.release() }
        previewSurface = null
    }

    companion object {
        private const val TAG = "LiveCameraController"

        @Volatile private var instance: LiveCameraController? = null

        fun get(context: Context): LiveCameraController = instance ?: synchronized(this) {
            instance ?: LiveCameraController(context.applicationContext).also { instance = it }
        }

        /** True iff the controller is currently producing preview frames. */
        fun isPreviewActive(): Boolean = instance?.session != null
    }
}
