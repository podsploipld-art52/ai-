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
import android.os.IBinder
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
import kotlin.math.sin

/**
 * Floating virtual joystick overlay.
 *
 * Three interaction zones (always visible, no hidden long-press):
 *  - **Centre / base** → joystick thumb. Touch and drag → fires gestures into the game.
 *  - **Top-left corner ✥ handle** → drag this to move the whole joystick to a new spot.
 *  - **Bottom-right corner ⤡ handle** → drag this to resize the joystick (radius).
 *
 * The legacy long-press-to-configure path is kept as a fallback but the corner handles are
 * the primary, discoverable way to relocate / resize. Position/size persisted in [Settings].
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
            ACTION_RELAYOUT -> relayout()
            ACTION_PUSH -> {
                val angle = intent.getFloatExtra(EXTRA_ANGLE, 0f)
                val magnitude = intent.getFloatExtra(EXTRA_MAGNITUDE, 1f).coerceIn(0f, 1f)
                val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 600L)
                rootView?.aiPush(angle, magnitude, durationMs)
            }
        }
        return START_NOT_STICKY
    }

    /** Re-read joystickX/Y/radius from settings and reposition the floating window. Used
     *  when the user adjusts the sliders in the Agent tab. */
    private fun relayout() {
        val view = rootView ?: return
        val s = settings ?: return
        val params = view.windowParams ?: return
        val sizePx = (s.joystickRadius * 2 + dp(JOY_PADDING_DP)).coerceAtLeast(dp(80))
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val minMargin = sizePx / 3
        val safeCx = s.joystickX.coerceIn(minMargin, screenW - minMargin)
        val safeCy = s.joystickY.coerceIn(minMargin, screenH - minMargin)
        params.width = sizePx
        params.height = sizePx
        params.x = safeCx - sizePx / 2
        params.y = safeCy - sizePx / 2
        runCatching { windowManager?.updateViewLayout(view, params) }
        view.requestLayout()
        view.invalidate()
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
        val sizePx = (s.joystickRadius * 2 + dp(JOY_PADDING_DP)).coerceAtLeast(dp(80))
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
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC202020") // dark grey, 80%
        }
        private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
        }
        private val handleIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(13f)
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        // Corner-handle geometry — the always-visible resize affordance.
        private val handleRadiusPx: Float get() = dp(14f)
        private val handleMarginPx: Float get() = dp(2f)

        // Thumb position in view-local coordinates relative to base centre. Driven only by
        // the AI's `joystick_move` tool (aiPush); the user's finger never touches it.
        private var thumbDx: Float = 0f
        private var thumbDy: Float = 0f

        private var draggingWindow = false
        private var resizingFromHandle = false

        private var anchorRawX = 0f
        private var anchorRawY = 0f
        private var anchorWindowX = 0
        private var anchorWindowY = 0

        private var initialPinchDistance = 0f
        private var initialRadius = 0
        private var inPinch = false

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = settings.joystickRadius * 2 + dp(JOY_PADDING_DP.toFloat()).toInt()
            setMeasuredDimension(size, size)
        }

        /** Centre of the bottom-right "drag-to-resize" handle in view-local px. */
        private fun resizeHandleCenter(): Pair<Float, Float> {
            val cx = width - handleRadiusPx - handleMarginPx
            val cy = height - handleRadiusPx - handleMarginPx
            return cx to cy
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = settings.joystickRadius.toFloat()
            // Base circle
            canvas.drawCircle(cx, cy, r, basePaint)
            canvas.drawCircle(cx, cy, r, baseStrokePaint)
            // Thumb circle (smaller) — moved by the AI, not by the user's finger.
            val thumbR = r * 0.42f
            canvas.drawCircle(cx + thumbDx, cy + thumbDy, thumbR, thumbPaint)
            canvas.drawCircle(cx + thumbDx, cy + thumbDy, thumbR, thumbStrokePaint)
            // Corner ⤡ resize handle — always visible.
            val (rhx, rhy) = resizeHandleCenter()
            canvas.drawCircle(rhx, rhy, handleRadiusPx, handlePaint)
            canvas.drawCircle(rhx, rhy, handleRadiusPx, handleStrokePaint)
            canvas.drawText("⤡", rhx, rhy + dp(5f), handleIconPaint)
            // Tiny "drag-me" hint icon on top-left to advertise the move-by-touch behaviour.
            canvas.drawText("✥", handleRadiusPx + handleMarginPx,
                handleRadiusPx + handleMarginPx + dp(5f), handleIconPaint)

            if (draggingWindow || resizingFromHandle) {
                val label = if (draggingWindow) "Двигаю" else "Размер"
                canvas.drawText(label, cx, cy - r - dp(4f), labelPaint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val cx = width / 2f
            val cy = height / 2f
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    anchorRawX = event.rawX
                    anchorRawY = event.rawY
                    anchorWindowX = windowParams?.x ?: 0
                    anchorWindowY = windowParams?.y ?: 0

                    // The corner ⤡ resize handle has priority — single-finger drag from there
                    // resizes instead of moving the widget.
                    val (rhx, rhy) = resizeHandleCenter()
                    val hitResize = hypot(event.x - rhx, event.y - rhy) <= handleRadiusPx + dp(6f)
                    if (hitResize) {
                        resizingFromHandle = true
                        initialPinchDistance = hypot(event.x - cx, event.y - cy)
                        initialRadius = settings.joystickRadius
                        invalidate()
                        return true
                    }

                    // Anywhere else in the view → start dragging the WHOLE widget. The thumb is
                    // controlled exclusively by the AI via aiPush(); the user's finger is for
                    // positioning the joystick on the screen.
                    draggingWindow = true
                    invalidate()
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2) {
                        // Two fingers → pinch-to-resize (whichever zone the user started in).
                        initialPinchDistance = pinchDistance(event)
                        initialRadius = settings.joystickRadius
                        inPinch = true
                        // While pinching, suspend window-drag so the centre doesn't follow
                        // either finger.
                        draggingWindow = false
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (inPinch && event.pointerCount >= 2) {
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
                    if (resizingFromHandle) {
                        val curDist = hypot(event.x - cx, event.y - cy)
                        if (initialPinchDistance > 1f) {
                            val scale = curDist / initialPinchDistance
                            val newRadius = (initialRadius * scale).toInt().coerceIn(40, 600)
                            if (newRadius != settings.joystickRadius) {
                                settings.joystickRadius = newRadius
                                resizeWindow(newRadius)
                                invalidate()
                            }
                        }
                        return true
                    }
                    if (draggingWindow) {
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
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.pointerCount <= 2) {
                        inPinch = false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingWindow = false
                    resizingFromHandle = false
                    inPinch = false
                    invalidate()
                }
            }
            return true
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

        // Joystick dispatch follows the natural motion a real human makes on a virtual stick:
        //   1. Touch DOWN on the joystick base centre.
        //   2. Drag the touch to (centre + dx, centre + dy) — engine's "pushed" direction.
        //   3. Touch UP.
        // The whole gesture is one long swipe (see aiPush) instead of three short
        // continueStroke segments, which most Unity / SurfaceView games dropped because
        // the 16ms sub-strokes lifted the touch before the engine had time to interpret
        // the drag.

        private fun resizeWindow(radiusPx: Int) {
            val params = windowParams ?: return
            val newSize = radiusPx * 2 + dp(JOY_PADDING_DP.toFloat()).toInt()
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
            val rad = Math.toRadians(angleDeg.toDouble())
            val r = settings.joystickRadius.toFloat() * magnitude.coerceIn(0f, 1f)
            val dx = (r * cos(rad)).toFloat()
            val dy = (r * sin(rad)).toFloat()
            post {
                thumbDx = dx
                thumbDy = dy
                invalidate()

                val cx = centerScreenX()
                val cy = centerScreenY()
                val targetX = cx + dx
                val targetY = cy + dy

                // Visualize the push regardless of whether dispatch is enabled.
                runCatching {
                    com.aiagent.android.overlay.TapPulseService.pulseJoystick(context, cx, cy)
                    com.aiagent.android.overlay.TapPulseService.pulseJoystick(context, targetX, targetY)
                }

                if (settings.joystickDispatch) {
                    val service = AgentAccessibilityService.instance
                    if (service != null) {
                        // Single LONG stroke from base centre → pushed position. Many Unity /
                        // SurfaceView joysticks need the swipe to actually MOVE between
                        // cx,cy and (cx+dx, cy+dy) within a single stroke; the previous
                        // begin/continueStroke/end chain often dropped the drag because the
                        // 16ms strokes were too short and the system rolled back the touch
                        // before the continueStroke arrived.
                        runCatching {
                            service.swipeAsync(cx, cy, targetX, targetY, durationMs)
                        }
                    }
                }

                postDelayed({
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

        // Padding around the joystick base circle (in dp) — leaves room for the corner ⤡
        // resize handle and a tiny ✥ "drag-me" hint.
        private const val JOY_PADDING_DP = 32
        const val ACTION_SHOW = "com.aiagent.android.JOYSTICK_SHOW"
        const val ACTION_HIDE = "com.aiagent.android.JOYSTICK_HIDE"
        const val ACTION_RELAYOUT = "com.aiagent.android.JOYSTICK_RELAYOUT"
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

        /** Re-read the persisted X/Y/radius from [com.aiagent.android.data.Settings] and update
         *  the floating window. No-op when the joystick isn't currently shown. */
        fun relayout(context: Context) {
            context.startService(Intent(context, JoystickOverlayService::class.java).apply {
                action = ACTION_RELAYOUT
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
