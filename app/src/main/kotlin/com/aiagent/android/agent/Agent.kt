package com.aiagent.android.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.util.Log
import com.aiagent.android.audio.MicRecorder
import com.aiagent.android.data.Settings
import com.aiagent.android.device.DeviceInfo
import com.aiagent.android.files.FileTools
import com.aiagent.android.llm.ChatMessage
import com.aiagent.android.llm.ChatRequest
import com.aiagent.android.llm.LlmClient
import com.aiagent.android.llm.LlmException
import com.aiagent.android.llm.FunctionCall
import com.aiagent.android.llm.ToolCall
import com.aiagent.android.llm.textMessage
import com.aiagent.android.llm.userImageMessage
import com.aiagent.android.ocr.OcrEngine
import com.aiagent.android.overlay.OverlayService
import com.aiagent.android.service.AgentAccessibilityService
import com.aiagent.android.service.ScreenCaptureService
import com.aiagent.android.service.ScreenState
import com.aiagent.android.stt.SpeechToText
import com.aiagent.android.tts.TtsManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drives the LLM <-> device interaction loop.
 *
 *  1. Send the user's instruction + system prompt to the LLM with the available tool schemas.
 *  2. The LLM returns one or more tool calls.
 *  3. Each tool call is executed against the AccessibilityService / OS APIs.
 *  4. The tool results are appended to the conversation and we loop until the LLM calls `done`
 *     (or we exceed [Settings.maxSteps]).
 */
class Agent(
    private val context: Context,
    private val settings: Settings,
    private val askUser: suspend (String) -> String,
    private val startScreenRecording: suspend () -> String,
    private val stopScreenRecording: suspend () -> String,
    /**
     * Called when the Accessibility `takeScreenshot()` API can't deliver a bitmap (e.g. on
     * Realme / Vivo / older API ROMs that block it for non-system services). The implementer is
     * expected to ask the user for MediaProjection consent and start the [ScreenCaptureService].
     * Returns true if a capture service is now running, false if the user denied consent or the
     * device doesn't support MediaProjection at all. Subsequent screenshots will be served from
     * [ScreenCaptureService.captureFrame].
     */
    private val ensureCaptureService: suspend () -> Boolean,
    /**
     * Called after every successful live-mode camera capture with the absolute path of the
     * JPEG, so the UI can display a preview ("камера видит это"). Path is null if the capture
     * failed for the current turn.
     */
    private val onLiveFrame: (String?) -> Unit = {},
    private val onLog: suspend (AgentLog) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Lazy-initialised heavy components.
    private val tts: TtsManager by lazy { TtsManager(context) }
    private val stt: SpeechToText by lazy { SpeechToText(context, settings) }
    private val fileTools: FileTools by lazy { FileTools(context, settings) }
    private val projectTools: com.aiagent.android.files.ProjectTools by lazy {
        com.aiagent.android.files.ProjectTools(context)
    }
    private val micRecorder = MicRecorder()
    private val liveCapturer: LiveTurnCapturer by lazy {
        LiveTurnCapturer(context, settings, stt, micRecorder)
    }

    @Volatile private var lastScreenshotMs: Long = 0L

    /** Set to true the first time the user denies MediaProjection consent in a run. We don't
     *  pester them again on subsequent screenshot requests within the same run. */
    @Volatile private var projectionDenied: Boolean = false

    /** Path of the most recent take_camera_photo capture that hasn't been folded into a vision
     *  message yet. Drained on the next iteration of the agent loop. */
    @Volatile private var pendingCameraPhoto: String? = null

    /**
     * Run the agent loop using an externally-owned conversation list. The caller (typically
     * MainViewModel) keeps this list across runs so the user can press "Продолжить" without
     * losing previous context. When [conversation] is empty we seed it with system+user; when
     * it already has content we just append the new user instruction and continue.
     *
     * The loop is "user-only-exit": the agent never auto-terminates the conversation. When the
     * model calls `done` or stops producing tool calls we simply pause (return from this fun);
     * the conversation stays in memory so the user can press «Продолжить» with a new instruction
     * to resume, or tap the floating STOP overlay to truly terminate. The only ways out are a
     * CancellationException (user pressed STOP) or the [Settings.maxSteps] hard cap.
     */
    suspend fun run(
        userInstruction: String,
        conversation: MutableList<ChatMessage>,
    ) {
        val service = AgentAccessibilityService.instance
        if (service == null) {
            onLog(AgentLog.Error("Служба Спецвозможностей не запущена. Включите в Настройки Android → Спецвозможности → AI Agent."))
            return
        }
        if (settings.apiKey.isBlank()) {
            onLog(AgentLog.Error("API-ключ не задан. Укажите его на вкладке Настройки."))
            return
        }

        val client = LlmClient(settings.baseUrl, settings.apiKey)
        val systemPrompt = settings.systemPrompt.takeIf { it.isNotBlank() } ?: SYSTEM_PROMPT
        if (conversation.isEmpty()) {
            conversation.add(textMessage(role = "system", text = systemPrompt))
        }
        if (userInstruction.isNotBlank()) {
            conversation.add(textMessage(role = "user", text = userInstruction))
        }
        val messages = conversation
        var lastScreenState: ScreenState? = null

        try {
            for (step in 1..settings.maxSteps) {
                onLog(AgentLog.Thinking(step))
                // Drain any pending voice/text interruptions the user pushed via the floating
                // overlay while the agent was busy. Each becomes a fresh user message that the
                // controller sees on this step.
                while (true) {
                    val interruption = userInterrupts.poll() ?: break
                    if (interruption.isNotBlank()) {
                        messages.add(textMessage(role = "user", text = interruption))
                        onLog(AgentLog.System("Получено сообщение от пользователя: $interruption"))
                    }
                }
                // Game-mode: get a fresh view of the screen before each model call.
                // Two paths:
                //  (a) Single-model: inject the raw image straight into the controller's
                //      history. Requires a vision-capable controller model.
                //  (b) Two-model (vision describer): ask a separate vision model to write a
                //      textual description of the screenshot and inject the *text* — not the
                //      image — into the controller's history. Lets a powerful but text-only
                //      controller (e.g. gpt-oss-120b) drive the agent while a smaller vision
                //      model handles pixel parsing.
                if (settings.autoScreenshotEachTurn) {
                    val frame = throttleAndCapture(service)
                    if (frame != null) {
                        if (settings.useVisionDescriber && settings.visionDescriberModel.isNotBlank()) {
                            val description = describeScreenshotWithVisionModel(client, frame)
                            if (!description.isNullOrBlank()) {
                                messages.add(
                                    textMessage(
                                        role = "user",
                                        text = "Описание экрана от модели-наблюдателя (шаг $step):\n$description",
                                    ),
                                )
                            }
                        } else if (visionEnabled()) {
                            val dataUrl = bitmapToDataUrl(frame)
                            messages.add(
                                userImageMessage(
                                    text = "Текущий кадр экрана (шаг $step):",
                                    imageDataUrl = dataUrl,
                                ),
                            )
                        }
                    }
                }
                // Live mode: each turn captures a camera frame + a short mic chunk in parallel,
                // transcribes it, and feeds both into the model. Speaks the model reply via TTS
                // at the end of the step. Approximation of Gemini Live without a realtime API.
                if (settings.liveMode) {
                    onLog(AgentLog.System("[live] слушаю микрофон + кадр с камеры…"))
                    val live = runCatching { liveCapturer.captureOne() }
                    if (live.isFailure) {
                        onLog(AgentLog.Error("[live] capture: ${live.exceptionOrNull()?.message}"))
                    } else {
                        val r = live.getOrThrow()
                        // Push the latest camera frame to the UI preview before anything else.
                        onLiveFrame(r.cameraJpegPath)
                        val noise = listOfNotNull(
                            r.cameraError?.let { "камера: $it" },
                            r.transcriptError?.let { "stt: $it" },
                        ).joinToString("; ")
                        if (noise.isNotEmpty()) onLog(AgentLog.System("[live] $noise"))
                        if (r.transcript.isNotBlank()) {
                            onLog(AgentLog.System("[live] услышал: «${r.transcript}»"))
                        }
                        // Push the camera frame as a vision attachment whenever the controller
                        // model supports vision. We deliberately bypass settings.sendScreenshots
                        // here — the user explicitly enabled live mode, so they OBVIOUSLY want
                        // the camera frame to reach the model.
                        val cameraPath = r.cameraJpegPath
                        if (cameraPath != null && supportsVision(settings.model)) {
                            val small = downscaleCameraJpeg(cameraPath)
                            if (small != null) {
                                val b64 = android.util.Base64.encodeToString(
                                    small,
                                    android.util.Base64.NO_WRAP,
                                )
                                messages.add(
                                    userImageMessage(
                                        text = "[live] кадр с камеры (шаг $step). " +
                                            (if (r.transcript.isBlank()) "Пользователь молчит."
                                            else "Пользователь сказал: «${r.transcript}»."),
                                        imageDataUrl = "data:image/jpeg;base64,$b64",
                                    ),
                                )
                            }
                        } else if (r.transcript.isNotBlank()) {
                            // Non-vision controller — just push the transcript as text.
                            messages.add(
                                textMessage(
                                    role = "user",
                                    text = "[live] (шаг $step) пользователь сказал: «${r.transcript}»",
                                ),
                            )
                        }
                    }
                }
                // If a take_camera_photo just ran, slip the camera image into the next turn so
                // the vision-capable controller can describe / reason about it. Drained on use.
                pendingCameraPhoto?.let { path ->
                    pendingCameraPhoto = null
                    if (visionEnabled()) {
                        runCatching {
                            val bytes = File(path).readBytes()
                            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                            messages.add(
                                userImageMessage(
                                    text = "Снимок с камеры (сохранён по пути $path):",
                                    imageDataUrl = "data:image/jpeg;base64,$b64",
                                ),
                            )
                        }
                    }
                }
                // Only send reasoning_effort to models that actually accept it. Per the Groq
                // docs (https://console.groq.com/docs/reasoning) the supported set is:
                //   openai/gpt-oss-20b, openai/gpt-oss-120b, openai/gpt-oss-safeguard-20b,
                //   qwen/qwen3-32b — plus OpenAI's own o1 / o3 / o4 series. Sending it to any
                // other model (e.g. llama-3.x, llama-4-scout) yields HTTP 400.
                val effort = settings.reasoningEffort.takeIf {
                    it.isNotBlank() && supportsReasoningEffort(settings.model)
                }
                // The controller may be text-only (e.g. `openai/gpt-oss-120b`). In ANY scenario
                // where the controller can't accept images we strip ALL multimodal content
                // from history so the provider doesn't return HTTP 400 'content must be a
                // string'. The vision describer (two-model mode) is the supported way to feed
                // pixel info to a text controller — it converts the screenshot to plain text
                // BEFORE adding to history. In single-model + vision-capable mode we keep
                // only the last N images to stay under Groq's 5-images-per-request cap.
                val controllerIsTextOnly = !supportsVision(settings.model)
                val keepImages = if (settings.useVisionDescriber || controllerIsTextOnly) {
                    0
                } else {
                    MAX_IMAGES_IN_HISTORY
                }
                trimOldScreenshots(messages, keep = keepImages)
                val baseRequest = ChatRequest(
                    model = settings.model,
                    messages = messages,
                    tools = Tools.toolList(),
                    toolChoice = "auto",
                    temperature = settings.temperature.toDouble(),
                    maxCompletionTokens = settings.maxTokens.takeIf { it > 0 },
                    reasoningEffort = effort,
                )
                val response = try {
                    client.chat(baseRequest)
                } catch (e: LlmException) {
                    val msg = e.message.orEmpty()
                    when {
                        // Some Groq models (and most non-OpenAI providers) reject
                        // `reasoning_effort`. Disable it for this run AND persist the change so the
                        // next runs don't fail the same way, then retry the same step.
                        msg.startsWith("HTTP 400") &&
                            msg.contains("reasoning_effort", ignoreCase = true) -> {
                            onLog(
                                AgentLog.Error(
                                    "Модель не поддерживает reasoning_effort — отключаю и пробую снова. " +
                                        "Параметр выключен в Настройках, чтобы это не повторялось.",
                                ),
                            )
                            settings.reasoningEffort = ""
                            client.chat(baseRequest.copy(reasoningEffort = null))
                        }
                        msg.startsWith("HTTP 400") && msg.contains("Parsing", ignoreCase = true) -> {
                            onLog(AgentLog.Error("Модель сгенерировала некорректный tool-call. Пробую ещё раз с подсказкой быть короче."))
                            messages.add(
                                textMessage(
                                    role = "system",
                                    text = "Your previous response was rejected by the API as malformed. " +
                                        "Reply with a SINGLE short tool call. Do not embed long text or newlines " +
                                        "in tool arguments. Keep `text` arguments under 500 characters and " +
                                        "without literal newline characters.",
                                ),
                            )
                            client.chat(baseRequest.copy(temperature = 0.0))
                        }
                        msg.startsWith("HTTP 400") &&
                            (msg.contains("Too many images", ignoreCase = true) ||
                                msg.contains("image", ignoreCase = true) &&
                                msg.contains("limit", ignoreCase = true)) -> {
                            onLog(AgentLog.Error("Слишком много картинок в истории — выкидываю все, кроме последней, и пробую снова."))
                            trimOldScreenshots(messages, keep = 1)
                            client.chat(baseRequest.copy(messages = messages))
                        }
                        else -> throw e
                    }
                }
                val choice = response.choices.firstOrNull()
                    ?: run {
                        onLog(AgentLog.Error("Пустой ответ от модели"))
                        return
                    }
                val msg = choice.message
                msg.contentText?.takeIf { it.isNotBlank() }?.let { rawText ->
                    onLog(AgentLog.Assistant(rawText))
                    // Live-mode UX: speak the assistant's reply automatically so the user can
                    // have a voice conversation. We strip Markdown noise + cap length so TTS
                    // doesn't read out the whole code block character-by-character.
                    if (settings.liveMode) {
                        val tts4Speech = stripMarkdownForSpeech(rawText)
                            .take(MAX_SPEAK_CHARS)
                        if (tts4Speech.isNotBlank()) tts.speak(tts4Speech, settings.ttsRate)
                    }
                }
                // Recovery: some text-only models (notably gpt-oss-120b) sometimes serialize
                // the tool call as JSON inside the assistant content instead of using the
                // proper `tool_calls` field. Detect that and synthesize a real ToolCall so the
                // agent loop continues to behave correctly. Without this the user sees a wall
                // of `АГЕНТ: {"ask_user_overlay": {...}}` text and nothing actually happens.
                val rawToolCalls = msg.toolCalls.orEmpty()
                val recoveredCall = if (rawToolCalls.isEmpty()) {
                    msg.contentText?.let { recoverToolCallFromText(it) }
                } else null
                val finalMsg = if (recoveredCall != null) {
                    onLog(AgentLog.System("Восстановил tool-call из текста: ${recoveredCall.function.name}"))
                    // Replace the assistant message with one that has a proper tool_calls field
                    // so subsequent tool messages (with matching tool_call_id) link correctly.
                    msg.copy(content = null, toolCalls = listOf(recoveredCall))
                } else msg
                messages.add(finalMsg)
                if (recoveredCall != null && rawToolCalls.isEmpty()) {
                    // Also nudge the model to use the proper format next time.
                    messages.add(
                        textMessage(
                            role = "system",
                            text = "REMINDER: Use the structured `tool_calls` API field for all " +
                                "tool invocations. Do NOT serialize tool calls as JSON inside the " +
                                "`content` of an assistant message — they will not execute. Just " +
                                "call the tool the normal way.",
                        ),
                    )
                }
                val toolCalls = if (recoveredCall != null) listOf(recoveredCall) else rawToolCalls
                if (toolCalls.isEmpty()) {
                    // Two modes here:
                    //  - autoPauseOnIdle = true  → return; user resumes with «Продолжить».
                    //  - autoPauseOnIdle = false (game mode) → don't exit, nudge the model and
                    //    keep going. Only the overlay STOP button can stop us.
                    if (settings.autoPauseOnIdle) {
                        onLog(
                            AgentLog.Error(
                                "Жду новое указание. Нажми «Продолжить» чтобы продолжить, или «Стоп» в overlay.",
                            ),
                        )
                        return
                    }
                    messages.add(
                        textMessage(
                            role = "system",
                            text = "Я не получил от тебя tool call. Продолжай помогать игроку: " +
                                "посмотри на текущий скриншот выше и решай — нужно ли что-то " +
                                "делать (read_screen / tap / swipe / speak / ask_user_overlay). " +
                                "Если ничего полезного сделать нельзя прямо сейчас — просто молчи, " +
                                "ответь одним словом 'жду'. НЕ повторяй одну и ту же фразу типа " +
                                "«если нужно что-то конкретное, скажите» — это спам.",
                        ),
                    )
                    continue
                }
                for (call in toolCalls) {
                    // Each tool is wrapped in its own try/catch so a buggy tool argument or an
                    // exception inside the implementation cannot kill the loop. The error is
                    // returned to the model as a normal tool result so it can recover.
                    val result = try {
                        executeTool(service, call, lastScreenState)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Propagate cancellation up so the user-press-STOP flow ends cleanly
                        // instead of the loop keeping going with a fake "exception" tool result.
                        throw e
                    } catch (e: Throwable) {
                        Log.e(TAG, "Tool ${call.function.name} threw", e)
                        ToolResult.error("Исключение в инструменте ${call.function.name}: ${e.message ?: e::class.java.simpleName}")
                    }
                    lastScreenState = result.newScreenState ?: lastScreenState
                    onLog(AgentLog.ToolCall(call.function.name, call.function.arguments, result.summary))
                    messages.add(
                        textMessage(
                            role = "tool",
                            text = result.toolContent,
                            toolCallId = call.id,
                            name = call.function.name,
                        ),
                    )
                    // Tool produced a screenshot → expose it to the controller. Two paths:
                    //  - two-model mode: run the describer over the image and inject its text
                    //    output. The controller is text-only by design.
                    //  - single-model + vision-capable controller: attach raw image.
                    if (result.imageDataUrl != null) {
                        if (settings.useVisionDescriber && settings.visionDescriberModel.isNotBlank()) {
                            val description = describeDataUrlWithVisionModel(client, result.imageDataUrl)
                            if (!description.isNullOrBlank()) {
                                messages.add(
                                    textMessage(
                                        role = "user",
                                        text = "Описание скриншота от модели-наблюдателя (после ${call.function.name}):\n$description",
                                    ),
                                )
                            }
                        } else if (visionEnabled()) {
                            messages.add(
                                userImageMessage(
                                    text = "Текущий скриншот (после инструмента ${call.function.name}):",
                                    imageDataUrl = result.imageDataUrl,
                                ),
                            )
                        }
                    }
                    if (result.done != null) {
                        onLog(AgentLog.Done(result.done.summary, result.done.success))
                        if (settings.autoPauseOnIdle) {
                            // Pause and wait for the next user instruction.
                            return
                        }
                        // Game mode: don't let the model exit; remind it to keep helping.
                        messages.add(
                            textMessage(
                                role = "system",
                                text = "User has set the agent to user-only-exit. You CANNOT stop. " +
                                    "Don't call `done` again. Wait for the next user message " +
                                    "or proactively read_screen / take_screenshot to see what " +
                                    "the user is doing now and react.",
                            ),
                        )
                    }
                }
            }
            onLog(AgentLog.Error("Достигнут лимит шагов (${settings.maxSteps}) без завершения."))
        } catch (e: kotlinx.coroutines.CancellationException) {
            // User pressed STOP. Propagate so the viewmodel sees the run as cancelled instead of
            // failed; we don't log it as an error because that's the intended exit path.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Agent loop failed", e)
            onLog(AgentLog.Error(e.message ?: e.toString()))
        } finally {
            client.close()
            runCatching { tts.shutdown() }
            returnToApp()
        }
    }

    /** Bring the AI Agent app back to the foreground so the user can see the result log. */
    private fun returnToApp() {
        runCatching {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launch != null) {
                launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                context.startActivity(launch)
            }
        }
    }

    private suspend fun executeTool(
        service: AgentAccessibilityService,
        call: ToolCall,
        lastScreenState: ScreenState?,
    ): ToolResult {
        val args = parseArgs(call.function.arguments)
        return when (call.function.name) {
            "read_screen" -> {
                val state = service.captureScreenState()
                // Grab a bitmap so a vision-capable model — or the two-model describer — can SEE
                // the screen. Accessibility alone misses everything drawn into a SurfaceView
                // (games / video / WebGL).
                val needsBitmap = visionEnabled() || settings.useVisionDescriber
                val bitmap = if (needsBitmap) throttleAndCapture(service) else null
                val hint = if (state.nodes.isEmpty()) {
                    "\n[hint] Accessibility tree пуст — приложение скорее всего рендерит в SurfaceView " +
                        "(игра / видео / WebGL). Вызови read_screen_text для OCR или " +
                        "take_screenshot если включён vision-режим."
                } else ""
                ToolResult(
                    toolContent = "Foreground app: ${state.packageName}\n${state.description}$hint",
                    summary = "экран считан → ${state.nodes.size} элементов",
                    newScreenState = state,
                    imageDataUrl = bitmap?.let { bitmapToDataUrl(it) },
                )
            }
            "read_screen_text" -> {
                val bitmap = throttleAndCapture(service)
                if (bitmap == null) {
                    ToolResult.error("Не удалось получить изображение экрана. Пользователь отказал в разрешении захвата экрана.")
                } else {
                    val text = try {
                        OcrEngine.extractText(bitmap)
                    } catch (e: Exception) {
                        "[ошибка OCR: ${e.message}]"
                    }
                    ToolResult(
                        toolContent = "Foreground app: ${service.captureScreenState().packageName}\n--- OCR ---\n$text",
                        summary = "OCR: ${text.length} символов",
                        imageDataUrl = if (visionEnabled() || settings.useVisionDescriber) bitmapToDataUrl(bitmap) else null,
                    )
                }
            }
            "take_screenshot" -> {
                val bitmap = throttleAndCapture(service)
                if (bitmap == null) {
                    ToolResult.error("Не удалось получить скриншот.")
                } else {
                    val path = saveBitmapToPng(bitmap)
                    ToolResult(
                        toolContent = "Saved screenshot to $path",
                        summary = "сохранён скриншот: $path",
                        imageDataUrl = if (visionEnabled() || settings.useVisionDescriber) bitmapToDataUrl(bitmap) else null,
                    )
                }
            }
            "tap" -> {
                val nodeId = args.intOf("node_id") ?: return ToolResult.error("Missing node_id")
                val node = lastScreenState?.nodes?.getOrNull(nodeId)
                    ?: return ToolResult.error("Unknown node_id $nodeId. Call read_screen first.")
                val ok = service.tapNode(node)
                ToolResult(
                    toolContent = if (ok) "Tapped node $nodeId" else "Tap dispatch failed",
                    summary = if (ok) "нажат элемент #$nodeId" else "не удалось нажать элемент #$nodeId",
                )
            }
            "tap_at" -> {
                val rawX = args.numOf("x") ?: return ToolResult.error("Missing x")
                val rawY = args.numOf("y") ?: return ToolResult.error("Missing y")
                val x = resolveCoord(rawX, displayWidth())
                val y = resolveCoord(rawY, displayHeight())
                if (isInsideStopButton(x, y)) {
                    return ToolResult.error("Точка ($x,$y) попадает в кнопку СТОП. Только пользователь может её нажать. Выбери другую цель.")
                }
                val ok = service.tap(x, y)
                ToolResult(
                    toolContent = if (ok) "Tapped at ($x,$y)" else "Tap dispatch failed",
                    summary = if (ok) "нажато по координатам ($x, $y)" else "не удалось нажать ($x, $y)",
                )
            }
            "swipe" -> {
                val direction = args.stringOf("direction") ?: return ToolResult.error("Missing direction")
                val distance = args.stringOf("distance") ?: "medium"
                val (x1, y1, x2, y2) = computeSwipe(direction, distance)
                val ok = service.swipe(x1, y1, x2, y2)
                val dirRu = when (direction) {
                    "up" -> "вверх"; "down" -> "вниз"; "left" -> "влево"; "right" -> "вправо"; else -> direction
                }
                val distRu = when (distance) {
                    "short" -> "коротко"; "long" -> "длинно"; else -> "средне"
                }
                ToolResult(
                    toolContent = if (ok) "Swiped $direction" else "Swipe failed",
                    summary = if (ok) "свайп $dirRu, $distRu" else "не удалось свайпнуть $dirRu",
                )
            }
            "swipe_at" -> {
                val rx1 = args.numOf("x1") ?: return ToolResult.error("Missing x1")
                val ry1 = args.numOf("y1") ?: return ToolResult.error("Missing y1")
                val rx2 = args.numOf("x2") ?: return ToolResult.error("Missing x2")
                val ry2 = args.numOf("y2") ?: return ToolResult.error("Missing y2")
                val w = displayWidth()
                val h = displayHeight()
                val x1 = resolveCoord(rx1, w)
                val y1 = resolveCoord(ry1, h)
                val x2 = resolveCoord(rx2, w)
                val y2 = resolveCoord(ry2, h)
                if (isInsideStopButton(x1, y1) || isInsideStopButton(x2, y2)) {
                    return ToolResult.error("Свайп пересекает кнопку СТОП — это запрещено.")
                }
                val duration = args.intOf("duration_ms")?.toLong() ?: 300L
                val ok = service.swipe(x1, y1, x2, y2, duration)
                ToolResult(
                    toolContent = if (ok) "Swiped ($x1,$y1)→($x2,$y2)" else "Swipe failed",
                    summary = if (ok) "свайп ($x1,$y1) → ($x2,$y2)" else "не удалось свайпнуть",
                )
            }
            "type_text" -> {
                val text = args.stringOf("text") ?: return ToolResult.error("Missing text")
                val nodeId = args.intOf("node_id")
                val ok = if (nodeId != null) {
                    val node = lastScreenState?.nodes?.getOrNull(nodeId)
                        ?: return ToolResult.error("Unknown node_id $nodeId")
                    service.typeTextInNode(node, text)
                } else {
                    service.typeText(text)
                }
                ToolResult(
                    toolContent = if (ok) "Typed '$text'" else "Could not find an editable field",
                    summary = if (ok) "введён текст (${text.length} симв.)" else "нет активного поля ввода",
                )
            }
            "press_back" -> {
                val ok = service.pressBack()
                ToolResult(
                    toolContent = if (ok) "Back pressed" else "Back failed",
                    summary = if (ok) "нажата Назад" else "не удалось нажать Назад",
                )
            }
            "press_home" -> {
                val ok = service.pressHome()
                ToolResult(
                    toolContent = if (ok) "Home pressed" else "Home failed",
                    summary = if (ok) "переход на Главный экран" else "не удалось перейти на Главный экран",
                )
            }
            "press_recents" -> {
                val ok = service.pressRecents()
                ToolResult(
                    toolContent = if (ok) "Recents opened" else "Recents failed",
                    summary = if (ok) "открыты Недавние приложения" else "не удалось открыть Недавние",
                )
            }
            "open_app" -> {
                val pkg = args.stringOf("package_name") ?: return ToolResult.error("Missing package_name")
                val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent == null) {
                    ToolResult.error("App $pkg not installed or no launcher entry.")
                } else {
                    launchIntent.flags = launchIntent.flags or Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(launchIntent)
                    delay(500)
                    ToolResult(
                        toolContent = "Launched $pkg",
                        summary = "запущено приложение $pkg",
                    )
                }
            }
            "wait" -> {
                val ms = (args.intOf("ms") ?: 500).coerceIn(0, 5000)
                delay(ms.toLong())
                ToolResult(
                    toolContent = "Waited ${ms}ms",
                    summary = "пауза ${ms} мс",
                )
            }
            "ask_user" -> {
                val question = args.stringOf("question") ?: return ToolResult.error("Missing question")
                onLog(AgentLog.AskUser(question))
                val answer = askUser(question)
                ToolResult(
                    toolContent = answer,
                    summary = "вопрос «$question» → «$answer»",
                )
            }
            "ask_user_overlay" -> {
                val question = args.stringOf("question") ?: return ToolResult.error("Missing question")
                val options = args.stringListOf("options")
                val labelHint = if (options != null) " [${options.joinToString(" / ")}]" else ""
                onLog(AgentLog.AskUser("[overlay] $question$labelHint"))
                val deferred = CompletableDeferred<String>()
                OverlayService.Pending.deferred = deferred
                OverlayService.showQuestion(context, question, options)
                val answer = try {
                    deferred.await()
                } finally {
                    OverlayService.Pending.deferred = null
                    OverlayService.hide(context)
                }
                ToolResult(
                    toolContent = answer,
                    summary = "overlay «$question» → «$answer»",
                )
            }
            "speak" -> {
                val rawText = args.stringOf("text") ?: return ToolResult.error("Missing text")
                // Cap TTS payload. The user complains about speak() reading whole-screen
                // descriptions like a wall of text. Force the model toward short remarks: hard
                // truncate to MAX_SPEAK_CHARS, strip newlines, log a hint back so the model
                // learns next time.
                val sanitized = rawText.replace(Regex("\\s+"), " ").trim()
                val text = if (sanitized.length > MAX_SPEAK_CHARS) {
                    sanitized.take(MAX_SPEAK_CHARS - 1).trimEnd { it == ',' || it == '.' || it.isWhitespace() } + "…"
                } else {
                    sanitized
                }
                val rate = args.floatOf("rate") ?: settings.ttsRate
                val ok = tts.speak(text, rate)
                val truncatedHint = if (sanitized.length > MAX_SPEAK_CHARS)
                    " (исходник был ${sanitized.length} симв., обрезан до $MAX_SPEAK_CHARS — в следующий раз говори короче, одно предложение)"
                else ""
                ToolResult(
                    toolContent = if (ok) "Spoke ${text.length} chars$truncatedHint" else "TTS failed",
                    summary = if (ok) "озвучено: «${text.take(40)}»" else "не удалось озвучить",
                )
            }
            "listen" -> {
                val lang = args.stringOf("language")
                val transcript = stt.listenLive(language = lang)
                ToolResult(
                    toolContent = transcript,
                    summary = "услышано: «${transcript.take(80)}»",
                )
            }
            "joystick_move" -> {
                if (!com.aiagent.android.overlay.JoystickOverlayService.isActive()) {
                    return ToolResult.error(
                        "Виртуальный джойстик не включён. Попроси пользователя включить его в " +
                            "приложении (вкладка «Агент» → переключатель «Виртуальный джойстик») " +
                            "и расположить поверх внутриигрового джойстика.",
                    )
                }
                val direction = args.stringOf("direction")?.lowercase()
                val angleArg = args.floatOf("angle")
                val angle = angleArg ?: when (direction) {
                    "east", "right", "восток" -> 0f
                    "southeast" -> 45f
                    "south", "down", "юг" -> 90f
                    "southwest" -> 135f
                    "west", "left", "запад" -> 180f
                    "northwest" -> 225f
                    "north", "up", "север" -> 270f
                    "northeast" -> 315f
                    else -> return ToolResult.error(
                        "Укажи direction (north/south/east/west/...) или angle в градусах.",
                    )
                }
                val magnitude = (args.floatOf("magnitude") ?: 1.0f).coerceIn(0.05f, 1f)
                val durationMs = (args.intOf("duration_ms") ?: 600).coerceIn(50, 5000).toLong()
                com.aiagent.android.overlay.JoystickOverlayService.push(
                    context = context,
                    angleDeg = angle,
                    magnitude = magnitude,
                    durationMs = durationMs,
                )
                ToolResult(
                    toolContent = "Joystick pushed at angle ${angle}° magnitude $magnitude for ${durationMs}ms",
                    summary = "джойстик ${direction ?: "${angle}°"} ($magnitude × $durationMs мс)",
                )
            }
            "record_audio" -> {
                val seconds = (args.intOf("seconds") ?: 6).coerceIn(1, 60)
                val lang = args.stringOf("language")
                val outDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "audio").apply { mkdirs() }
                val outFile = File(outDir, "rec-${System.currentTimeMillis()}.wav")
                val started = micRecorder.start(outFile, maxMs = seconds * 1000L)
                if (!started) return ToolResult.error("Не удалось начать запись (нет разрешения RECORD_AUDIO?).")
                delay(seconds * 1000L)
                val finalFile = micRecorder.stop()
                if (finalFile == null || !finalFile.exists()) {
                    return ToolResult.error("Запись не удалась.")
                }
                val text = stt.transcribeFile(finalFile, language = lang)
                ToolResult(
                    toolContent = text,
                    summary = "${seconds}с → «${text.take(80)}»",
                )
            }
            "device_info" -> {
                val info = DeviceInfo.gather(context)
                ToolResult(
                    toolContent = info,
                    summary = "device_info (${info.length} симв.)",
                )
            }
            "list_files" -> {
                val path = args.stringOf("path") ?: return ToolResult.error("Missing path")
                val out = try {
                    fileTools.listEntries(path)
                } catch (e: SecurityException) {
                    "[доступ запрещён: ${e.message}]"
                }
                ToolResult(toolContent = out, summary = "list $path")
            }
            "read_file" -> {
                val path = args.stringOf("path") ?: return ToolResult.error("Missing path")
                val maxBytes = args.intOf("max_bytes") ?: 65536
                val out = try {
                    fileTools.readText(path, maxBytes)
                } catch (e: SecurityException) {
                    "[доступ запрещён: ${e.message}]"
                }
                ToolResult(toolContent = out, summary = "read $path (${out.length} симв.)")
            }
            "write_file" -> {
                val path = args.stringOf("path") ?: return ToolResult.error("Missing path")
                val content = args.stringOf("content") ?: return ToolResult.error("Missing content")
                val mime = args.stringOf("mime_type") ?: "text/plain"
                val out = try {
                    fileTools.writeText(path, content, mime)
                } catch (e: SecurityException) {
                    "[доступ запрещён: ${e.message}]"
                }
                ToolResult(toolContent = out, summary = "write $path")
            }
            "make_dir" -> {
                val path = args.stringOf("path") ?: return ToolResult.error("Missing path")
                val out = try {
                    fileTools.makeDir(path)
                } catch (e: SecurityException) {
                    "[доступ запрещён: ${e.message}]"
                }
                ToolResult(toolContent = out, summary = "mkdir $path")
            }
            "delete_file" -> {
                val path = args.stringOf("path") ?: return ToolResult.error("Missing path")
                val out = try {
                    fileTools.deletePath(path)
                } catch (e: SecurityException) {
                    "[доступ запрещён: ${e.message}]"
                }
                ToolResult(toolContent = out, summary = "rm $path")
            }
            "start_screen_recording" -> {
                val res = startScreenRecording()
                ToolResult(
                    toolContent = res,
                    summary = "запись экрана: $res",
                )
            }
            "stop_screen_recording" -> {
                val res = stopScreenRecording()
                ToolResult(
                    toolContent = res,
                    summary = res,
                )
            }
            "list_apps" -> {
                val includeSystem = args.boolOf("include_system") ?: false
                val filter = args.stringOf("filter")?.lowercase()?.takeIf { it.isNotBlank() }
                val pm = context.packageManager
                val launchablePackages: Set<String> = pm
                    .queryIntentActivities(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                        0,
                    )
                    .map { it.activityInfo.packageName }
                    .toSet()
                val pkgs = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                val rows = pkgs.mapNotNull { app ->
                    val launchable = app.packageName in launchablePackages
                    if (!includeSystem && !launchable) return@mapNotNull null
                    val display = pm.getApplicationLabel(app).toString()
                    if (filter != null) {
                        val hay = (display + " " + app.packageName).lowercase()
                        if (!hay.contains(filter)) return@mapNotNull null
                    }
                    "$display | ${app.packageName} | ${if (launchable) "launchable" else "background"}"
                }.sorted()
                val out = if (rows.isEmpty()) "(приложений не найдено)"
                else rows.joinToString("\n")
                ToolResult(
                    toolContent = out,
                    summary = "${rows.size} прил.",
                )
            }
            "get_clipboard" -> {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip
                val text = if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).coerceToText(context).toString()
                } else ""
                ToolResult(
                    toolContent = if (text.isEmpty()) "(буфер пуст)" else text,
                    summary = "буфер: ${text.take(40)}",
                )
            }
            "set_clipboard" -> {
                val text = args.stringOf("text") ?: return ToolResult.error("set_clipboard: text required")
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ai-agent", text))
                ToolResult(
                    toolContent = "OK (${text.length} симв.)",
                    summary = "буфер ← «${text.take(40)}»",
                )
            }
            "set_volume" -> {
                val streamName = args.stringOf("stream") ?: "music"
                val absolute = args.intOf("level")
                val relative = args.intOf("relative")
                val streamId = when (streamName.lowercase()) {
                    "music" -> AudioManager.STREAM_MUSIC
                    "ring" -> AudioManager.STREAM_RING
                    "notification" -> AudioManager.STREAM_NOTIFICATION
                    "alarm" -> AudioManager.STREAM_ALARM
                    "voice_call", "call", "voice" -> AudioManager.STREAM_VOICE_CALL
                    "system" -> AudioManager.STREAM_SYSTEM
                    else -> AudioManager.STREAM_MUSIC
                }
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(streamId)
                val target = when {
                    absolute != null -> absolute.coerceIn(0, max)
                    relative != null -> {
                        val cur = am.getStreamVolume(streamId)
                        val delta = (relative * max / 100f).toInt()
                        (cur + delta).coerceIn(0, max)
                    }
                    else -> return ToolResult.error("set_volume: укажите level или relative")
                }
                runCatching {
                    am.setStreamVolume(streamId, target, AudioManager.FLAG_SHOW_UI)
                }.onFailure {
                    return ToolResult.error("set_volume: ${it.message ?: "не удалось"} (стрим $streamName может требовать notification policy access)")
                }
                ToolResult(
                    toolContent = "stream=$streamName уровень=$target/$max",
                    summary = "$streamName: $target/$max",
                )
            }
            "set_brightness" -> {
                val level = args.intOf("level") ?: return ToolResult.error("set_brightness: level required")
                // We can only adjust the per-window brightness. The system brightness needs
                // WRITE_SETTINGS, which we deliberately don't request.
                val activityBrightness = if (level < 0) -1f else (level.coerceIn(0, 100) / 100f)
                // The accessibility service can't change activity window params directly; we
                // ask the overlay service if it's running, otherwise just acknowledge.
                OverlayService.applyBrightness(context, activityBrightness)
                ToolResult(
                    toolContent = "overlay-brightness=$activityBrightness (системная яркость не меняется)",
                    summary = "яркость overlay: $activityBrightness",
                )
            }
            "http_fetch" -> {
                val url = args.stringOf("url") ?: return ToolResult.error("Missing url")
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return ToolResult.error("URL должен начинаться с http(s)://")
                }
                val method = (args.stringOf("method") ?: "GET").uppercase()
                val maxBytes = args.intOf("max_bytes") ?: 65536
                val headers = args.objectOf("headers")
                val body = args.stringOf("body")
                val result = runCatching {
                    HttpFetch.fetch(url, method, headers, body, maxBytes)
                }
                if (result.isFailure) {
                    val msg = result.exceptionOrNull()?.message ?: "unknown"
                    ToolResult.error("http_fetch: $msg")
                } else {
                    val r = result.getOrThrow()
                    ToolResult(
                        toolContent = "HTTP ${r.statusCode} ${r.statusText}\n${r.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }}\n\n${r.body}",
                        summary = "$method $url → ${r.statusCode}",
                    )
                }
            }
            "project_write" -> {
                val project = args.stringOf("project") ?: return ToolResult.error("Missing project")
                val file = args.stringOf("file") ?: return ToolResult.error("Missing file")
                val content = args.stringOf("content") ?: return ToolResult.error("Missing content")
                val out = runCatching { projectTools.write(project, file, content) }
                    .getOrElse { "ошибка: ${it.message}" }
                ToolResult(toolContent = out, summary = "project_write $project/$file")
            }
            "project_read" -> {
                val project = args.stringOf("project") ?: return ToolResult.error("Missing project")
                val file = args.stringOf("file") ?: return ToolResult.error("Missing file")
                val maxBytes = args.intOf("max_bytes") ?: 65536
                val out = runCatching { projectTools.read(project, file, maxBytes) }
                    .getOrElse { "ошибка: ${it.message}" }
                ToolResult(toolContent = out, summary = "project_read $project/$file (${out.length}c)")
            }
            "project_list" -> {
                val project = args.stringOf("project")
                val out = runCatching { projectTools.list(project) }
                    .getOrElse { "ошибка: ${it.message}" }
                ToolResult(toolContent = out, summary = if (project.isNullOrBlank()) "project_list" else "project_list $project")
            }
            "open_project_in_browser" -> {
                val project = args.stringOf("project") ?: return ToolResult.error("Missing project")
                val file = args.stringOf("file") ?: "index.html"
                val server = com.aiagent.android.web.LocalProjectServer.get(context)
                val port = runCatching { server.start() }.getOrElse {
                    return ToolResult.error("HTTP-сервер не стартовал: ${it.message}")
                }
                val url = "http://127.0.0.1:$port/$project/$file"
                runCatching {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }.exceptionOrNull()?.let {
                    return ToolResult(
                        toolContent = "Сервер запущен на $url, но запустить браузер не удалось: ${it.message}",
                        summary = "open_project_in_browser → $url (браузер не открылся)",
                    )
                }
                ToolResult(
                    toolContent = "Открыл в браузере: $url",
                    summary = "open_project_in_browser $project/$file",
                )
            }
            "project_delete" -> {
                val project = args.stringOf("project") ?: return ToolResult.error("Missing project")
                val file = args.stringOf("file")
                val out = runCatching { projectTools.delete(project, file) }
                    .getOrElse { "ошибка: ${it.message}" }
                ToolResult(toolContent = out, summary = "project_delete $project/${file ?: "*"}")
            }
            "take_camera_photo" -> {
                val facing = args.stringOf("facing") ?: "back"
                val result = com.aiagent.android.camera.CameraTool.takePhoto(context, facing)
                if (result.isFailure) {
                    ToolResult.error("камера: ${result.exceptionOrNull()?.message ?: "не удалось"}")
                } else {
                    val path = result.getOrThrow()
                    // If vision is available, queue this photo to be attached on the next turn.
                    if (visionEnabled()) pendingCameraPhoto = path
                    ToolResult(
                        toolContent = "сохранено: $path" + if (visionEnabled()) " (отправлено в модель)" else " (vision выключен — модель его не увидит)",
                        summary = "фото: $path",
                    )
                }
            }
            "done" -> {
                val summary = args.stringOf("summary") ?: "(без описания)"
                val success = args.boolOf("success") ?: true
                ToolResult(
                    toolContent = "Acknowledged: $summary",
                    summary = if (success) "завершено: $summary" else "прекращено: $summary",
                    done = DoneSignal(summary, success),
                )
            }
            else -> ToolResult.error("Unknown tool: ${call.function.name}")
        }
    }

    /**
     * Capture a screen bitmap, throttled by `Settings.screenFps`.
     *
     * Tries the Accessibility `takeScreenshot()` API first (no extra permission, no notification).
     * If that fails — which happens on Android < 11 and on a number of OEM ROMs (Realme, Vivo,
     * Xiaomi MIUI in particular) — we fall back to MediaProjection: ask the user for one-time
     * consent, start a long-lived [ScreenCaptureService], and pull frames from its ImageReader.
     */
    private suspend fun throttleAndCapture(service: AgentAccessibilityService): Bitmap? {
        val fps = settings.screenFps
        if (fps > 0f) {
            val minIntervalMs = (1000f / fps).toLong()
            val sinceLast = System.currentTimeMillis() - lastScreenshotMs
            if (sinceLast < minIntervalMs) {
                delay(minIntervalMs - sinceLast)
            }
        }
        lastScreenshotMs = System.currentTimeMillis()

        // Fast path: Accessibility takeScreenshot.
        if (!ScreenCaptureService.isRunning) {
            val bitmap = service.captureBitmap()
            if (bitmap != null) return bitmap
        }

        // Slow path: MediaProjection. Start the capture service if it isn't running yet.
        if (!ScreenCaptureService.isRunning) {
            // The user already declined this run; don't pop the system dialog again.
            if (projectionDenied) return null
            val ok = try {
                ensureCaptureService()
            } catch (e: Exception) {
                onLog(AgentLog.Error("Не удалось запустить захват экрана: ${e.message}"))
                false
            }
            if (!ok) {
                projectionDenied = true
                return null
            }
        }
        return ScreenCaptureService.captureFrame()
    }

    private fun saveBitmapToPng(bitmap: Bitmap): String {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "screenshots").apply { mkdirs() }
        val name = "shot-" + SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date()) + ".png"
        val file = File(dir, name)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return file.absolutePath
    }

    private fun supportsReasoningEffort(modelId: String): Boolean =
        ModelCapabilities.supportsReasoningEffort(modelId)

    private fun supportsVision(modelId: String): Boolean =
        ModelCapabilities.supportsVision(modelId)

    /**
     * True if the agent should attach raw screenshots to controller-LLM messages this run.
     *
     * IMPORTANT: we only attach images when the configured *controller* model is known to be
     * multimodal. If a user enables "send screenshots" while the controller is a text-only
     * model (e.g. `openai/gpt-oss-120b`), Groq returns
     * `HTTP 400: messages[N].content must be a string`. The two-model "vision describer" path
     * is the right way to feed pixel info to a text controller — it converts the image to text
     * via a separate vision model BEFORE the controller sees it.
     */
    private fun visionEnabled(): Boolean =
        settings.sendScreenshots && supportsVision(settings.model)

    /**
     * Walk the conversation in reverse and keep only the [keep] most recent multimodal
     * (image-bearing) user messages. Older multimodal messages are rewritten in place to
     * plain-text so the API stops counting them as images. Llama-4 on Groq currently caps
     * inputs at 5 images per request — without this we hit
     * `HTTP 400: Too many images provided` after a handful of read_screen calls.
     */
    private fun trimOldScreenshots(messages: MutableList<ChatMessage>, keep: Int) {
        var imagesRemaining = keep
        for (i in messages.indices.reversed()) {
            val msg = messages[i]
            val content = msg.content ?: continue
            if (content !is JsonArray) continue
            // Multimodal message. If we still have budget, leave it; otherwise replace with text.
            if (imagesRemaining > 0) {
                imagesRemaining--
                continue
            }
            val plainText = extractTextFromMultimodal(content)
            messages[i] = ChatMessage(
                role = msg.role,
                content = JsonPrimitive(
                    if (plainText.isBlank()) "[старый скриншот опущен для экономии токенов]"
                    else "$plainText [скриншот опущен]",
                ),
                toolCalls = msg.toolCalls,
                toolCallId = msg.toolCallId,
                name = msg.name,
            )
        }
    }

    /**
     * Best-effort recovery for the case where a text-only model (e.g. gpt-oss-120b) decides
     * to serialize a tool invocation as JSON in the assistant `content` field instead of
     * using the proper `tool_calls` API. We accept several common shapes:
     *
     *   1. `{"toolName": {...args}}`             ← the most common form we see in the wild
     *   2. `{"name": "toolName", "arguments": ...}` ← OpenAI's older function-call shape
     *   3. `{"tool": "toolName", "args": {...}}` ← occasional variant
     *
     * Returns a synthetic [ToolCall] if exactly one known tool name is detected; null
     * otherwise. Knowingly conservative — we only attempt parses that look like JSON, never
     * try to coerce arbitrary natural-language text into a tool call.
     */
    private fun recoverToolCallFromText(raw: String): ToolCall? {
        val trimmed = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (!trimmed.startsWith("{")) return null
        val knownTools: Set<String> = com.aiagent.android.agent.Tools.toolList()
            .map { it.function.name }
            .toSet()
        val parsed = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()
            as? JsonObject ?: return null

        // Shape #2: explicit name + arguments.
        val explicitName = parsed["name"]?.jsonPrimitive?.contentOrNull
            ?: parsed["tool"]?.jsonPrimitive?.contentOrNull
        if (explicitName != null && explicitName in knownTools) {
            val argsElem = parsed["arguments"] ?: parsed["args"] ?: JsonObject(emptyMap())
            val argsString = if (argsElem is JsonPrimitive && argsElem.isString) {
                argsElem.contentOrNull.orEmpty()
            } else {
                argsElem.toString()
            }
            return ToolCall(
                id = "recovered_${System.nanoTime()}",
                type = "function",
                function = FunctionCall(name = explicitName, arguments = argsString),
            )
        }

        // Shape #1: top-level key is the tool name.
        val matchedName = parsed.keys.firstOrNull { it in knownTools } ?: return null
        val args = parsed[matchedName] as? JsonObject ?: return null
        return ToolCall(
            id = "recovered_${System.nanoTime()}",
            type = "function",
            function = FunctionCall(name = matchedName, arguments = args.toString()),
        )
    }

    private fun extractTextFromMultimodal(content: JsonElement): String {
        if (content !is JsonArray) return ""
        return content.joinToString(" ") { part ->
            val obj = (part as? JsonObject) ?: return@joinToString ""
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            if (type == "text") obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty() else ""
        }.trim()
    }

    /** True if the given screen-pixel point falls inside the persistent overlay STOP button.
     *  Used to refuse tap_at / swipe_at calls so the AI cannot click its own kill switch. */
    private fun isInsideStopButton(x: Int, y: Int): Boolean {
        val r = OverlayService.stopButtonBounds ?: return false
        val pad = 16
        return x in (r.left - pad)..(r.right + pad) &&
            y in (r.top - pad)..(r.bottom + pad)
    }

    /**
     * Encode a bitmap as a `data:image/jpeg;base64,...` URL after downscaling so the longest side
     * is at most [Settings.screenshotMaxDim] pixels. JPEG is used for ~10× smaller payload than
     * PNG at quality 80, which dramatically reduces upload size and token usage.
     */
    /**
     * Two-model mode: ask the configured vision describer to write a textual summary of the
     * given screenshot. The result is plain Russian prose suitable to drop into the controller
     * model's history. Returns null if the call fails — the agent keeps going without the
     * description so a transient describer error doesn't kill the whole step.
     */
    private suspend fun describeScreenshotWithVisionModel(
        controller: LlmClient,
        bitmap: Bitmap,
    ): String? = describeDataUrlWithVisionModel(controller, bitmapToDataUrl(bitmap))

    private suspend fun describeDataUrlWithVisionModel(
        controller: LlmClient,
        dataUrl: String,
    ): String? {
        val req = ChatRequest(
            model = settings.visionDescriberModel,
            messages = listOf(
                textMessage(
                    role = "system",
                    text = "Ты — модель-наблюдатель для агента-помощника в играх. Тебе показывают " +
                        "скриншот экрана Android-устройства. Опиши его подробно (5-10 предложений) " +
                        "так, чтобы текстовая модель-контроллер могла принять решение БЕЗ доступа к " +
                        "пикселям:\n" +
                        "- какое приложение / игра / экран сейчас открыт;\n" +
                        "- ключевые UI-элементы (кнопки, поля, тексты, иконки) — где они расположены, " +
                        "как выглядят, какого цвета, на что похожи (если иконка — на что она похожа);\n" +
                        "- состояние игры если это игра: HP/мана/таймер/счёт, какие враги/предметы " +
                        "видны, в каком углу что лежит, цветные индикаторы;\n" +
                        "- модальные окна / диалоги / уведомления — что там написано;\n" +
                        "- что вообще происходит на экране СЕЙЧАС.\n" +
                        "Пиши сразу описание, простыми фразами на русском, без вступлений типа " +
                        "'я вижу' или 'на скриншоте'.",
                ),
                userImageMessage(
                    text = "Опиши, что на этом скриншоте.",
                    imageDataUrl = dataUrl,
                ),
            ),
            // No tools — the describer just writes prose.
            tools = null,
            toolChoice = null,
            temperature = 0.2,
            maxCompletionTokens = 700,
            reasoningEffort = null,
        )
        return try {
            val resp = controller.chat(req)
            resp.choices.firstOrNull()?.message?.contentText?.trim()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Vision describer failed: ${e.message}")
            onLog(AgentLog.Error("Модель-наблюдатель не ответила: ${e.message ?: e::class.java.simpleName}. Шаг продолжается без описания."))
            null
        }
    }

    private fun bitmapToDataUrl(bitmap: Bitmap): String {
        val maxDim = settings.screenshotMaxDim.coerceAtLeast(256)
        val scale = (maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
        val target = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else bitmap
        val out = java.io.ByteArrayOutputStream()
        target.compress(Bitmap.CompressFormat.JPEG, 80, out)
        val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        return "data:image/jpeg;base64,$b64"
    }

    private fun computeSwipe(direction: String, distance: String): IntArray {
        val service = AgentAccessibilityService.instance
        val metrics = service?.resources?.displayMetrics
        val w = metrics?.widthPixels ?: 1080
        val h = metrics?.heightPixels ?: 1920
        val dist = when (distance) {
            "short" -> 0.25
            "long" -> 0.75
            else -> 0.5
        }
        val cx = w / 2
        val cy = h / 2
        return when (direction) {
            "up" -> intArrayOf(cx, (h * (0.5 + dist / 2)).toInt(), cx, (h * (0.5 - dist / 2)).toInt())
            "down" -> intArrayOf(cx, (h * (0.5 - dist / 2)).toInt(), cx, (h * (0.5 + dist / 2)).toInt())
            "left" -> intArrayOf((w * (0.5 + dist / 2)).toInt(), cy, (w * (0.5 - dist / 2)).toInt(), cy)
            "right" -> intArrayOf((w * (0.5 - dist / 2)).toInt(), cy, (w * (0.5 + dist / 2)).toInt(), cy)
            else -> intArrayOf(cx, cy, cx, cy)
        }
    }

    private fun parseArgs(raw: String): JsonObject {
        if (raw.isBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse { JsonObject(emptyMap()) }
    }

    private fun JsonObject.intOf(key: String): Int? =
        (get(key) as? JsonPrimitive)?.intOrNull
            ?: (get(key) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun JsonObject.numOf(key: String): Double? =
        (get(key) as? JsonPrimitive)?.doubleOrNull
            ?: (get(key) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    /**
     * Resolve a coordinate the LLM passed as either an absolute pixel value (e.g. 540) or as a
     * 0..1 fraction of the screen dimension (e.g. 0.5 = horizontal centre). The schema declares
     * the field as `number` so the model can pick whichever feels natural — we normalise both
     * forms here. Anything in `[0.0, 1.0]` is treated as a fraction; anything > 1.0 is pixels.
     * (No real device has a 1-pixel-wide screen, so x=1.0 unambiguously means "far edge".)
     */
    private fun resolveCoord(value: Double, dim: Int): Int {
        return if (value in 0.0..1.0) {
            (value * dim).toInt().coerceIn(0, (dim - 1).coerceAtLeast(0))
        } else {
            value.toInt()
        }
    }

    /** Width of the real display in pixels, or a sane default if unavailable. */
    private fun displayWidth(): Int =
        AgentAccessibilityService.instance?.resources?.displayMetrics?.widthPixels ?: 1080

    /** Height of the real display in pixels, or a sane default if unavailable. */
    private fun displayHeight(): Int =
        AgentAccessibilityService.instance?.resources?.displayMetrics?.heightPixels ?: 1920

    private fun JsonObject.stringOf(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolOf(key: String): Boolean? =
        runCatching { (get(key) as? JsonPrimitive)?.boolean }.getOrNull()

    private fun JsonObject.floatOf(key: String): Float? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()

    private fun JsonObject.stringListOf(key: String): List<String>? =
        (get(key) as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        }?.takeIf { it.isNotEmpty() }

    /** Strip Markdown markup before passing text to TTS so it doesn't read out punctuation
     *  and code blocks character-by-character. Removes fenced code blocks entirely; converts
     *  `**bold**` / `*italic*` / `` `code` `` / `[label](url)` to their plain-text version. */
    private fun stripMarkdownForSpeech(input: String): String {
        var s = input
        // Drop fenced code blocks — TTS reading symbol-by-symbol is awful.
        s = s.replace(Regex("```[\\s\\S]*?```"), " (фрагмент кода) ")
        // Inline code → plain.
        s = s.replace(Regex("`([^`]+)`"), "$1")
        // Bold / italic / strikethrough.
        s = s.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        s = s.replace(Regex("\\*([^*]+)\\*"), "$1")
        s = s.replace(Regex("~~([^~]+)~~"), "$1")
        // Links: keep the label only.
        s = s.replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
        // Headers — drop the leading hashes.
        s = s.replace(Regex("(?m)^#+\\s*"), "")
        // List markers.
        s = s.replace(Regex("(?m)^[\\-*]\\s+"), "")
        s = s.replace(Regex("(?m)^\\d+\\.\\s+"), "")
        return s.trim()
    }

    /** Pull a flat string→string map out of a JSON object argument, e.g. `headers={"Accept":"application/json"}`. */
    private fun JsonObject.objectOf(key: String): Map<String, String>? {
        val obj = get(key) as? JsonObject ?: return null
        return obj.entries.mapNotNull { (k, v) ->
            val s = (v as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            k to s
        }.toMap().takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val TAG = "Agent"
        /** Voice / text messages pushed by the user from the floating ⚙️ overlay while the
         *  agent is mid-step. Drained at the start of each turn and injected as user messages
         *  so the controller sees them and can react. Thread-safe queue. */
        val userInterrupts: java.util.concurrent.ConcurrentLinkedQueue<String> =
            java.util.concurrent.ConcurrentLinkedQueue()
        /** Vision-capable models on Groq cap inputs at 5 images/request; 3 keeps headroom for
         *  the model's own returned tool messages without hitting the limit. */
        private const val MAX_IMAGES_IN_HISTORY = 3
        /** Hard cap on text passed to the TTS engine. The model frequently tries to read full
         *  multi-paragraph screen descriptions; we silently truncate to keep TTS short and
         *  game-friendly. */
        private const val MAX_SPEAK_CHARS = 220
        private const val SYSTEM_PROMPT = """You are an AI agent that lives on the user's Android phone and helps them — especially during gameplay. You can observe the screen, listen to audio, speak, write files, and control the UI through Accessibility.

You have these tools:

VISION
- read_screen        → list the active app and its interactive UI nodes (use first; fast). When vision-mode is enabled the same call also delivers the screen as an image to the next turn, so you can really SEE the pixels — even of games / SurfaceView apps where the a11y tree is empty.
- read_screen_text   → on-device OCR of the current screen pixels (slower; use when read_screen returns nothing useful, e.g. inside games / video players that draw to a SurfaceView).
- take_screenshot    → save a PNG of the current screen to disk and return its path. With vision-mode it also delivers the screen image to the next turn.

ACTUATION
- tap(node_id) → tap a UI element. Works ONLY when read_screen returned a non-empty list of nodes.
- tap_at(x, y) → tap by coordinate. Coordinates are EITHER absolute pixels (whole numbers, e.g. x=540, y=1200) OR 0..1 fractions of the screen (e.g. x=0.5, y=0.6 = horizontal centre, 60% down). Use tap_at for SurfaceView games (Among Us, Roblox, Clash, Genshin, Fortnite, …) — `read_screen` returns 0–1 elements there because the game draws into a Surface; the Accessibility tree is empty. Read the screen with read_screen_text (OCR) and tap visible text by its bbox centre.
- swipe / swipe_at / type_text → interact with the UI.
- press_back / press_home / press_recents     → system navigation.
- open_app(package_name)                      → launch an app by package.
- wait(ms)                                    → pause for animations.

VOICE / AUDIO
- speak(text, rate?)              → say something out loud through the device speaker. **Keep it under ~200 chars (one short sentence).** Anything longer is silently truncated. NEVER read out a whole screen description — summarise in one phrase.
- listen(language?)               → one-shot live mic listen via the platform recogniser.
- record_audio(seconds, language?) → record N seconds of mic audio and transcribe via Whisper.

USER INTERACTION
- ask_user(question)         → ask a free-form question; the answer comes back as text.
- ask_user_overlay(question) → display a 50%-transparent overlay on top of the current app (works during gameplay) with Yes/No/Open/Dismiss buttons. Result: 'yes' / 'no' / 'dismiss' / the user's typed answer.

DEVICE / FILES
- device_info  → model, OS, screen, RAM, battery, network, hardware features.
- list_apps(include_system?, filter?)  → installed applications on the device, one per line as `display_name | package | launchable`.
- list_files(path), read_file(path), write_file(path, content), make_dir(path), delete_file(path)
   Path rules: 'content://...' or 'name/sub/path' relative to one of the user's allowed folders, or — only when 'all-files' mode is enabled in Settings — an absolute path like '/storage/emulated/0/...'.

PROJECTS (preferred for any multi-file code task)
- project_write(project, file, content) / project_read(project, file) / project_list(project?) / project_delete(project, file?)
   These ALWAYS write to the agent's own folder (`Documents/AI-Agent/projects/<project>/<file>` when reachable, otherwise app-private). They do NOT need any storage permission.
   USE THESE — not write_file — whenever the user asks for a multi-file project: a game, a website, a small Android app, a clone of Minecraft, etc. Build the project incrementally:
     1. project_write(project, "README.md", description)
     2. project_write(project, "src/...", code)   ← repeat per file
     3. project_list(project) at the end → tell the user the project is ready in the agent folder.
- open_project_in_browser(project, file?) → spin up the local HTTP server and open `http://127.0.0.1:<port>/<project>/<file>` (default `index.html`) in the system browser.
   For ANY HTML / CSS / JS project, the LAST step MUST be `open_project_in_browser(project)` so the user immediately sees the running site/game. Don't ask for permission — just open it.

CLIPBOARD / SYSTEM
- get_clipboard / set_clipboard(text)  → read or replace the system clipboard.
- set_volume(stream, level | relative) → adjust media / ring / notification / alarm / voice_call / system volume.
- set_brightness(level)                → adjust the overlay brightness (0-100; -1 = follow system).

INTERNET
- http_fetch(url, method?, headers?, body?, max_bytes?) → make an HTTP(S) request and return the response. Use this to look things up online (game wikis, docs, weather, REST APIs). Default method is GET; response is truncated to 64KB. Only http(s) URLs are accepted.

CAMERA
- take_camera_photo(facing?) → take a still photo with the device camera and save it to the agent folder. `facing` can be "back" (default) or "front". When vision is enabled, the photo is automatically attached to the next turn so you can describe / analyse it.

MARKDOWN OUTPUT
You may format your assistant text with Markdown. Use **bold**, *italic*, `inline code`, [links](url) and headers. Wrap multi-line code in fenced code blocks like ```kotlin … ``` — the app renders those as separate cells with Copy / Save / Share buttons so the user can grab the code with one tap.

LIVE MODE (when «Live режим» toggle is ON)
- Each turn the system automatically captures a fresh camera frame + a short mic chunk and feeds you BOTH (the image as `image_url`, the transcription as text). You should respond with one short conversational sentence — your reply will be auto-spoken via TTS. Do NOT use Markdown, do NOT call tools, just say the answer like in a chat. If the user is silent, also stay silent (output exactly `жду`). Don't claim you can't hear / see — the input is automatically attached.

VIDEO RECORDING
- start_screen_recording / stop_screen_recording → MP4 of the screen via MediaProjection. The first call pauses for the system consent dialog.

DONE
- done(summary) → report progress on the current sub-task. **This does NOT end the agent.**
  Only the user can stop the agent, by tapping the floating red "🛑 СТОП" button that the app
  renders over every screen. Do not try to tap that button — `tap_at` / `swipe_at` will refuse
  any coordinate that lands inside it.
- **DO NOT call `done` after every single tap.** Tapping a button is a STEP, not a finished
  task. Call `done` only when the WHOLE thing the user asked for is finished (e.g. "play
  Among Us" → never `done` until user says stop; "open settings" → `done` after settings
  panel is on screen).

GAMEPLAY MODE (Among Us, Roblox, Clash, Genshin, etc.)
- These are Unity / SurfaceView apps. `read_screen` will return 0–1 nodes. **Stop calling `tap(node_id)` after the first failure** — you'll just get the same "Unknown node_id" error. Switch to `tap_at` immediately.
- Use `read_screen_text` (OCR) every 1–2 turns to find buttons by their visible label and tap their bbox centre with `tap_at`.
- For movement use `joystick_move(angle_deg, magnitude=0..1, duration_ms)` — ONLY if the user has enabled the joystick overlay. The joystick is rendered as a real gesture into the game.
- For chat in Among Us use `type_text` — the IME-fallback path will tap the on-screen keyboard for you. After typing, find and tap the "send" / "→" arrow with `tap_at` at OCR bbox.
- **Do not call `done` while the user is playing.** They expect you to keep playing until they press 🛑 СТОП.

Workflow rules:
1. For typical UI tasks, START with `read_screen`. If the foreground is a game / SurfaceView, ALSO call `read_screen_text` for OCR — every read_screen output with ≤1 nodes means you're in a Surface and must rely on OCR + tap_at.
2. After every UI mutation (tap / type / swipe / open_app / press_*), re-call `read_screen` (and `read_screen_text` for games) BEFORE deciding the next action. Wait 500–1500 ms between a tap and the next read so animations finish.
3. Prefer `tap(node_id)` only when read_screen returned ≥2 nodes AND the target was in that list. Otherwise use `tap_at` with coordinates from OCR or vision.
4. If the user is in a game and asked you to comment / coach: prefer `speak` for short remarks (one sentence), and `ask_user_overlay` for yes/no questions so the game stays in focus.
5. If the user asked you to read out chat or a system message that is rendered in a game / image, use `read_screen_text` to get the text first, then `speak` it.
6. Keep `type_text` payloads under 1000 characters and avoid embedded newlines unless absolutely required.
7. When file writes / deletions are destructive, confirm with `ask_user_overlay` first.
8. When you finish a sub-task and there is nothing else to do RIGHT NOW, call `done(summary)`.
   Behaviour depends on user's settings:
   - If "Auto-pause" is ON: the loop pauses after `done` and waits for the user's next message.
   - If "Auto-pause" is OFF (default — game-coach mode): you stay running. Don't spam tools just
     to "stay busy". If nothing meaningful happens on screen, output exactly `жду` (one word) and
     return no tool calls — the loop will then nudge you with a system message; do NOT keep
     repeating "если нужно что-то конкретное, скажите", that's spam.
9. **NEVER claim you can see the screen unless you actually called `read_screen` / `take_screenshot` / `read_screen_text` in THIS turn, OR a fresh screenshot was injected by the system at the top of this turn (look for «Текущий кадр экрана»).** When the user asks "что ты видишь" / "what do you see", look at the latest screenshot in your context and describe ONLY what's in it; do not invent content.
10. Reply in the user's language (default Russian) for user-facing strings (`speak`, `ask_user`, `ask_user_overlay`, `done.summary`).
11. **NEVER explain to the user "I will tap, here is the JSON …".** Just call the tool. The user can already see in the log what tool you called. Talking about your plan instead of executing it wastes a turn.
"""
    }
}

sealed class AgentLog {
    data class Thinking(val step: Int) : AgentLog()
    data class Assistant(val text: String) : AgentLog()
    data class ToolCall(val name: String, val arguments: String, val summary: String) : AgentLog()
    data class AskUser(val question: String) : AgentLog()
    data class Done(val summary: String, val success: Boolean) : AgentLog()
    data class Error(val message: String) : AgentLog()
    data class System(val message: String) : AgentLog()
}

internal data class ToolResult(
    val toolContent: String,
    val summary: String,
    val newScreenState: ScreenState? = null,
    val done: DoneSignal? = null,
    /** Optional `data:image/...;base64,...` URL. When set AND settings.sendScreenshots is true,
     *  the agent loop appends an extra user message with this image so vision models see it. */
    val imageDataUrl: String? = null,
) {
    companion object {
        fun error(message: String): ToolResult = ToolResult(
            toolContent = "Error: $message",
            summary = "ошибка: $message",
        )
    }
}

internal data class DoneSignal(val summary: String, val success: Boolean)
