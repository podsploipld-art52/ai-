package com.aiagent.android.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.Executors

/**
 * Accessibility service that exposes a high-level API for the agent to inspect the screen
 * and perform UI actions (tap, swipe, type, system navigation).
 *
 * It does not contain any LLM logic — it only provides the bridge between the agent
 * and the system's accessibility surface.
 */
class AgentAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "AgentAccessibilityService connected")
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We do not need to react to every event for the agent's pull-based model.
        // Subscribers (UI) can read state on demand via captureScreenState().
    }

    override fun onInterrupt() {
        Log.w(TAG, "AgentAccessibilityService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "AgentAccessibilityService unbound")
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    /** Capture a textual snapshot of the currently visible UI tree. */
    fun captureScreenState(): ScreenState {
        val root = rootInActiveWindow ?: return ScreenState(
            packageName = "<no-window>",
            description = "(no active window)",
            nodes = emptyList(),
        )
        val pkg = root.packageName?.toString() ?: "<unknown>"
        val nodes = mutableListOf<UiNode>()
        var index = 0
        val builder = StringBuilder()
        builder.append("App: ").append(pkg).append('\n')
        traverse(root, depth = 0, builder = builder, nodes = nodes) { id ->
            id.also { index++ }
        }
        // Recycle root to avoid leaks. Children inside `nodes` keep their own reference
        // for later interaction; we copy bounds and ids and then recycle them at the end.
        return ScreenState(
            packageName = pkg,
            description = builder.toString().take(MAX_DESCRIPTION_CHARS),
            nodes = nodes,
        )
    }

    private fun traverse(
        node: AccessibilityNodeInfo,
        depth: Int,
        builder: StringBuilder,
        nodes: MutableList<UiNode>,
        nextId: (Int) -> Int,
    ) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()
        val viewId = node.viewIdResourceName
        val cls = node.className?.toString()?.substringAfterLast('.')
        val clickable = node.isClickable
        val editable = node.isEditable
        val scrollable = node.isScrollable
        val checkable = node.isCheckable
        val checked = node.isChecked

        val visible = rect.width() > 0 && rect.height() > 0
        val isInteresting = visible && (
            !text.isNullOrBlank() ||
                !contentDesc.isNullOrBlank() ||
                clickable ||
                editable ||
                scrollable
            )

        if (isInteresting) {
            val id = nodes.size
            val node1 = UiNode(
                id = id,
                cls = cls ?: "",
                text = text,
                contentDesc = contentDesc,
                viewId = viewId,
                bounds = rect,
                clickable = clickable,
                editable = editable,
                scrollable = scrollable,
                checkable = checkable,
                checked = checked,
            )
            nodes.add(node1)
            // Build textual line
            repeat(depth) { builder.append("  ") }
            builder.append('[').append(id).append("] ")
            builder.append(cls ?: "View")
            text?.let { builder.append(" text=\"").append(it.take(120)).append('"') }
            contentDesc?.let { builder.append(" desc=\"").append(it.take(80)).append('"') }
            viewId?.let { builder.append(" id=").append(it.substringAfterLast('/')) }
            if (clickable) builder.append(" clickable")
            if (editable) builder.append(" editable")
            if (scrollable) builder.append(" scrollable")
            if (checkable) builder.append(" checked=").append(checked)
            builder.append(" @").append(rect.flattenToString())
            builder.append('\n')
            nextId(id)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverse(child, depth + if (isInteresting) 1 else 0, builder, nodes, nextId)
        }
    }

    /** Tap at the given screen coordinates. Returns true if the gesture dispatched successfully. */
    suspend fun tap(x: Int, y: Int, durationMs: Long = 60L): Boolean {
        // Visual pulse so the user can see where the AI is tapping.
        runCatching { com.aiagent.android.overlay.TapPulseService.pulseTap(this, x, y) }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        return dispatchAndWait(gesture)
    }

    /** Swipe between two points. */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300L): Boolean {
        runCatching { com.aiagent.android.overlay.TapPulseService.pulseSwipe(this, x1, y1, x2, y2) }
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        return dispatchAndWait(gesture)
    }

    /** Fire-and-forget swipe variant — used by overlays (e.g. joystick) that can't run a
     *  suspending function. Kicks off the gesture without awaiting completion. Returns
     *  immediately; the gesture finishes after [durationMs]. */
    fun swipeAsync(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        runCatching {
            com.aiagent.android.overlay.TapPulseService.pulseSwipe(
                this, x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(),
            )
        }
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        return runCatching {
            dispatchGesture(gesture, null, mainHandler)
        }.getOrDefault(false)
    }

    // --- Continuous "joystick" drag --------------------------------------------------------------
    // The joystick overlay calls these to keep a single touch-down active in the underlying app
    // while the user (or AI) moves the thumb. Each segment is dispatched with willContinue=true
    // so the touch is not lifted between updates; the final segment uses willContinue=false.

    @Volatile
    private var jsLastStroke: GestureDescription.StrokeDescription? = null
    @Volatile
    private var jsLastX: Float = 0f
    @Volatile
    private var jsLastY: Float = 0f
    private val jsLock = Any()

    /** Begin a joystick stroke at the given screen coordinates. */
    fun joystickBegin(x: Float, y: Float, durationMs: Long = 16L) {
        synchronized(jsLock) {
            val path = Path().apply {
                moveTo(x, y)
                // Tiny offset so the stroke has non-zero length; many engines drop zero-length paths.
                lineTo(x + 0.001f, y + 0.001f)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs, true)
            val ok = dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                null,
                mainHandler,
            )
            if (ok) {
                jsLastStroke = stroke
                jsLastX = x
                jsLastY = y
            } else {
                jsLastStroke = null
            }
        }
    }

    /** Continue a joystick stroke to a new position. */
    fun joystickUpdate(x: Float, y: Float, durationMs: Long = 16L) {
        synchronized(jsLock) {
            val previous = jsLastStroke ?: return
            val path = Path().apply {
                moveTo(jsLastX, jsLastY)
                lineTo(x, y)
            }
            val next = previous.continueStroke(path, 0L, durationMs, true)
            val ok = dispatchGesture(
                GestureDescription.Builder().addStroke(next).build(),
                null,
                mainHandler,
            )
            if (ok) {
                jsLastStroke = next
                jsLastX = x
                jsLastY = y
            } else {
                jsLastStroke = null
            }
        }
    }

    /** End a joystick stroke (touch is lifted). */
    fun joystickEnd(x: Float, y: Float, durationMs: Long = 16L) {
        synchronized(jsLock) {
            val previous = jsLastStroke
            if (previous != null) {
                val path = Path().apply {
                    moveTo(jsLastX, jsLastY)
                    lineTo(x, y)
                }
                val finish = previous.continueStroke(path, 0L, durationMs, false)
                runCatching {
                    dispatchGesture(
                        GestureDescription.Builder().addStroke(finish).build(),
                        null,
                        mainHandler,
                    )
                }
            }
            jsLastStroke = null
        }
    }

    /** Tap a captured node (by id from the most-recent ScreenState). */
    suspend fun tapNode(node: UiNode): Boolean {
        val cx = node.bounds.centerX()
        val cy = node.bounds.centerY()
        return tap(cx, cy)
    }

    /**
     * Type text into the currently focused editable node. Returns true on success.
     *
     * Strategy ordering:
     *  1. Find a focused EditText in any open window and call ACTION_SET_TEXT or paste.
     *     Works for native apps and apps that expose an accessibility-friendly text field
     *     (including some Unity titles whose hidden TouchScreenKeyboard EditText is
     *     reachable via the IME window).
     *  2. If no editable node is reachable but the on-screen IME is visible (Unity / Flutter
     *     SurfaceView games like Among Us), tap the keys one-by-one. This is much slower but
     *     is the only way to put text into a non-accessibility chat box.
     */
    suspend fun typeText(text: String): Boolean {
        val focused = findFocusedEditable()
        if (focused != null && setOrPasteText(focused, text)) return true
        // Last-resort: type by tapping the on-screen keyboard.
        return typeViaImeKeys(text)
    }

    /** Type text into a specific node (must be editable). */
    fun typeTextInNode(node: UiNode, text: String): Boolean {
        val accNode = findNodeByBounds(node.bounds) ?: return false
        if (!accNode.isEditable) return false
        // Make sure it has focus first so the IME / Compose pipeline accepts the change.
        accNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        accNode.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        return setOrPasteText(accNode, text)
    }

    /**
     * Try multiple strategies to put text into the given node:
     * 1. ACTION_SET_TEXT (works for native EditText)
     * 2. Clipboard + ACTION_PASTE (works for Compose TextField, WebView inputs, etc. that
     *    silently ignore SET_TEXT)
     */
    private fun setOrPasteText(node: AccessibilityNodeInfo, text: String): Boolean {
        // Strategy 1: ACTION_SET_TEXT replaces the full text content.
        // We trust the platform's return value here. Reading back node.text after the call
        // frequently returns stale data (the node info object is a snapshot taken before the
        // edit was applied), which used to make us run the clipboard-paste fallback as well
        // and end up with the text inserted twice.
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val setOk = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
            .getOrDefault(false)
        if (setOk) return true
        // Strategy 2: clipboard paste — used only when SET_TEXT actually refused (Compose
        // TextField, WebView inputs, custom IME-only fields).
        return pasteViaClipboard(node, text)
    }

    private fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): Boolean {
        return runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ai-agent", text))
            // Make sure the node is focused before pasting.
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }.getOrDefault(false)
    }

    /**
     * Walk every open window (not just the active root) looking for an editable node. Some
     * apps — notably Unity TouchScreenKeyboard, browser address bars while focused, and
     * sub-window dialogs — host their EditText in a different window than the foreground
     * activity, so [rootInActiveWindow] alone misses them.
     */
    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        runCatching { rootInActiveWindow?.let { roots.add(it) } }
        runCatching {
            windows?.forEach { w ->
                val r = runCatching { w.root }.getOrNull() ?: return@forEach
                if (roots.none { it == r }) roots.add(r)
            }
        }
        // First pass: focused editable.
        for (root in roots) {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.addLast(root)
            while (stack.isNotEmpty()) {
                val node = stack.removeLast()
                if (node.isEditable && node.isFocused) return node
                for (i in 0 until node.childCount) {
                    val c = node.getChild(i) ?: continue
                    stack.addLast(c)
                }
            }
        }
        // Fall back to first editable node anywhere.
        for (root in roots) {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.addLast(root)
            while (stack.isNotEmpty()) {
                val node = stack.removeLast()
                if (node.isEditable) return node
                for (i in 0 until node.childCount) {
                    val c = node.getChild(i) ?: continue
                    stack.addLast(c)
                }
            }
        }
        return null
    }

    /**
     * IME-tap fallback: when the foreground app does NOT expose an editable accessibility
     * node (e.g. Unity / Flutter / SurfaceView chat in Among Us), but the on-screen
     * keyboard IS visible as a separate accessibility window, we type the text by walking
     * the IME tree and tapping each key by its bounds.
     *
     * Limitations:
     *  - Only ASCII letters / digits / common punctuation reliably map to IME key labels.
     *  - Mixed-case typing requires a Shift key; we attempt to find one and tap it before
     *    each uppercase character. Cyrillic / other layouts work if the IME is already in
     *    that mode and the labels match.
     *  - Slow: ~150 ms per character. Fine for a one-off chat message; not for paragraphs.
     */
    private suspend fun typeViaImeKeys(text: String): Boolean {
        val imeRoot = findImeRoot() ?: return false
        // Build a map: key label (lowercase) → screen-bounds.
        val keyMap = mutableMapOf<String, Rect>()
        collectKeys(imeRoot, keyMap)
        if (keyMap.isEmpty()) return false
        var success = true
        for (ch in text) {
            val matched = matchKey(ch, keyMap)
            if (matched == null) {
                Log.w(TAG, "IME-typing: no key for char '$ch'")
                success = false
                continue
            }
            // Mixed case: try to flip Shift on uppercase, off on lowercase.
            if (ch.isUpperCase()) keyMap["shift"]?.let { tap(it.centerX(), it.centerY()) }
            tap(matched.centerX(), matched.centerY())
            // Tiny delay so the IME registers each tap separately.
            kotlinx.coroutines.delay(60L)
        }
        return success
    }

    private fun findImeRoot(): AccessibilityNodeInfo? {
        val ws = runCatching { windows }.getOrNull() ?: return null
        for (w in ws) {
            if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                val r = runCatching { w.root }.getOrNull()
                if (r != null) return r
            }
        }
        return null
    }

    private fun collectKeys(root: AccessibilityNodeInfo, out: MutableMap<String, Rect>) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val label = (node.text?.toString() ?: node.contentDescription?.toString())
                ?.trim()?.lowercase()
            if (!label.isNullOrEmpty() && (node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) out.putIfAbsent(label, r)
            }
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                stack.addLast(c)
            }
        }
    }

    private fun matchKey(ch: Char, keyMap: Map<String, Rect>): Rect? {
        val needle = when (ch) {
            ' ' -> "space"
            '\n' -> "enter"
            else -> ch.lowercaseChar().toString()
        }
        keyMap[needle]?.let { return it }
        // Some IMEs label keys with extended descriptions, e.g. "a key" or "0 key".
        for ((label, rect) in keyMap) {
            if (label == needle || label.startsWith("$needle ") || label.endsWith(" $needle")) {
                return rect
            }
        }
        return null
    }

    private fun findNodeByBounds(bounds: Rect): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r == bounds) return node
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                stack.addLast(c)
            }
        }
        return null
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun pullNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    /**
     * Capture a still image of the current screen using the [AccessibilityService.takeScreenshot]
     * API (Android 11+). Returns a software [Bitmap] suitable for ML Kit / display.
     *
     * On older platforms or on failure, this returns null and the caller should fall back to
     * MediaProjection or the Accessibility tree.
     */
    suspend fun captureBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        val deferred = CompletableDeferred<Bitmap?>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hwBuffer: HardwareBuffer = screenshot.hardwareBuffer
                        val bm = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                        // Convert to software bitmap so callers can read pixels (ML Kit does not
                        // accept HARDWARE-config bitmaps directly).
                        val sw = bm?.copy(Bitmap.Config.ARGB_8888, false)
                        runCatching { hwBuffer.close() }
                        deferred.complete(sw)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "takeScreenshot failed: $errorCode")
                        deferred.complete(null)
                    }
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "takeScreenshot threw", e)
            deferred.complete(null)
        }
        val bm = deferred.await()
        executor.shutdown()
        return bm
    }

    /**
     * Tap by coordinates that target a specific keyboard-key region. Used by the keyboard-fallback
     * path when accessibility setText is rejected by the foreground IME (e.g. some Compose
     * fields, WebView inputs in Chrome custom tabs).
     */
    suspend fun typeViaTaps(text: String, keyToBounds: Map<Char, Rect>): Boolean {
        var allOk = true
        for (ch in text) {
            val bounds = keyToBounds[ch] ?: keyToBounds[ch.lowercaseChar()]
            if (bounds == null) {
                allOk = false
            } else {
                allOk = allOk and tap(bounds.centerX(), bounds.centerY())
                // Small delay so the IME has time to register taps.
                kotlinx.coroutines.delay(40)
            }
        }
        return allOk
    }

    private suspend fun dispatchAndWait(gesture: GestureDescription): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val ok = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    deferred.complete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    deferred.complete(false)
                }
            },
            mainHandler,
        )
        if (!ok) return false
        return deferred.await()
    }

    companion object {
        private const val TAG = "AgentA11yService"
        private const val MAX_DESCRIPTION_CHARS = 12000

        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}

data class ScreenState(
    val packageName: String,
    val description: String,
    val nodes: List<UiNode>,
)

data class UiNode(
    val id: Int,
    val cls: String,
    val text: String?,
    val contentDesc: String?,
    val viewId: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
)
