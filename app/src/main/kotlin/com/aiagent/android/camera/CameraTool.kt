package com.aiagent.android.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Headless camera capture using Camera2.
 *
 * Returns the absolute path of the saved JPEG, or null + an error message if capture failed.
 *
 * Notes:
 *  - Uses Camera2 directly (no CameraX dependency) to keep the APK small and avoid lifecycle
 *    coupling — the agent has no `Activity`.
 *  - Requires CAMERA permission, which we request at runtime via the same flow as
 *    RECORD_AUDIO (see MainActivity / MainViewModel).
 *  - The picture lands in `Documents/AI-Agent/photos/` if reachable, otherwise in
 *    the app's own external dir. The path is returned so the agent can later attach
 *    it to a vision message.
 */
object CameraTool {

    private const val TAG = "CameraTool"

    /**
     * Suspend until either (a) we've saved a photo and return its path, or (b) [timeoutMs]
     * elapses. Returns Result.success(path) or Result.failure with a human-readable error.
     */
    suspend fun takePhoto(
        context: Context,
        facing: String = "back",
        timeoutMs: Long = 12_000L,
    ): Result<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return Result.failure(IllegalStateException("камера не поддерживается на этой версии Android"))
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.failure(IllegalStateException("нет permission CAMERA — выдайте в настройках приложения"))
        }
        return withTimeoutOrNull(timeoutMs) { capture(context, facing) }
            ?: Result.failure(IllegalStateException("камера не отдала кадр за ${timeoutMs}ms"))
    }

    private suspend fun capture(context: Context, facing: String): Result<String> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return Result.failure(IllegalStateException("CameraManager недоступен"))
        val targetLens = if (facing.lowercase() == "front") {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == targetLens
        } ?: manager.cameraIdList.firstOrNull()
            ?: return Result.failure(IllegalStateException("камера не найдена"))

        val characteristics = manager.getCameraCharacteristics(cameraId)
        val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Result.failure(IllegalStateException("нет конфигов потока"))
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        // Pick a moderate JPEG size (max 1920×1080 or the largest supported) for upload-friendly
        // bitmaps. Models charge per megapixel and we don't need a 48 MP raw selfie.
        val sizes = configMap.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
        val targetSize: Size = sizes
            .filter { it.width <= 1920 && it.height <= 1920 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.firstOrNull()
            ?: return Result.failure(IllegalStateException("нет поддерживаемых разрешений JPEG"))

        val handlerThread = HandlerThread("ai-agent-camera").apply { start() }
        val handler = Handler(handlerThread.looper)
        val executor = Executors.newSingleThreadExecutor()

        val reader = ImageReader.newInstance(targetSize.width, targetSize.height, ImageFormat.JPEG, 2)

        return suspendCancellableCoroutine { cont ->
            var camera: CameraDevice? = null
            var session: CameraCaptureSession? = null
            fun cleanup() {
                runCatching { session?.close() }
                runCatching { camera?.close() }
                runCatching { reader.close() }
                runCatching { handlerThread.quitSafely() }
                runCatching { executor.shutdown() }
            }
            cont.invokeOnCancellation { cleanup() }

            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    val saved = saveBytes(context, bytes, sensorOrientation, facing)
                    if (cont.isActive) cont.resume(saved)
                } finally {
                    image.close()
                    cleanup()
                }
            }, handler)

            try {
                @Suppress("MissingPermission")
                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(c: CameraDevice) {
                        camera = c
                        try {
                            val target = reader.surface
                            val builder = c.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(target)
                                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                            }
                            val sessionCb = object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    session = s
                                    runCatching { s.capture(builder.build(), null, handler) }
                                        .onFailure {
                                            if (cont.isActive) cont.resume(Result.failure(it))
                                            cleanup()
                                        }
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    if (cont.isActive) {
                                        cont.resume(Result.failure(IllegalStateException("camera session configure failed")))
                                    }
                                    cleanup()
                                }
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                val outputConfig = OutputConfiguration(target)
                                val config = SessionConfiguration(
                                    SessionConfiguration.SESSION_REGULAR,
                                    listOf(outputConfig),
                                    executor,
                                    sessionCb,
                                )
                                c.createCaptureSession(config)
                            } else {
                                @Suppress("DEPRECATION")
                                c.createCaptureSession(listOf(target), sessionCb, handler)
                            }
                        } catch (e: Throwable) {
                            if (cont.isActive) cont.resume(Result.failure(e))
                            cleanup()
                        }
                    }
                    override fun onDisconnected(c: CameraDevice) {
                        if (cont.isActive) cont.resume(Result.failure(IllegalStateException("camera disconnected")))
                        cleanup()
                    }
                    override fun onError(c: CameraDevice, error: Int) {
                        if (cont.isActive) cont.resume(Result.failure(IllegalStateException("camera error code $error")))
                        cleanup()
                    }
                }, handler)
            } catch (e: SecurityException) {
                cont.resume(Result.failure(e))
                cleanup()
            } catch (e: Throwable) {
                cont.resume(Result.failure(e))
                cleanup()
            }
        }
    }

    private fun saveBytes(
        context: Context,
        bytes: ByteArray,
        sensorOrientation: Int,
        facing: String,
    ): Result<String> {
        return try {
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val name = "photo-$facing-$ts.jpg"
            val publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val candidates = listOfNotNull(
                File(publicDocs, "AI-Agent/photos"),
                context.getExternalFilesDir(null)?.let { File(it, "photos") },
                File(context.filesDir, "photos"),
            )
            var target: File? = null
            for (dir in candidates) {
                val ok = runCatching { dir.mkdirs() }.getOrDefault(false) || dir.exists()
                if (!ok) continue
                target = File(dir, name)
                break
            }
            val out = target ?: return Result.failure(IllegalStateException("нет каталога для записи"))
            // The camera always returns JPEG with EXIF orientation that some apps mis-render.
            // Instead, decode + rotate to upright pixels so the bitmap is correct everywhere.
            val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return Result.failure(IllegalStateException("не удалось декодировать JPEG"))
            val rotated = if (sensorOrientation != 0) {
                val matrix = Matrix().apply { postRotate(sensorOrientation.toFloat()) }
                Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            } else original
            FileOutputStream(out).use { fos ->
                rotated.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }
            if (rotated !== original) original.recycle()
            Result.success(out.absolutePath)
        } catch (e: Throwable) {
            Log.w(TAG, "saveBytes failed", e)
            Result.failure(e)
        }
    }
}
