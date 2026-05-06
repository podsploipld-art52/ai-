package com.aiagent.android.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Tiny overlay that paints transient "tap pulses" on top of every app, so the user can
 * physically see where the AI is firing tap_at / swipe_at / joystick gestures. The pulses
 * are PURELY decorative — they're rendered above the AI's accessibility-dispatched touch
 * events, not in place of them, and the host overlay is non-touchable so the gesture
 * passes straight through to the underlying app.
 *
 * Pulse styles:
 *   - "tap"      → solid yellow circle that expands and fades.
 *   - "swipe"    → a stroked line from (x1,y1) to (x2,y2) with two end pulses.
 *   - "joystick" → a thicker amber pulse, slightly larger.
 *
 * Each pulse is added as a child View into a fullscreen container; once its animation ends
 * the View removes itself.
 */
class TapPulseService : Service() {

    private var windowManager: WindowManager? = null
    private var rootView: PulseLayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PULSE -> {
                val kind = intent.getStringExtra(EXTRA_KIND) ?: "tap"
                val x = intent.getFloatExtra(EXTRA_X, -1f)
                val y = intent.getFloatExtra(EXTRA_Y, -1f)
                val x2 = intent.getFloatExtra(EXTRA_X2, Float.NaN)
                val y2 = intent.getFloatExtra(EXTRA_Y2, Float.NaN)
                if (x >= 0f && y >= 0f) ensureLayer()?.spawn(kind, x, y, x2, y2)
            }
            ACTION_HIDE -> hide()
        }
        return START_NOT_STICKY
    }

    private fun ensureLayer(): PulseLayer? {
        rootView?.let { return it }
        val wm = windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        windowManager = wm

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        // FLAG_NOT_TOUCHABLE → finger pokes pass straight through to the app under us. We
        // never receive touch events here; we only render pulses.
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        val layer = PulseLayer(this)
        runCatching { wm.addView(layer, params) }
            .onFailure { Log.w(TAG, "Failed to add pulse layer: ${it.message}") }
        rootView = layer
        return layer
    }

    private fun hide() {
        val v = rootView ?: return
        runCatching { windowManager?.removeView(v) }
        rootView = null
    }

    override fun onDestroy() {
        super.onDestroy()
        hide()
    }

    /**
     * Fullscreen invisible container. Each pulse is a child View whose `onDraw` paints a
     * single circle/line at view-local coords; the View removes itself after its animation
     * finishes. We use child Views (not a single canvas with a list of pulses) so each pulse
     * is independent and we don't have to manage a redraw clock.
     */
    private class PulseLayer(ctx: Context) : View(ctx) {

        // Track the spawned pulse views via their own Animators which call view.invalidate()
        // every frame. We keep a list so we know how big the layer's redraw region should be.
        private val activePulses = mutableListOf<Pulse>()

        private val displayMetrics get() = resources.displayMetrics

        init {
            // The layer itself is fully transparent; only the children paint.
            setWillNotDraw(false)
            setBackgroundColor(Color.TRANSPARENT)
        }

        fun spawn(kind: String, x: Float, y: Float, x2: Float, y2: Float) {
            val p = Pulse(kind, x, y, x2, y2, dpScale = displayMetrics.density)
            p.onUpdate = { invalidate() }
            activePulses.add(p)
            p.start { activePulses.remove(p); invalidate() }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Draw oldest first → newest on top.
            for (p in activePulses) p.draw(canvas)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            // Force redraw cycle while there are active pulses. Each Pulse's animator will
            // call invalidate() on update.
        }
    }

    /** A single tap / swipe / joystick pulse. */
    private class Pulse(
        val kind: String,
        val x: Float,
        val y: Float,
        val x2: Float,
        val y2: Float,
        val dpScale: Float,
    ) {
        private val durationMs = when (kind) {
            "swipe" -> 800L
            "joystick" -> 600L
            else -> 700L
        }

        private val baseColor = when (kind) {
            "joystick" -> Color.parseColor("#FFFFC107") // amber
            "swipe" -> Color.parseColor("#FF03A9F4")    // light blue
            else -> Color.parseColor("#FFFFFF00")       // yellow
        }
        private val strokeColor = Color.parseColor("#FFFFFFFF")

        private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = baseColor
        }
        private val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * dpScale
            color = strokeColor
        }
        private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f * dpScale
            color = baseColor
        }

        private var progress: Float = 0f // 0..1
        private var animator: ValueAnimator? = null

        fun start(onEnd: () -> Unit) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                addUpdateListener {
                    progress = it.animatedValue as Float
                    // Force a redraw on every update via the parent.
                    onUpdate?.invoke()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        onEnd()
                    }
                })
                start()
            }
        }

        // Set by PulseLayer so each frame triggers a layer-level invalidate.
        var onUpdate: (() -> Unit)? = null

        fun draw(canvas: Canvas) {
            val t = progress
            val maxRadius = when (kind) {
                "joystick" -> 90f * dpScale
                "swipe" -> 35f * dpScale
                else -> 60f * dpScale
            }
            val r = maxRadius * t
            val alpha = (255 * (1f - t)).toInt().coerceIn(0, 255)
            paintFill.alpha = (alpha * 0.55f).toInt().coerceIn(0, 255)
            paintStroke.alpha = alpha
            paintLine.alpha = (255 * (1f - 0.6f * t)).toInt().coerceIn(0, 255)

            if (kind == "swipe" && !x2.isNaN() && !y2.isNaN()) {
                // Animate a "growing" line from (x,y) toward (x2,y2).
                val px = x + (x2 - x) * t
                val py = y + (y2 - y) * t
                canvas.drawLine(x, y, px, py, paintLine)
                // Pulse at start.
                canvas.drawCircle(x, y, 18f * dpScale * (1f - t * 0.5f), paintFill)
                canvas.drawCircle(x, y, 18f * dpScale * (1f - t * 0.5f), paintStroke)
                // Pulse at the moving head.
                canvas.drawCircle(px, py, 14f * dpScale, paintFill)
            } else {
                canvas.drawCircle(x, y, r, paintFill)
                canvas.drawCircle(x, y, r, paintStroke)
                // Inner solid dot so the start point stays visible.
                paintFill.alpha = (255 * (1f - t * 0.7f)).toInt().coerceIn(0, 255)
                canvas.drawCircle(x, y, 8f * dpScale, paintFill)
            }
        }
    }

    companion object {
        private const val TAG = "TapPulseService"
        const val ACTION_PULSE = "com.aiagent.android.PULSE"
        const val ACTION_HIDE = "com.aiagent.android.PULSE_HIDE"
        const val EXTRA_KIND = "kind"
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"
        const val EXTRA_X2 = "x2"
        const val EXTRA_Y2 = "y2"

        /** True if the user has overlay permission (or pre-Marshmallow). Fallback: just try. */
        fun pulseTap(context: Context, x: Int, y: Int) =
            send(context, "tap", x.toFloat(), y.toFloat(), Float.NaN, Float.NaN)

        fun pulseSwipe(context: Context, x1: Int, y1: Int, x2: Int, y2: Int) =
            send(context, "swipe", x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())

        fun pulseJoystick(context: Context, x: Float, y: Float) =
            send(context, "joystick", x, y, Float.NaN, Float.NaN)

        fun hide(context: Context) {
            runCatching {
                context.startService(Intent(context, TapPulseService::class.java).apply {
                    action = ACTION_HIDE
                })
            }
        }

        private fun send(context: Context, kind: String, x: Float, y: Float, x2: Float, y2: Float) {
            runCatching {
                context.startService(Intent(context, TapPulseService::class.java).apply {
                    action = ACTION_PULSE
                    putExtra(EXTRA_KIND, kind)
                    putExtra(EXTRA_X, x)
                    putExtra(EXTRA_Y, y)
                    putExtra(EXTRA_X2, x2)
                    putExtra(EXTRA_Y2, y2)
                })
            }
        }
    }
}
