package com.aiagent.android.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings as AndroidSettings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiagent.android.agent.Agent
import com.aiagent.android.agent.AgentLog
import com.aiagent.android.data.Settings
import com.aiagent.android.llm.ChatMessage
import com.aiagent.android.llm.LlmClient
import com.aiagent.android.overlay.OverlayService
import com.aiagent.android.service.AgentAccessibilityService
import com.aiagent.android.service.ScreenCaptureService
import com.aiagent.android.service.ScreenRecorderService
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = Settings(app)

    private val _state = MutableStateFlow(
        UiState(
            apiKey = settings.apiKey,
            baseUrl = settings.baseUrl,
            model = settings.model,
            maxSteps = settings.maxSteps,
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            reasoningEffort = settings.reasoningEffort,
            systemPrompt = settings.systemPrompt,
            screenFps = settings.screenFps,
            audioSource = settings.audioSource,
            sttProvider = settings.sttProvider,
            ttsRate = settings.ttsRate,
            fileAccessMode = settings.fileAccessMode,
            allowedFolders = settings.allowedFolders.toList(),
            overlayAlpha = settings.overlayAlpha,
            sendScreenshots = settings.sendScreenshots,
            screenshotMaxDim = settings.screenshotMaxDim,
            autoScreenshotEachTurn = settings.autoScreenshotEachTurn,
            autoPauseOnIdle = settings.autoPauseOnIdle,
            useVisionDescriber = settings.useVisionDescriber,
            visionDescriberModel = settings.visionDescriberModel,
            joystickEnabled = settings.joystickEnabled,
            joystickDispatch = settings.joystickDispatch,
            settingsOverlayEnabled = settings.settingsOverlayEnabled,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var currentJob: Job? = null
    private var pendingAnswerChannel: Channel<String>? = null
    private var pendingProjectionChannel: Channel<ProjectionGrant>? = null

    /**
     * The persistent chat history shared across `runAgent()` calls so the user can press
     * "Продолжить" without losing context. Cleared by [resetConversation].
     */
    private val conversation: MutableList<ChatMessage> = mutableListOf()

    init {
        refreshPermissionStatus()
        // Wire the overlay STOP button click into the same code path as the in-app cancel.
        OverlayService.stopListener = { cancelAgent() }
        // Wire the floating ⚙️ settings overlay's "🎤 Сказать задачу" button. The transcript
        // becomes the new instruction; the agent runs (or resumes if it was already running).
        OverlayService.startListener = { transcript ->
            viewModelScope.launch {
                val s = _state.value
                if (s.running && s.pendingQuestion != null) {
                    // The agent is asking a question right now — treat the voice transcript
                    // as the answer.
                    _state.update { it.copy(pendingAnswer = transcript) }
                    submitAnswer()
                } else if (!s.running) {
                    // Idle. Set the instruction and start (or resume) the agent.
                    _state.update { it.copy(instruction = transcript) }
                    runAgent()
                } else {
                    // Running but no open question. Push into the agent's interrupt queue —
                    // it will be drained at the start of the next step and injected as a
                    // fresh user message so the controller sees it.
                    Agent.userInterrupts.offer(transcript)
                    appendLog(LogEntry.System("Голос (агент занят, дойдёт на следующем шаге): $transcript"))
                }
            }
        }
        // Restore the joystick overlay if the user had it enabled in a previous session.
        if (settings.joystickEnabled) {
            com.aiagent.android.overlay.JoystickOverlayService.show(getApplication())
        }
        if (settings.settingsOverlayEnabled) {
            OverlayService.showSettings(getApplication())
        }
    }

    override fun onCleared() {
        super.onCleared()
        OverlayService.stopListener = null
        OverlayService.startListener = null
    }

    fun refreshServiceStatus() {
        _state.update { it.copy(serviceEnabled = AgentAccessibilityService.isRunning()) }
        refreshPermissionStatus()
    }

    fun refreshPermissionStatus() {
        val app = getApplication<Application>()
        val overlay = AndroidSettings.canDrawOverlays(app)
        val manageStorage = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true
        val mic = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        _state.update {
            it.copy(
                overlayGranted = overlay,
                manageStorageGranted = manageStorage,
                micGranted = mic,
                allowedFolders = settings.allowedFolders.toList(),
            )
        }
    }

    fun updateApiKey(value: String) {
        settings.apiKey = value
        _state.update { it.copy(apiKey = value) }
    }

    fun updateBaseUrl(value: String) {
        settings.baseUrl = value
        _state.update { it.copy(baseUrl = value) }
    }

    fun updateModel(value: String) {
        settings.model = value
        _state.update { it.copy(model = value) }
    }

    fun updateMaxSteps(value: Int) {
        settings.maxSteps = value
        _state.update { it.copy(maxSteps = value) }
    }

    fun updateTemperature(value: Float) {
        settings.temperature = value
        _state.update { it.copy(temperature = value) }
    }

    fun updateMaxTokens(value: Int) {
        settings.maxTokens = value
        _state.update { it.copy(maxTokens = value) }
    }

    fun updateReasoningEffort(value: String) {
        settings.reasoningEffort = value
        _state.update { it.copy(reasoningEffort = value) }
    }

    fun updateSystemPrompt(value: String) {
        settings.systemPrompt = value
        _state.update { it.copy(systemPrompt = value) }
    }

    fun updateScreenFps(value: Float) {
        settings.screenFps = value
        _state.update { it.copy(screenFps = value) }
    }

    fun updateAudioSource(value: String) {
        settings.audioSource = value
        _state.update { it.copy(audioSource = value) }
    }

    fun updateSttProvider(value: String) {
        settings.sttProvider = value
        _state.update { it.copy(sttProvider = value) }
    }

    fun updateTtsRate(value: Float) {
        settings.ttsRate = value
        _state.update { it.copy(ttsRate = value) }
    }

    fun updateFileAccessMode(value: String) {
        settings.fileAccessMode = value
        _state.update { it.copy(fileAccessMode = value) }
    }

    fun updateOverlayAlpha(value: Float) {
        settings.overlayAlpha = value
        _state.update { it.copy(overlayAlpha = value) }
    }

    fun updateSendScreenshots(value: Boolean) {
        settings.sendScreenshots = value
        _state.update { it.copy(sendScreenshots = value) }
    }

    fun updateAutoScreenshot(value: Boolean) {
        settings.autoScreenshotEachTurn = value
        _state.update { it.copy(autoScreenshotEachTurn = value) }
    }

    fun updateAutoPauseOnIdle(value: Boolean) {
        settings.autoPauseOnIdle = value
        _state.update { it.copy(autoPauseOnIdle = value) }
    }

    fun updateUseVisionDescriber(value: Boolean) {
        settings.useVisionDescriber = value
        _state.update { it.copy(useVisionDescriber = value) }
    }

    fun updateVisionDescriberModel(value: String) {
        settings.visionDescriberModel = value
        _state.update { it.copy(visionDescriberModel = value) }
    }

    fun updateJoystickEnabled(value: Boolean) {
        settings.joystickEnabled = value
        _state.update { it.copy(joystickEnabled = value) }
        val ctx = getApplication<Application>()
        if (value) {
            com.aiagent.android.overlay.JoystickOverlayService.show(ctx)
        } else {
            com.aiagent.android.overlay.JoystickOverlayService.hide(ctx)
        }
    }

    fun updateJoystickDispatch(value: Boolean) {
        settings.joystickDispatch = value
        _state.update { it.copy(joystickDispatch = value) }
    }

    fun updateSettingsOverlay(value: Boolean) {
        settings.settingsOverlayEnabled = value
        _state.update { it.copy(settingsOverlayEnabled = value) }
        val ctx = getApplication<Application>()
        if (value) {
            OverlayService.showSettings(ctx)
        } else {
            OverlayService.hideSettings(ctx)
        }
    }

    fun updateScreenshotMaxDim(value: Int) {
        settings.screenshotMaxDim = value.coerceAtLeast(256)
        _state.update { it.copy(screenshotMaxDim = settings.screenshotMaxDim) }
    }

    fun copyLogToClipboard() {
        val app = getApplication<Application>()
        val text = _state.value.log.joinToString("\n") { entry ->
            when (entry) {
                is LogEntry.System -> "[${entry.time}] СИСТЕМА: ${entry.text}"
                is LogEntry.Thinking -> "[${entry.time}] ШАГ ${entry.step}: думаю…"
                is LogEntry.Assistant -> "[${entry.time}] АГЕНТ: ${entry.text}"
                is LogEntry.Tool -> "[${entry.time}] ИНСТРУМЕНТ ${entry.name}(${entry.arguments}) → ${entry.summary}"
                is LogEntry.AskUser -> "[${entry.time}] ВОПРОС: ${entry.question}"
                is LogEntry.Done -> "[${entry.time}] ${if (entry.success) "ГОТОВО" else "ПРЕРВАНО"}: ${entry.summary}"
                is LogEntry.Error -> "[${entry.time}] ОШИБКА: ${entry.message}"
            }
        }
        val cm = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("ai-agent log", text))
    }

    fun clearLog() {
        _state.update { it.copy(log = emptyList()) }
    }

    fun addAllowedFolder(uri: String) {
        val newSet = settings.allowedFolders + uri
        settings.allowedFolders = newSet
        _state.update { it.copy(allowedFolders = newSet.toList()) }
    }

    fun removeAllowedFolder(uri: String) {
        val newSet = settings.allowedFolders - uri
        settings.allowedFolders = newSet
        // Also tell Android to forget the persistable URI grant so the agent can no longer access
        // this folder even if it remembered the URI from somewhere.
        runCatching {
            getApplication<Application>().contentResolver.releasePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        _state.update { it.copy(allowedFolders = newSet.toList()) }
    }

    fun updateInstruction(value: String) {
        _state.update { it.copy(instruction = value) }
    }

    private var listenJob: Job? = null

    /**
     * Capture a voice instruction and append to (or replace) the current instruction field.
     * Toggleable: tap once to start, tap again to cancel. Used so the user doesn't have to
     * type to continue/start the agent.
     */
    fun toggleVoiceInstructionInput() {
        val current = listenJob
        if (current?.isActive == true) {
            current.cancel()
            listenJob = null
            _state.update { it.copy(listeningInstruction = false) }
            return
        }
        _state.update { it.copy(listeningInstruction = true) }
        listenJob = viewModelScope.launch {
            val app = getApplication<Application>()
            val transcript = try {
                com.aiagent.android.stt.SpeechToText(app, settings).listenLive(language = null)
            } catch (e: Exception) {
                "[ошибка распознавания: ${e.message ?: e::class.java.simpleName}]"
            }
            _state.update { it.copy(listeningInstruction = false) }
            if (transcript.isNotBlank() && !transcript.startsWith("[")) {
                val existing = _state.value.instruction
                val combined = if (existing.isBlank()) transcript else "$existing $transcript"
                _state.update { it.copy(instruction = combined) }
            } else {
                appendLog(LogEntry.System("Голос: $transcript"))
            }
        }
    }

    /**
     * Start (or resume) the agent. If [conversation] already has prior messages, the new
     * instruction is appended and the loop continues from where it left off ("Продолжить");
     * otherwise the loop starts fresh. Use [resetConversation] to clear history.
     */
    fun runAgent() {
        val instruction = _state.value.instruction.trim()
        if (instruction.isEmpty()) return
        if (_state.value.running) return
        refreshServiceStatus()
        val isResume = conversation.isNotEmpty()
        _state.update {
            it.copy(
                running = true,
                pendingQuestion = null,
                pendingAnswer = "",
                hasConversation = true,
            )
        }
        OverlayService.agentRunning = true
        appendLog(
            LogEntry.System(
                if (isResume) "Продолжаю: $instruction"
                else "Запуск агента: $instruction",
            ),
        )

        // Show the persistent overlay STOP button. Only the user can stop the agent.
        OverlayService.showStop(getApplication())

        val agent = Agent(
            getApplication(),
            settings,
            askUser = { question -> waitForUserAnswer(question) },
            startScreenRecording = { startScreenRecording() },
            stopScreenRecording = { stopScreenRecording() },
            ensureCaptureService = { ensureScreenCaptureService() },
        ) { entry -> appendAgentLog(entry) }

        // Clear the input field so the user knows the message was accepted.
        _state.update { it.copy(instruction = "") }

        currentJob = viewModelScope.launch {
            try {
                agent.run(instruction, conversation)
            } finally {
                _state.update { it.copy(running = false, pendingQuestion = null) }
                pendingAnswerChannel?.close()
                pendingAnswerChannel = null
                OverlayService.agentRunning = false
                OverlayService.hideStop(getApplication())
                appendLog(LogEntry.System("Агент остановлен. История сохранена — нажми «Продолжить» для нового сообщения."))
            }
        }
    }

    fun cancelAgent() {
        currentJob?.cancel()
        currentJob = null
        pendingAnswerChannel?.close()
        pendingAnswerChannel = null
        pendingProjectionChannel?.close()
        pendingProjectionChannel = null
        _state.update { it.copy(running = false, pendingQuestion = null, pendingProjection = false) }
        OverlayService.agentRunning = false
        OverlayService.hideStop(getApplication())
        appendLog(LogEntry.System("Прервано пользователем."))
    }

    /** Clear the persisted conversation so the next run starts fresh. */
    fun resetConversation() {
        conversation.clear()
        _state.update {
            it.copy(
                hasConversation = false,
                log = emptyList(),
            )
        }
        appendLog(LogEntry.System("История диалога очищена."))
    }

    private val _availableModels = MutableStateFlow<List<String>>(emptyList())
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()

    /** Fetch the provider's `/models` list and expose them as a dropdown. */
    fun fetchModelList() {
        if (_state.value.modelsLoading) return
        _state.update { it.copy(modelsLoading = true, modelsError = null) }
        val baseUrl = settings.baseUrl
        val apiKey = settings.apiKey
        viewModelScope.launch {
            val client = LlmClient(baseUrl, apiKey)
            try {
                val ids = client.listModels()
                _availableModels.value = ids
                _state.update { it.copy(modelsLoading = false, availableModels = ids) }
                appendLog(LogEntry.System("Загружено моделей: ${ids.size}"))
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        modelsLoading = false,
                        modelsError = e.message ?: e::class.java.simpleName,
                    )
                }
                appendLog(LogEntry.Error("Не удалось загрузить модели: ${e.message}"))
            } finally {
                client.close()
            }
        }
    }

    fun updatePendingAnswer(value: String) {
        _state.update { it.copy(pendingAnswer = value) }
    }

    fun submitAnswer() {
        val answer = _state.value.pendingAnswer.trim()
        val ch = pendingAnswerChannel ?: return
        if (answer.isEmpty()) return
        viewModelScope.launch { ch.send(answer) }
    }

    private suspend fun waitForUserAnswer(question: String): String {
        val ch = Channel<String>(capacity = 1)
        pendingAnswerChannel = ch
        _state.update { it.copy(pendingQuestion = question, pendingAnswer = "") }
        return try {
            ch.receive()
        } catch (_: Throwable) {
            "(пользователь отменил)"
        } finally {
            pendingAnswerChannel = null
            _state.update { it.copy(pendingQuestion = null, pendingAnswer = "") }
        }
    }

    /** Called from the Agent's `start_screen_recording` tool. Suspends until the user grants. */
    private suspend fun startScreenRecording(): String {
        if (ScreenRecorderService.isRecording) return "уже идёт запись"
        val ch = Channel<ProjectionGrant>(capacity = 1)
        pendingProjectionChannel = ch
        _state.update { it.copy(pendingProjection = true) }
        val grant = try {
            ch.receive()
        } catch (_: Throwable) {
            return "запись отменена"
        } finally {
            pendingProjectionChannel = null
            _state.update { it.copy(pendingProjection = false) }
        }
        if (grant.resultCode == 0 || grant.data == null) return "пользователь отказал в записи"
        val app = getApplication<Application>()
        val intent = Intent(app, ScreenRecorderService::class.java).apply {
            action = ScreenRecorderService.ACTION_START
            putExtra(ScreenRecorderService.EXTRA_RESULT_CODE, grant.resultCode)
            putExtra(ScreenRecorderService.EXTRA_DATA, grant.data)
        }
        if (Build.VERSION.SDK_INT >= 26) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
        // Give the recorder a moment to start.
        kotlinx.coroutines.delay(400)
        return if (ScreenRecorderService.lastError != null) {
            "ошибка записи: ${ScreenRecorderService.lastError}"
        } else {
            "запись начата"
        }
    }

    /**
     * Ensures a long-lived [ScreenCaptureService] is running so the agent can grab screenshots
     * via MediaProjection (the universal fallback for devices where the Accessibility
     * `takeScreenshot()` API is blocked by the OEM, e.g. Realme / Vivo / older Xiaomi).
     */
    private suspend fun ensureScreenCaptureService(): Boolean {
        if (ScreenCaptureService.isRunning) return true
        val ch = Channel<ProjectionGrant>(capacity = 1)
        pendingProjectionChannel = ch
        _state.update { it.copy(pendingProjection = true) }
        appendLog(LogEntry.System("Запрашиваю разрешение на захват экрана. Подтвердите в системном диалоге."))
        val grant = try {
            ch.receive()
        } catch (_: Throwable) {
            return false
        } finally {
            pendingProjectionChannel = null
            _state.update { it.copy(pendingProjection = false) }
        }
        if (grant.resultCode == 0 || grant.data == null) {
            appendLog(LogEntry.System("Пользователь отказал в захвате экрана."))
            return false
        }
        val app = getApplication<Application>()
        val intent = Intent(app, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, grant.resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, grant.data)
        }
        if (Build.VERSION.SDK_INT >= 26) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
        // Give the service a moment to spin up its VirtualDisplay.
        kotlinx.coroutines.delay(700)
        return ScreenCaptureService.isRunning
    }

    private fun stopScreenRecording(): String {
        if (!ScreenRecorderService.isRecording) return "запись не велась"
        val app = getApplication<Application>()
        val intent = Intent(app, ScreenRecorderService::class.java).apply {
            action = ScreenRecorderService.ACTION_STOP
        }
        app.startService(intent)
        val file = ScreenRecorderService.lastFile ?: ""
        return "запись остановлена, файл: $file"
    }

    /** Called by [MainActivity] after the system MediaProjection consent dialog returns. */
    fun onProjectionResult(resultCode: Int, data: Intent?) {
        val ch = pendingProjectionChannel
        if (ch != null) {
            viewModelScope.launch { ch.send(ProjectionGrant(resultCode, data)) }
        }
    }

    fun isProjectionPending(): Boolean = pendingProjectionChannel != null

    /** Persist a SAF tree URI grant so the agent can reach the picked folder later. */
    fun onFolderPicked(uri: Uri) {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        addAllowedFolder(uri.toString())
    }

    private suspend fun appendAgentLog(entry: AgentLog) {
        val log = when (entry) {
            is AgentLog.Thinking -> LogEntry.Thinking(entry.step)
            is AgentLog.Assistant -> LogEntry.Assistant(entry.text)
            is AgentLog.ToolCall -> LogEntry.Tool(entry.name, entry.arguments, entry.summary)
            is AgentLog.AskUser -> LogEntry.AskUser(entry.question)
            is AgentLog.Done -> LogEntry.Done(entry.summary, entry.success)
            is AgentLog.Error -> LogEntry.Error(entry.message)
            is AgentLog.System -> LogEntry.System(entry.message)
        }
        appendLog(log)
    }

    private fun appendLog(entry: LogEntry) {
        _state.update { state ->
            state.copy(log = state.log + entry.copyWithTime())
        }
    }
}

data class UiState(
    val instruction: String = "",
    val running: Boolean = false,
    /** True while the platform SpeechRecognizer is actively listening for the instruction field. */
    val listeningInstruction: Boolean = false,
    val serviceEnabled: Boolean = false,
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val maxSteps: Int = 10_000,
    val temperature: Float = 0.2f,
    val maxTokens: Int = 2048,
    val reasoningEffort: String = "",
    val systemPrompt: String = "",
    val log: List<LogEntry> = emptyList(),
    /** When non-null, the agent is waiting for the user to answer this question. */
    val pendingQuestion: String? = null,
    val pendingAnswer: String = "",
    /** When true, the agent has requested screen recording and the UI must show the consent button. */
    val pendingProjection: Boolean = false,

    // Game-assistant settings.
    val screenFps: Float = 0f,
    val audioSource: String = "mic",
    val sttProvider: String = "groq",
    val ttsRate: Float = 1.0f,
    val fileAccessMode: String = "saf",
    val allowedFolders: List<String> = emptyList(),
    val overlayAlpha: Float = 0.5f,
    val sendScreenshots: Boolean = false,
    val screenshotMaxDim: Int = 1024,
    val autoScreenshotEachTurn: Boolean = true,
    val autoPauseOnIdle: Boolean = false,
    val useVisionDescriber: Boolean = false,
    val visionDescriberModel: String = "meta-llama/llama-4-scout-17b-16e-instruct",
    val joystickEnabled: Boolean = false,
    val joystickDispatch: Boolean = true,
    val settingsOverlayEnabled: Boolean = false,

    // Runtime permission status.
    val overlayGranted: Boolean = false,
    val manageStorageGranted: Boolean = false,
    val micGranted: Boolean = false,

    // Conversation / model picker.
    /** True once the user has run at least one instruction. Switches the run button label
     *  from "Запустить" to "Продолжить" and reveals the "Начать заново" button. */
    val hasConversation: Boolean = false,
    val availableModels: List<String> = emptyList(),
    val modelsLoading: Boolean = false,
    val modelsError: String? = null,
)

data class ProjectionGrant(val resultCode: Int, val data: Intent?)

sealed class LogEntry(val time: String) {
    abstract fun copyWithTime(): LogEntry

    data class System(val text: String, private val t: String = now()) : LogEntry(t) {
        override fun copyWithTime() = copy(t = time)
    }
    data class Thinking(val step: Int, private val t: String = now()) : LogEntry(t) {
        override fun copyWithTime() = copy(t = time)
    }
    data class Assistant(val text: String, private val t: String = now()) : LogEntry(t) {
        override fun copyWithTime() = copy(t = time)
    }
    data class Tool(val name: String, val arguments: String, val summary: String, private val t: String = now()) :
        LogEntry(t) {
        override fun copyWithTime() = copy(t = time)
    }
    data class AskUser(val question: String, private val t: String = now()) : LogEntry(t) {
        override fun copyWithTime() = copy(t = time)
    }
    data class Done(val summary: String, val success: Boolean, private val t: String = now()) : LogEntry(t) {
        override fun copyWithTime() = copy(t = time)
    }
    data class Error(val message: String, private val t: String = now()) : LogEntry(t) {
        override fun copyWithTime() = copy(t = time)
    }

    companion object {
        private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        fun now(): String = fmt.format(Date())
    }
}
