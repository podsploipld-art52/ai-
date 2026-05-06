package com.aiagent.android.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.aiagent.android.data.Settings
import com.aiagent.android.service.AgentAccessibilityService
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Floating virtual joystick overlay.
 *
 * Two modes:
 *  - **Active (default)**: touches on the thumb push it; release returns to centre. Each
 *    movement of the thumb is mirrored as an in-app drag dispatched through the
 *    AccessibilityService at the same screen coordinates — so if the user places the overlay
 *    over a game's built-in joystick the gesture passes through.
 *  - **Configure (long-press)**: a single-finger drag on the base moves the overlay; pinch
 *    with two fingers resizes it. Position/size are persisted in [Settings].
 *
 * Programmatic moves: the AI calls [pushDirection] (via the `joystick_move` tool) to push
 * the thumb in a direction for a given duration, then release.
 */
class JoystickOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var rootView: JoystickView? = null
    private var settings: Settings? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> show()
            ACTION_HIDE -> hide()
            ACTION_PUSH -> {
                val angle = intent.getFloatExtra(EXTRA_ANGLE, 0f)
                val magnitude = intent.getFloatExtra(EXTRA_MAGNITUDE, 1f).coerceIn(0f, 1f)
                val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 600L)
                rootView?.aiPush(angle, magnitude, durationMs)
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        if (rootView != null) return
        val s = settings ?: Settings(this).also { settings = it }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val view = JoystickView(this, s)
        rootView = view
        instance = this

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        // Joystick overlay must capture touches (NOT_FOCUSABLE only — keyboard never targets it).
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val sizePx = (s.joystickRadius * 2 + dp(20)).coerceAtLeast(dp(80))
        // Clamp the persisted centre into the visible screen so a stale Settings value (e.g.
        // from a previous device with different resolution, or from a glitched drag) can't
        // hide the joystick off-screen. We require at least 1/3 of the joystick to be visible.
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val minMargin = sizePx / 3
        val safeCx = s.joystickX.coerceIn(minMargin, screenW - minMargin)
        val safeCy = s.joystickY.coerceIn(minMargin, screenH - minMargin)
        if (safeCx != s.joystickX) s.joystickX = safeCx
        if (safeCy != s.joystickY) s.joystickY = safeCy
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = safeCx - sizePx / 2
            y = safeCy - sizePx / 2
        }
        runCatching { windowManager?.addView(view, params) }
        view.windowParams = params
        view.windowManager = windowManager
        view.updateScreenBounds(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
    }

    private fun hide() {
        val v = rootView
        if (v != null) runCatching { windowManager?.removeView(v) }
        rootView = null
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        hide()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Custom view: draws the joystick base + thumb and handles touches. */
    @SuppressLint("ViewConstructor")
    private class JoystickView(ctx: Context, private val settings: Settings) : View(ctx) {

        var windowManager: WindowManager? = null
        var windowParams: WindowManager.LayoutParams? = null
        private var screenWidthPx: Int = 1080
        private var screenHeightPx: Int = 1920

        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#664527A0") // semi-transparent purple
        }
        private val baseStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
        }
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFC107") // amber
        }
        private val thumbStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF000000")
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(12f)
            textAlign = Paint.Align.CENTER
        }

        // Thumb position in view-local coordinates relative to base centre.
        private var thumbDx: Float = 0f
        private var thumbDy: Float = 0f

        private var dragging = false
        private var configMode = false

        private val longPressMs = 500L
        private var longPressStart = 0L
        private var moveDistanceForCancel = dp(8f)
        private var anchorRawX = 0f
        private var anchorRawY = 0f
        private var anchorWindowX = 0
        private var anchorWindowY = 0

        // For pinch-to-resize while in config mode.
        private var initialPinchDistance = 0f
        private var initialRadius = 0
        private var inPinch = false

        // Pending long-press timer scheduled at ACTION_DOWN. Cancelled on UP / CANCEL or once
        // the finger moves more than [moveDistanceForCancel].
        private val handler = Handler(Looper.getMainLooper())
        private var longPressRunnable: Runnable? = null

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = settings.joystickRadius * 2 + dp(20f).toInt()
            setMeasuredDimension(size, size)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = settings.joystickRadius.toFloat()
            // Base circle
            canvas.drawCircle(cx, cy, r, basePaint)
            canvas.drawCircle(cx, cy, r, baseStrokePaint)
            // Thumb circle (smaller)
            val thumbR = r * 0.42f
            canvas.drawCircle(cx + thumbDx, cy + thumbDy, thumbR, thumbPaint)
            canvas.drawCircle(cx + thumbDx, cy + thumbDy, thumbR, thumbStrokePaint)
            if (configMode) {
                canvas.drawText("Настройка", cx, cy - r - dp(4f), labelPaint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val cx = width / 2f
            val cy = height / 2f
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPressStart = System.currentTimeMillis()
                    anchorRawX = event.rawX
                    anchorRawY = event.rawY
                    anchorWindowX = windowParams?.x ?: 0
                    anchorWindowY = windowParams?.y ?: 0
                    val dx = event.x - cx
                    val dy = event.y - cy
                    if (configMode) {
                        // Already in config mode — single tap to exit, drag to move.
                    } else if (hypot(dx, dy) <= settings.joystickRadius * 1.05f) {
                        // Touch on base/thumb area -> start dragging the thumb (active mode).
                        dragging = true
                        updateThumb(dx, dy)
                        beginDispatch()
                        // Schedule a long-press timer. Even if the user holds completely still
                        // (no further ACTION_MOVE events), this runnable converts the active
                        // drag into a configuration drag after `longPressMs`.
                        // IMPORTANT: capture event values NOW. By the time the runnable fires
                        // 500 ms later, `event` is recycled by Android and reading
                        // event.rawX / event.rawY returns garbage — which used to make the
                        // window jump off-screen.
                        val downRawX = event.rawX
                        val downRawY = event.rawY
                        cancelPendingLongPress()
                        val r = Runnable {
                            if (configMode) return@Runnable
                            if (dragging) {
                                dragging = false
                                endDispatch()
                                thumbDx = 0f
                                thumbDy = 0f
                            }
                            configMode = true
                            // Re-anchor at the captured DOWN position. The next ACTION_MOVE
                            // computes delta from here; without this the window snaps to
                            // wherever the recycled event happened to be pointing.
                            anchorRawX = downRawX
                            anchorRawY = downRawY
                            anchorWindowX = windowParams?.x ?: 0
                            anchorWindowY = windowParams?.y ?: 0
                            invalidate()
                        }
                        handler.postDelayed(r, longPressMs)
                        longPressRunnable = r
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (configMode && event.pointerCount == 2) {
                        initialPinchDistance = pinchDistance(event)
                        initialRadius = settings.joystickRadius
                        inPinch = true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (inPinch && configMode && event.pointerCount >= 2) {
                        val dist = pinchDistance(event)
                        if (initialPinchDistance > 1f) {
                            val scale = dist / initialPinchDistance
                            val newRadius = (initialRadius * scale).toInt().coerceIn(40, 600)
                            if (newRadius != settings.joystickRadius) {
                                settings.joystickRadius = newRadius
                                resizeWindow(newRadius)
                                invalidate()
                            }
                        }
                        return true
                    }
                    // If the finger has wandered past the threshold before the long-press
                    // timer fired, treat this as a deliberate active-mode drag and cancel
                    // the pending long-press so a slow drag never accidentally enters config.
                    if (!configMode && longPressRunnable != null) {
                        val moved = hypot(event.rawX - anchorRawX, event.rawY - anchorRawY)
                        if (moved >= moveDistanceForCancel) cancelPendingLongPress()
                    }
                    if (!configMode && !dragging) return true
                    if (configMode && !inPinch) {
                        // Drag the whole window.
                        val dx = (event.rawX - anchorRawX).toInt()
                        val dy = (event.rawY - anchorRawY).toInt()
                        val params = windowParams
                        if (params != null) {
                            params.x = (anchorWindowX + dx).coerceIn(
                                -params.width / 2,
                                screenWidthPx - params.width / 2,
                            )
                            params.y = (anchorWindowY + dy).coerceIn(
                                -params.height / 2,
                                screenHeightPx - params.height / 2,
                            )
                            runCatching { windowManager?.updateViewLayout(this, params) }
                            settings.joystickX = params.x + params.width / 2
                            settings.joystickY = params.y + params.height / 2
                        }
                    } else if (dragging) {
                        val dx = event.x - cx
                        val dy = event.y - cy
                        updateThumb(dx, dy)
                        updateDispatch()
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.pointerCount <= 2) {
                        inPinch = false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelPendingLongPress()
                    if (configMode) {
                        // A clean tap (no drag, no pinch) exits config mode.
                        val moved = hypot(event.rawX - anchorRawX, event.rawY - anchorRawY)
                        if (!inPinch && moved < moveDistanceForCancel) {
                            configMode = false
                            invalidate()
                        }
                    } else if (dragging) {
                        endDispatch()
                        dragging = false
                        thumbDx = 0f
                        thumbDy = 0f
                        invalidate()
                    }
                    inPinch = false
                }
            }
            return true
        }

        private fun cancelPendingLongPress() {
            longPressRunnable?.let { handler.removeCallbacks(it) }
            longPressRunnable = null
        }

        private fun pinchDistance(event: MotionEvent): Float {
            if (event.pointerCount < 2) return 0f
            val dx = event.getX(0) - event.getX(1)
            val dy = event.getY(0) - event.getY(1)
            return hypot(dx, dy)
        }

        private fun updateThumb(dx: Float, dy: Float) {
            val r = settings.joystickRadius.toFloat()
            val dist = hypot(dx, dy)
            if (dist <= r) {
                thumbDx = dx
                thumbDy = dy
            } else {
                thumbDx = dx * r / dist
                thumbDy = dy * r / dist
            }
            invalidate()
        }

        private fun centerScreenX(): Float {
            val params = windowParams ?: return 0f
            return (params.x + params.width / 2).toFloat()
        }

        private fun centerScreenY(): Float {
            val params = windowParams ?: return 0f
            return (params.y + params.height / 2).toFloat()
        }

        private fun beginDispatch() {
            if (!settings.joystickDispatch) return
            val service = AgentAccessibilityService.instance ?: return
            val cx = centerScreenX()
            val cy = centerScreenY()
            runCatching { service.joystickBegin(cx + thumbDx, cy + thumbDy) }
        }

        private fun updateDispatch() {
            if (!settings.joystickDispatch) return
            val service = AgentAccessibilityService.instance ?: return
            val cx = centerScreenX()
            val cy = centerScreenY()
            runCatching { service.joystickUpdate(cx + thumbDx, cy + thumbDy) }
        }

        private fun endDispatch() {
            if (!settings.joystickDispatch) return
            val service = AgentAccessibilityService.instance ?: return
            val cx = centerScreenX()
            val cy = centerScreenY()
            runCatching { service.joystickEnd(cx + thumbDx, cy + thumbDy) }
        }

        private fun resizeWindow(radiusPx: Int) {
            val params = windowParams ?: return
            val newSize = radiusPx * 2 + dp(20f).toInt()
            val centerX = params.x + params.width / 2
            val centerY = params.y + params.height / 2
            params.width = newSize
            params.height = newSize
            params.x = centerX - newSize / 2
            params.y = centerY - newSize / 2
            runCatching { windowManager?.updateViewLayout(this, params) }
            requestLayout()
        }

        fun updateScreenBounds(w: Int, h: Int) {
            screenWidthPx = w
            screenHeightPx = h
        }

        /**
         * Programmatically push the thumb in [angleDeg] (0=east, 90=south, etc.) at [magnitude]
         * (0..1 of radius) for [durationMs], then release. Called by the AI via the
         * `joystick_move` tool.
         */
        fun aiPush(angleDeg: Float, magnitude: Float, durationMs: Long) {
            if (dragging) return // user is interacting; respect them.
            val rad = Math.toRadians(angleDeg.toDouble())
            val r = settings.joystickRadius.toFloat() * magnitude.coerceIn(0f, 1f)
            val dx = (r * cos(rad)).toFloat()
            val dy = (r * sin(rad)).toFloat()
            post {
                thumbDx = dx
                thumbDy = dy
                invalidate()
                beginDispatch()
                postDelayed({
                    updateDispatch()
                }, min(durationMs / 2, 200))
                postDelayed({
                    endDispatch()
                    thumbDx = 0f
                    thumbDy = 0f
                    invalidate()
                }, durationMs)
            }
        }

        private fun dp(v: Float): Float = v * resources.displayMetrics.density
    }

    companion object {
        private const val TAG = "JoystickOverlay"
        const val ACTION_SHOW = "com.aiagent.android.JOYSTICK_SHOW"
        const val ACTION_HIDE = "com.aiagent.android.JOYSTICK_HIDE"
        const val ACTION_PUSH = "com.aiagent.android.JOYSTICK_PUSH"
        const val EXTRA_ANGLE = "angle_deg"
        const val EXTRA_MAGNITUDE = "magnitude"
        const val EXTRA_DURATION_MS = "duration_ms"

        @Volatile
        var instance: JoystickOverlayService? = null
            private set

        fun show(context: Context) {
            context.startService(Intent(context, JoystickOverlayService::class.java).apply {
                action = ACTION_SHOW
            })
        }

        fun hide(context: Context) {
            context.startService(Intent(context, JoystickOverlayService::class.java).apply {
                action = ACTION_HIDE
            })
        }

        /**
         * Push the joystick programmatically. [angleDeg]: 0 = east, 90 = south (Android Y-down),
         * 180 = west, 270 = north. [magnitude]: 0..1 fraction of radius. [durationMs]: how long
         * to hold before releasing.
         */
        fun push(context: Context, angleDeg: Float, magnitude: Float, durationMs: Long) {
            context.startService(Intent(context, JoystickOverlayService::class.java).apply {
                action = ACTION_PUSH
                putExtra(EXTRA_ANGLE, angleDeg)
                putExtra(EXTRA_MAGNITUDE, magnitude)
                putExtra(EXTRA_DURATION_MS, durationMs)
            })
        }

        fun isActive(): Boolean = instance != null
    }
}
