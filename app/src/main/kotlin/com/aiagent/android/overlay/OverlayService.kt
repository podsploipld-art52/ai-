package com.aiagent.android.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import com.aiagent.android.data.Settings
import com.aiagent.android.stt.SpeechToText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Floating overlay window used by the agent to:
 *   - ask the user a yes/no question (`SHOW_QUESTION`)
 *   - show transient status (`SHOW_STATUS`)
 *   - render a persistent **STOP button** that is the ONLY way the user can terminate the agent.
 *     The agent itself cannot exit; the model's `done` tool is logged but ignored. The button is
 *     a separate, draggable overlay window placed above all apps; the AI cannot tap it because:
 *       a) `dispatchGesture` would have to reach the overlay's window which it can but
 *       b) we publish the on-screen bounds via [stopButtonBounds] and `tap_at` / `swipe_at`
 *          refuse to dispatch when the target falls inside that rectangle.
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var titleView: TextView? = null
    private var bodyView: TextView? = null
    private var buttonsRow: LinearLayout? = null
    private var voiceButton: Button? = null
    private var dismissButton: Button? = null
    private var voiceJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var stopRoot: View? = null
    private var settingsRoot: LinearLayout? = null
    private var settingsExpanded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_QUESTION -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
                val opts = intent.getStringArrayExtra(EXTRA_OPTIONS)?.toList()
                showQuestion(text, opts)
            }
            ACTION_SHOW_STATUS -> showStatus(intent.getStringExtra(EXTRA_TEXT) ?: "")
            ACTION_HIDE -> hideAll()
            ACTION_SHOW_STOP -> showStopButton()
            ACTION_HIDE_STOP -> hideStopButton()
            ACTION_SHOW_SETTINGS -> showSettingsButton()
            ACTION_HIDE_SETTINGS -> hideSettingsButton()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureView() {
        if (rootView != null) return
        val ctx: Context = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#1A237E")) // Indigo 900 — visible against most game UIs.
                setStroke(dp(2), Color.WHITE)
            }
        }

        titleView = TextView(ctx).apply {
            text = "AI Agent"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        bodyView = TextView(ctx).apply {
            text = ""
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(8))
        }

        // Buttons row is now built dynamically per question — see populateOptions().
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        buttonsRow = row

        container.addView(titleView)
        container.addView(bodyView)
        container.addView(row)

        rootView = container
        attachDragHandler(container)
    }

    /**
     * Render the answer buttons for a question. If [options] is null/empty we fall back to
     * the classic Да / Нет / 🎤 / ✕ row. With options, each becomes a labeled button (truncated
     * to ~16 chars). The mic button always gets shown — the user can answer by voice without
     * leaving the game.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun populateOptions(options: List<String>?) {
        val ctx: Context = this
        val row = buttonsRow ?: return
        row.removeAllViews()
        val effective = options?.take(6)?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        if (effective.isEmpty()) {
            // Default yes/no flow.
            row.addView(makeRowChild(makeButton(ctx, "Да", Color.parseColor("#1B5E20")) { deliver("yes") }))
            row.addView(makeRowChild(makeButton(ctx, "Нет", Color.parseColor("#B71C1C")) { deliver("no") }))
        } else {
            for ((idx, opt) in effective.withIndex()) {
                val short = if (opt.length > 22) opt.take(20) + "…" else opt
                val color = optionColor(idx)
                row.addView(makeRowChild(makeButton(ctx, short, color) { deliver(opt) }))
            }
        }
        voiceButton = makeButton(ctx, "🎤", Color.parseColor("#4527A0")) { startVoiceAnswer() }
        dismissButton = makeButton(ctx, "✕", Color.parseColor("#424242")) {
            cancelVoiceAnswer()
            deliver("dismiss")
            hideAll()
        }
        row.addView(makeRowChild(voiceButton!!))
        row.addView(makeRowChild(dismissButton!!))
    }

    private fun makeRowChild(btn: Button): Button {
        btn.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        ).apply { marginEnd = dp(4) }
        return btn
    }

    private fun optionColor(index: Int): Int = when (index % 6) {
        0 -> Color.parseColor("#1B5E20") // green
        1 -> Color.parseColor("#0D47A1") // blue
        2 -> Color.parseColor("#4A148C") // purple
        3 -> Color.parseColor("#BF360C") // orange-red
        4 -> Color.parseColor("#1B5E20") // green again
        else -> Color.parseColor("#37474F") // blue grey
    }

    private fun startVoiceAnswer() {
        val current = voiceJob
        if (current?.isActive == true) {
            // Tap-to-cancel.
            cancelVoiceAnswer()
            voiceButton?.text = "🎤"
            return
        }
        voiceButton?.text = "● слушаю"
        bodyView?.append("\n\n[слушаю — говори…]")
        voiceJob = serviceScope.launch {
            val stt = SpeechToText(applicationContext, Settings(applicationContext))
            val transcript = try {
                stt.listenLive(language = null)
            } catch (e: Exception) {
                "[ошибка распознавания: ${e.message ?: e::class.java.simpleName}]"
            }
            voiceButton?.text = "🎤"
            if (transcript.isNotBlank() && !transcript.startsWith("[")) {
                deliver(transcript)
            } else {
                bodyView?.append("\n$transcript — попробуй ещё раз")
            }
        }
    }

    private fun cancelVoiceAnswer() {
        voiceJob?.cancel()
        voiceJob = null
    }

    private fun makeButton(ctx: Context, text: String, bg: Int, onClick: (View) -> Unit): Button =
        Button(ctx).apply {
            this.text = text
            setTextColor(Color.WHITE)
            isAllCaps = false
            setBackgroundColor(bg)
            setOnClickListener(onClick)
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragHandler(container: View) {
        var startX = 0
        var startY = 0
        var rawX = 0f
        var rawY = 0f
        container.setOnTouchListener { _, ev ->
            val params = container.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    rawX = ev.rawX
                    rawY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (ev.rawX - rawX).toInt()
                    params.y = startY + (ev.rawY - rawY).toInt()
                    runCatching { windowManager?.updateViewLayout(container, params) }
                    true
                }
                else -> false
            }
        }
    }

    private fun showQuestion(text: String, options: List<String>?) {
        ensureView()
        val view = rootView ?: return
        if (view.parent == null) attach(view)
        titleView?.text = "Агент спрашивает"
        bodyView?.text = text
        populateOptions(options)
        buttonsRow?.visibility = View.VISIBLE
    }

    private fun showStatus(text: String) {
        ensureView()
        val view = rootView ?: return
        if (view.parent == null) attach(view)
        titleView?.text = "AI Agent"
        bodyView?.text = text
        // For status-only display we hide the buttons row.
        buttonsRow?.visibility = View.GONE
    }

    private fun attach(view: View) {
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.7f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(60)
        }
        val alpha = Settings(this).overlayAlpha.coerceIn(0.1f, 1.0f)
        view.alpha = alpha
        runCatching { windowManager?.addView(view, params) }
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun showStopButton() {
        if (stopRoot != null) return
        val ctx: Context = this
        windowManager = windowManager ?: getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val btn = Button(ctx).apply {
            text = "🛑 СТОП"
            setTextColor(Color.WHITE)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#C62828"))
                setStroke(dp(2), Color.WHITE)
            }
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener {
                // Fire the registered ViewModel callback. The button stays visible until the
                // ViewModel asks us to hide it (after it has cancelled the agent job).
                runCatching { stopListener?.invoke() }
            }
        }

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(80)
        }

        // Make the stop button draggable too — user requested "configurable".
        var startX = 0
        var startY = 0
        var rawX = 0f
        var rawY = 0f
        var dragged = false
        btn.setOnTouchListener { _, ev ->
            val lp = btn.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y; rawX = ev.rawX; rawY = ev.rawY; dragged = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - rawX).toInt()
                    val dy = (ev.rawY - rawY).toInt()
                    if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) {
                        dragged = true
                        // For Gravity.TOP|END, x grows toward the LEFT edge.
                        lp.x = (startX - dx).coerceAtLeast(0)
                        lp.y = (startY + dy).coerceAtLeast(0)
                        runCatching { windowManager?.updateViewLayout(btn, lp) }
                        updateStopButtonBounds(btn)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) true else false // consume only if dragged, else let click fire
                }
                else -> false
            }
        }
        runCatching { windowManager?.addView(btn, params) }
        stopRoot = btn
        // Capture bounds once layout is done.
        btn.post { updateStopButtonBounds(btn) }
    }

    private fun updateStopButtonBounds(view: View) {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        stopButtonBounds = Rect(
            loc[0],
            loc[1],
            loc[0] + view.width,
            loc[1] + view.height,
        )
    }

    private fun hideStopButton() {
        val v = stopRoot
        if (v != null) {
            runCatching { windowManager?.removeView(v) }
        }
        stopRoot = null
        stopButtonBounds = null
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun showSettingsButton() {
        if (settingsRoot != null) return
        val ctx: Context = this
        windowManager = windowManager ?: getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#EE263238"))
                setStroke(dp(2), Color.WHITE)
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val toggleBtn = Button(ctx).apply {
            text = "⚙️"
            setTextColor(Color.WHITE)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#5C6BC0"))
                setStroke(dp(2), Color.WHITE)
            }
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        rebuildSettingsPanel(panel)

        toggleBtn.setOnClickListener {
            settingsExpanded = !settingsExpanded
            panel.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
            if (settingsExpanded) rebuildSettingsPanel(panel)
        }
        container.addView(toggleBtn)
        container.addView(panel)

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(80)
        }

        // Drag the whole settings widget by long-press on the ⚙️ button.
        var startX = 0
        var startY = 0
        var rawX = 0f
        var rawY = 0f
        var dragged = false
        toggleBtn.setOnTouchListener { _, ev ->
            val lp = container.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y; rawX = ev.rawX; rawY = ev.rawY; dragged = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - rawX).toInt()
                    val dy = (ev.rawY - rawY).toInt()
                    if (kotlin.math.abs(dx) > dp(8) || kotlin.math.abs(dy) > dp(8)) {
                        dragged = true
                        lp.x = (startX + dx).coerceAtLeast(0)
                        lp.y = (startY + dy).coerceAtLeast(0)
                        runCatching { windowManager?.updateViewLayout(container, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> if (dragged) true else false
                else -> false
            }
        }

        runCatching { windowManager?.addView(container, params) }
        settingsRoot = container
    }

    private fun rebuildSettingsPanel(panel: LinearLayout) {
        panel.removeAllViews()
        val s = Settings(this)
        // Voice → start agent button. The most-used action when in-game.
        addStartAgentButton(panel)
        addSettingsToggle(panel, "🕹️ Джойстик", s.joystickEnabled) { value ->
            s.joystickEnabled = value
            if (value) JoystickOverlayService.show(applicationContext)
            else JoystickOverlayService.hide(applicationContext)
        }
        addSettingsToggle(panel, "👁️ Двухмодельный режим", s.useVisionDescriber) { value ->
            s.useVisionDescriber = value
        }
        addSettingsToggle(panel, "📸 Авто-скриншот", s.autoScreenshotEachTurn) { value ->
            s.autoScreenshotEachTurn = value
        }
        addSettingsToggle(panel, "⏸️ Пауза по «done»", s.autoPauseOnIdle) { value ->
            s.autoPauseOnIdle = value
        }
        addSettingsToggle(panel, "🎮 Передавать жест в игру", s.joystickDispatch) { value ->
            s.joystickDispatch = value
        }
    }

    @SuppressLint("SetTextI18n")
    private fun addStartAgentButton(parent: LinearLayout) {
        val ctx: Context = this
        val btn = Button(ctx).apply {
            text = if (agentRunning) "🎤 Сказать (продолжить)" else "🎤 Сказать задачу"
            setTextColor(Color.WHITE)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#4527A0"))
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.bottomMargin = dp(4)
            layoutParams = lp
        }
        var listening = false
        var listenScope: CoroutineScope? = null
        btn.setOnClickListener {
            if (listening) {
                listenScope?.coroutineContext?.get(Job)?.cancel()
                listening = false
                btn.text = if (agentRunning) "🎤 Сказать (продолжить)" else "🎤 Сказать задачу"
                (btn.background as? GradientDrawable)?.setColor(Color.parseColor("#4527A0"))
                return@setOnClickListener
            }
            listening = true
            btn.text = "● слушаю…  (тапни чтоб отменить)"
            (btn.background as? GradientDrawable)?.setColor(Color.parseColor("#C62828"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            listenScope = scope
            scope.launch {
                val transcript = try {
                    SpeechToText(applicationContext, Settings(applicationContext)).listenLive(language = null)
                } catch (e: Exception) {
                    "[ошибка распознавания: ${e.message ?: e::class.java.simpleName}]"
                }
                listening = false
                btn.text = if (agentRunning) "🎤 Сказать (продолжить)" else "🎤 Сказать задачу"
                (btn.background as? GradientDrawable)?.setColor(Color.parseColor("#4527A0"))
                if (transcript.isNotBlank() && !transcript.startsWith("[")) {
                    runCatching { startListener?.invoke(transcript) }
                }
            }
        }
        parent.addView(btn)
    }

    private fun addSettingsToggle(
        parent: LinearLayout,
        label: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        val ctx: Context = this
        var current = initial
        val btn = Button(ctx).apply {
            text = renderToggle(label, current)
            setTextColor(Color.WHITE)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (current) Color.parseColor("#388E3C") else Color.parseColor("#616161"))
            }
            setPadding(dp(10), dp(6), dp(10), dp(6))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = dp(4)
            layoutParams = lp
        }
        btn.setOnClickListener {
            current = !current
            onChange(current)
            btn.text = renderToggle(label, current)
            (btn.background as? GradientDrawable)?.setColor(
                if (current) Color.parseColor("#388E3C") else Color.parseColor("#616161"),
            )
        }
        parent.addView(btn)
    }

    private fun renderToggle(label: String, value: Boolean): String =
        if (value) "$label  ✓" else "$label  —"

    private fun hideSettingsButton() {
        val v = settingsRoot
        if (v != null) runCatching { windowManager?.removeView(v) }
        settingsRoot = null
        settingsExpanded = false
    }

    private fun hideAll() {
        cancelVoiceAnswer()
        val view = rootView
        if (view != null) {
            runCatching { windowManager?.removeView(view) }
        }
        rootView = null
        titleView = null
        bodyView = null
        buttonsRow = null
        voiceButton = null
        dismissButton = null
    }

    override fun onDestroy() {
        super.onDestroy()
        hideAll()
        hideStopButton()
        hideSettingsButton()
    }

    private fun deliver(value: String) {
        val def = Pending.deferred
        if (def != null && !def.isCompleted) {
            def.complete(value)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * Singleton bridge between the agent (caller) and the OverlayService (UI).
     *
     * Set [deferred] before starting the service; the user's button choice will be delivered
     * into it. Cleared when the agent reads the value.
     */
    object Pending {
        @Volatile
        var deferred: CompletableDeferred<String>? = null
    }

    companion object {
        const val ACTION_SHOW_QUESTION = "com.aiagent.android.OVERLAY_QUESTION"
        const val ACTION_SHOW_STATUS = "com.aiagent.android.OVERLAY_STATUS"
        const val ACTION_HIDE = "com.aiagent.android.OVERLAY_HIDE"
        const val ACTION_SHOW_STOP = "com.aiagent.android.OVERLAY_SHOW_STOP"
        const val ACTION_HIDE_STOP = "com.aiagent.android.OVERLAY_HIDE_STOP"
        const val ACTION_SHOW_SETTINGS = "com.aiagent.android.OVERLAY_SHOW_SETTINGS"
        const val ACTION_HIDE_SETTINGS = "com.aiagent.android.OVERLAY_HIDE_SETTINGS"
        const val EXTRA_TEXT = "text"
        const val EXTRA_OPTIONS = "options"

        /** Screen-space bounds of the persistent STOP button while it's visible. Used by
         *  AgentAccessibilityService to refuse `tap_at` / `swipe_at` calls that would land on it. */
        @Volatile
        var stopButtonBounds: Rect? = null

        /** Invoked when the user taps the persistent STOP overlay button. Set by MainViewModel. */
        @Volatile
        var stopListener: (() -> Unit)? = null

        /** Invoked when the user gives a fresh voice instruction inside the floating ⚙️ panel.
         *  The string is the (already-transcribed) instruction. Set by MainViewModel. */
        @Volatile
        var startListener: ((String) -> Unit)? = null

        /** True while the agent is running, set by MainViewModel. The settings overlay reads
         *  this to decide whether the action button should be "🎤 Запустить" or "▶️ Продолжить". */
        @Volatile
        var agentRunning: Boolean = false

        fun showQuestion(context: Context, text: String, options: List<String>? = null) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_SHOW_QUESTION
                putExtra(EXTRA_TEXT, text)
                if (!options.isNullOrEmpty()) {
                    putExtra(EXTRA_OPTIONS, options.toTypedArray())
                }
            }
            context.startService(intent)
        }

        fun showStatus(context: Context, text: String) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_SHOW_STATUS
                putExtra(EXTRA_TEXT, text)
            }
            context.startService(intent)
        }

        fun hide(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply { action = ACTION_HIDE }
            context.startService(intent)
        }

        fun showStop(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply { action = ACTION_SHOW_STOP }
            context.startService(intent)
        }

        fun hideStop(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply { action = ACTION_HIDE_STOP }
            context.startService(intent)
        }

        /** Show the persistent floating ⚙️ Settings button + expandable panel on top of every app. */
        fun showSettings(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply { action = ACTION_SHOW_SETTINGS }
            context.startService(intent)
        }

        fun hideSettings(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply { action = ACTION_HIDE_SETTINGS }
            context.startService(intent)
        }

        /**
         * Apply window-level brightness to the overlay view (if currently shown). `value` should be
         * in [0..1] for an explicit level or -1 to follow system. Has no effect when the overlay
         * is not currently visible — Android does not provide a way to change brightness of other
         * apps without WRITE_SETTINGS, which is intentionally not declared.
         */
        fun applyBrightness(@Suppress("UNUSED_PARAMETER") context: Context, value: Float) {
            pendingBrightness = value
        }

        @Volatile
        var pendingBrightness: Float = -1f
    }
}
