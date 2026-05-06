package com.aiagent.android.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            viewModel.onProjectionResult(result.resultCode, result.data)
        }

    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.refreshPermissionStatus()
        }

    private val manageStorageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.refreshPermissionStatus()
        }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.refreshPermissionStatus()
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.refreshPermissionStatus()
        }

    private val openTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                viewModel.onFolderPicked(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(
                        viewModel = viewModel,
                        onRequestProjection = ::launchProjectionConsent,
                        onRequestOverlay = ::launchOverlayPermission,
                        onRequestManageStorage = ::launchManageStoragePermission,
                        onRequestMic = ::requestMicPermission,
                        onRequestCamera = ::requestCameraPermission,
                        onPickFolder = ::launchPickFolder,
                        onOpenAgentFolder = ::openAgentFolder,
                    )
                }
            }
        }
    }

    private fun launchProjectionConsent() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun launchOverlayPermission() {
        val intent = Intent(
            AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        overlayLauncher.launch(intent)
    }

    private fun launchManageStoragePermission() {
        if (Build.VERSION.SDK_INT < 30) return
        val intent = Intent(
            AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        manageStorageLauncher.launch(intent)
    }

    private fun requestMicPermission() {
        // If already granted, the only way to revoke is to send the user to the app permissions
        // page. If not granted, ask the system for it.
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            openAppPermissionsPage()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestCameraPermission() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            openAppPermissionsPage()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * Resolve the agent's "own folder" (creates it if needed) and copy its absolute path to
     * the clipboard with a toast — most file managers can paste this path directly.
     * We don't try to launch a specific file manager because each OEM ships a different one
     * and there is no universal "open this folder" intent that all of them honour.
     */
    private fun openAgentFolder() {
        val publicDocs = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOCUMENTS,
        )
        val candidates = listOfNotNull(
            java.io.File(publicDocs, "AI-Agent"),
            getExternalFilesDir(null),
            filesDir,
        )
        val target = candidates.firstOrNull { dir ->
            runCatching { dir.mkdirs() }.getOrDefault(false) || dir.exists()
        } ?: filesDir
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("ai-agent-folder", target.absolutePath))
        android.widget.Toast.makeText(
            this,
            "Папка агента: ${target.absolutePath}\n(путь скопирован в буфер)",
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }

    private fun openAppPermissionsPage() {
        val intent = Intent(
            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun launchPickFolder() {
        openTreeLauncher.launch(null)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshServiceStatus()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    viewModel: MainViewModel,
    onRequestProjection: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestManageStorage: () -> Unit,
    onRequestMic: () -> Unit,
    onRequestCamera: () -> Unit,
    onPickFolder: () -> Unit,
    onOpenAgentFolder: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    // Auto-launch the system MediaProjection consent dialog as soon as the agent (or another
    // viewmodel path) flips `pendingProjection`. Saves the user an extra tap.
    LaunchedEffect(state.pendingProjection) {
        if (state.pendingProjection) onRequestProjection()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("AI Agent") })
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Агент") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Настройки") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Доступы") })
            }
            when (tab) {
                0 -> AgentTab(
                    state = state,
                    onInstruction = viewModel::updateInstruction,
                    onRun = viewModel::runAgent,
                    onCancel = viewModel::cancelAgent,
                    onResetConversation = viewModel::resetConversation,
                    onCopyLog = viewModel::copyLogToClipboard,
                    onClearLog = viewModel::clearLog,
                    onPendingAnswer = viewModel::updatePendingAnswer,
                    onSubmitAnswer = viewModel::submitAnswer,
                    onVoiceInstruction = viewModel::toggleVoiceInstructionInput,
                    onJoystickEnabled = viewModel::updateJoystickEnabled,
                    onJoystickDispatch = viewModel::updateJoystickDispatch,
                    onJoystickX = viewModel::updateJoystickX,
                    onJoystickY = viewModel::updateJoystickY,
                    onJoystickRadius = viewModel::updateJoystickRadius,
                    onResetJoystickPlacement = viewModel::resetJoystickPlacement,
                    onLiveMode = viewModel::updateLiveMode,
                    onLiveTurnSeconds = viewModel::updateLiveTurnSeconds,
                    onLiveCameraFacing = viewModel::updateLiveCameraFacing,
                    onSettingsOverlay = viewModel::updateSettingsOverlay,
                    onAllowProjection = onRequestProjection,
                    onOpenAccessibility = {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
                1 -> SettingsTab(
                    state = state,
                    onApiKey = viewModel::updateApiKey,
                    onBaseUrl = viewModel::updateBaseUrl,
                    onSendScreenshots = viewModel::updateSendScreenshots,
                    onScreenshotMaxDim = viewModel::updateScreenshotMaxDim,
                    onAutoScreenshot = viewModel::updateAutoScreenshot,
                    onAutoPauseOnIdle = viewModel::updateAutoPauseOnIdle,
                    onUseVisionDescriber = viewModel::updateUseVisionDescriber,
                    onVisionDescriberModel = viewModel::updateVisionDescriberModel,
                    onModel = viewModel::updateModel,
                    onMaxSteps = viewModel::updateMaxSteps,
                    onTemperature = viewModel::updateTemperature,
                    onMaxTokens = viewModel::updateMaxTokens,
                    onReasoningEffort = viewModel::updateReasoningEffort,
                    onSystemPrompt = viewModel::updateSystemPrompt,
                    onScreenFps = viewModel::updateScreenFps,
                    onSttProvider = viewModel::updateSttProvider,
                    onTtsRate = viewModel::updateTtsRate,
                    onAudioSource = viewModel::updateAudioSource,
                    onOverlayAlpha = viewModel::updateOverlayAlpha,
                    onFetchModels = viewModel::fetchModelList,
                )
                2 -> PermissionsTab(
                    state = state,
                    onOpenAccessibility = {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    onRequestOverlay = onRequestOverlay,
                    onRequestManageStorage = onRequestManageStorage,
                    onRequestMic = onRequestMic,
                    onRequestCamera = onRequestCamera,
                    onPickFolder = onPickFolder,
                    onOpenAgentFolder = onOpenAgentFolder,
                    onRemoveFolder = viewModel::removeAllowedFolder,
                    onSetFileMode = viewModel::updateFileAccessMode,
                )
            }
        }
    }
}

@Composable
fun AgentTab(
    state: UiState,
    onInstruction: (String) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onResetConversation: () -> Unit,
    onCopyLog: () -> Unit,
    onClearLog: () -> Unit,
    onPendingAnswer: (String) -> Unit,
    onSubmitAnswer: () -> Unit,
    onVoiceInstruction: () -> Unit,
    onJoystickEnabled: (Boolean) -> Unit,
    onJoystickDispatch: (Boolean) -> Unit,
    onJoystickX: (Int) -> Unit,
    onJoystickY: (Int) -> Unit,
    onJoystickRadius: (Int) -> Unit,
    onResetJoystickPlacement: () -> Unit,
    onLiveMode: (Boolean) -> Unit,
    onLiveTurnSeconds: (Int) -> Unit,
    onLiveCameraFacing: (String) -> Unit,
    onSettingsOverlay: (Boolean) -> Unit,
    onAllowProjection: () -> Unit,
    onOpenAccessibility: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.log.size) {
        if (state.log.isNotEmpty()) {
            listState.animateScrollToItem(state.log.size - 1)
        }
    }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (state.serviceEnabled) Color(0xFFDCEDC8) else Color(0xFFFFE0B2),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (state.serviceEnabled) {
                        "Служба Спецвозможностей включена."
                    } else {
                        "Включите службу Спецвозможностей AI Agent, чтобы агент мог управлять устройством."
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.height(0.dp))
                OutlinedButton(onClick = onOpenAccessibility) { Text("Открыть настройки") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            OutlinedTextField(
                value = state.instruction,
                onValueChange = onInstruction,
                label = { Text("Что должен сделать агент?") },
                placeholder = { Text("например: Открой Настройки и включи режим энергосбережения") },
                modifier = Modifier.weight(1f),
                minLines = 2,
                maxLines = 5,
                enabled = !state.running,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onVoiceInstruction,
                enabled = !state.running,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.listeningInstruction) Color(0xFFC62828) else Color(0xFF4527A0),
                    contentColor = Color.White,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(56.dp),
            ) {
                Text(if (state.listeningInstruction) "● стоп" else "🎤")
            }
        }
        if (state.listeningInstruction) {
            Text(
                "Слушаю — говори. Нажми снова, чтобы отменить.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFC62828),
            )
        }
        Spacer(Modifier.height(8.dp))
        if (state.running) {
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFC62828),
                    contentColor = Color.White,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("■  СТОП  Прервать текущий шаг") }
            Text(
                "Кнопка СТОП также видна поверх любого приложения — её можно нажать в игре.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onRun,
                    enabled = state.instruction.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.hasConversation) "▶  Продолжить" else "▶  Запустить")
                }
                if (state.hasConversation) {
                    OutlinedButton(
                        onClick = onResetConversation,
                    ) { Text("Начать заново") }
                }
            }
            if (state.hasConversation) {
                Text(
                    "Диалог сохранён. «Продолжить» добавит твоё сообщение к существующей беседе. " +
                        "«Начать заново» сбросит историю.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (state.joystickEnabled) Color(0xFFD1C4E9) else Color(0xFFEEEEEE),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "🕹️  Виртуальный джойстик",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = state.joystickEnabled,
                        onCheckedChange = onJoystickEnabled,
                    )
                }
                if (state.joystickEnabled) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Двигай ползунки ниже чтобы поставить джойстик ровно поверх внутриигрового. " +
                            "Альтернатива: долгое нажатие на сам джойстик → режим настройки " +
                            "(тяни пальцем чтобы перенести, разводи двумя — изменить размер, " +
                            "тапни ещё раз чтобы выйти).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    val displayMetrics = LocalContext.current.resources.displayMetrics
                    val maxX = displayMetrics.widthPixels.coerceAtLeast(1)
                    val maxY = displayMetrics.heightPixels.coerceAtLeast(1)
                    Text(
                        "Положение по X: ${state.joystickX} px",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = state.joystickX.toFloat().coerceIn(0f, maxX.toFloat()),
                        onValueChange = { onJoystickX(it.toInt()) },
                        valueRange = 0f..maxX.toFloat(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Положение по Y: ${state.joystickY} px",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = state.joystickY.toFloat().coerceIn(0f, maxY.toFloat()),
                        onValueChange = { onJoystickY(it.toInt()) },
                        valueRange = 0f..maxY.toFloat(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Радиус: ${state.joystickRadius} px",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = state.joystickRadius.toFloat().coerceIn(40f, 600f),
                        onValueChange = { onJoystickRadius(it.toInt()) },
                        valueRange = 40f..600f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = onResetJoystickPlacement,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Сбросить положение и размер") }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = state.joystickDispatch,
                            onCheckedChange = onJoystickDispatch,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Передавать жесты в игру (через спецвозможности)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // Live mode (Gemini-Live-style polling): camera + mic chunk per turn, agent reply spoken aloud.
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (state.liveMode) Color(0xFFFFE0B2) else Color(0xFFEEEEEE),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "🎙️  Live режим (камера + микрофон + голос)",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = state.liveMode,
                        onCheckedChange = onLiveMode,
                    )
                }
                Text(
                    "Каждый шаг агент берёт кадр с камеры + ${state.liveTurnSeconds}с микрофона, " +
                        "отвечает текстом и произносит ответ голосом. " +
                        "Нужны разрешения CAMERA и RECORD_AUDIO. STOP-оверлей выключает мгновенно.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.liveMode) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Длительность шага: ${state.liveTurnSeconds} с",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = state.liveTurnSeconds.toFloat().coerceIn(2f, 15f),
                        onValueChange = { onLiveTurnSeconds(it.toInt()) },
                        valueRange = 2f..15f,
                        steps = 12,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Камера: ", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = state.liveCameraFacing == "front",
                            onClick = { onLiveCameraFacing("front") },
                            label = { Text("Фронтальная") },
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = state.liveCameraFacing == "back",
                            onClick = { onLiveCameraFacing("back") },
                            label = { Text("Задняя") },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (state.settingsOverlayEnabled) Color(0xFFB3E5FC) else Color(0xFFEEEEEE),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "⚙️  Настройки в плавающем окне",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = state.settingsOverlayEnabled,
                        onCheckedChange = onSettingsOverlay,
                    )
                }
                if (state.settingsOverlayEnabled) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Поверх любого приложения появится ⚙️ кнопка. Тапни — раскроется панель с " +
                            "переключателями: джойстик, двухмодельный режим, авто-скриншот и т.д. " +
                            "Двигай кнопку перетаскиванием.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (state.pendingQuestion != null) {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Агент ждёт ваш ответ:",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFFE65100),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(state.pendingQuestion, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.pendingAnswer,
                        onValueChange = onPendingAnswer,
                        label = { Text("Ваш ответ") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 4,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onSubmitAnswer,
                        enabled = state.pendingAnswer.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Отправить ответ") }
                }
            }
        }
        if (state.pendingProjection) {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Агент хочет записать экран",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFF0D47A1),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Чтобы записать видео, Android требует ваше согласие. " +
                            "Нажмите кнопку ниже — появится системный диалог. " +
                            "Видео сохранится в /Android/data/com.aiagent.android/files/Movies/AI Agent.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onAllowProjection,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Разрешить запись экрана") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Журнал",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = onCopyLog,
                enabled = state.log.isNotEmpty(),
            ) { Text("Копировать всё") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = onClearLog,
                enabled = state.log.isNotEmpty(),
            ) { Text("Очистить") }
        }
        SelectionContainer(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF5F5F5), RoundedCornerShape(8.dp))
                    .padding(8.dp),
            ) {
                items(state.log) { entry ->
                    LogRow(entry)
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    // Assistant messages are the only ones we render with full Markdown — including code
    // cells with copy / save / share buttons. Everything else stays plain & monospace
    // because the user mostly scans those for diagnostics.
    if (entry is LogEntry.Assistant) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                "АГЕНТ ${entry.time}",
                color = Color(0xFF1B5E20),
                style = MaterialTheme.typography.labelSmall,
            )
            MarkdownText(
                source = entry.text,
                color = Color(0xFF1B5E20),
            )
        }
        return
    }
    val (label, body, color) = when (entry) {
        is LogEntry.System -> Triple("СИСТЕМА ${entry.time}", entry.text, Color(0xFF455A64))
        is LogEntry.Thinking -> Triple("ШАГ ${entry.step} ${entry.time}", "думаю…", Color(0xFF1976D2))
        is LogEntry.Assistant -> Triple("АГЕНТ ${entry.time}", entry.text, Color(0xFF1B5E20))
        is LogEntry.Tool -> Triple("ИНСТРУМЕНТ ${entry.time}", "${entry.name}(${entry.arguments}) → ${entry.summary}", Color(0xFF6A1B9A))
        is LogEntry.AskUser -> Triple("ВОПРОС ${entry.time}", entry.question, Color(0xFFE65100))
        is LogEntry.Done -> Triple("ГОТОВО ${entry.time}", entry.summary, if (entry.success) Color(0xFF2E7D32) else Color(0xFFC62828))
        is LogEntry.Error -> Triple("ОШИБКА ${entry.time}", entry.message, Color(0xFFC62828))
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = color, style = MaterialTheme.typography.labelSmall)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            overflow = TextOverflow.Visible,
        )
    }
}

@Composable
fun SettingsTab(
    state: UiState,
    onApiKey: (String) -> Unit,
    onBaseUrl: (String) -> Unit,
    onModel: (String) -> Unit,
    onMaxSteps: (Int) -> Unit,
    onTemperature: (Float) -> Unit,
    onMaxTokens: (Int) -> Unit,
    onReasoningEffort: (String) -> Unit,
    onSystemPrompt: (String) -> Unit,
    onScreenFps: (Float) -> Unit,
    onSttProvider: (String) -> Unit,
    onTtsRate: (Float) -> Unit,
    onAudioSource: (String) -> Unit,
    onOverlayAlpha: (Float) -> Unit,
    onSendScreenshots: (Boolean) -> Unit,
    onScreenshotMaxDim: (Int) -> Unit,
    onAutoScreenshot: (Boolean) -> Unit,
    onAutoPauseOnIdle: (Boolean) -> Unit,
    onUseVisionDescriber: (Boolean) -> Unit,
    onVisionDescriberModel: (String) -> Unit,
    onFetchModels: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Провайдер", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = onApiKey,
            label = { Text("API-ключ") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = onBaseUrl,
            label = { Text("Base URL (OpenAI-совместимый)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        var modelDropdownOpen by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = state.model,
            onValueChange = onModel,
            label = { Text("Модель") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    onFetchModels()
                    modelDropdownOpen = true
                },
                enabled = !state.modelsLoading && state.apiKey.isNotBlank() && state.baseUrl.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (state.modelsLoading) "Загружаю…"
                    else if (state.availableModels.isEmpty()) "Загрузить список моделей"
                    else "Список моделей (${state.availableModels.size})",
                )
            }
            if (state.availableModels.isNotEmpty()) {
                Box {
                    OutlinedButton(onClick = { modelDropdownOpen = true }) { Text("Выбрать ▾") }
                    DropdownMenu(
                        expanded = modelDropdownOpen,
                        onDismissRequest = { modelDropdownOpen = false },
                    ) {
                        state.availableModels.forEach { id ->
                            DropdownMenuItem(
                                text = { Text(id) },
                                onClick = {
                                    onModel(id)
                                    modelDropdownOpen = false
                                },
                            )
                        }
                    }
                }
            }
        }
        if (state.modelsError != null) {
            Text(
                "Ошибка загрузки: ${state.modelsError}",
                color = Color(0xFFC62828),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(4.dp))
        Text("Генерация", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.temperature.toString(),
            onValueChange = {
                val v = it.toFloatOrNull()?.coerceIn(0f, 2f)
                if (v != null) onTemperature(v) else onTemperature(state.temperature)
            },
            label = { Text("Температура (0.0 – 2.0)") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
        )
        OutlinedTextField(
            value = state.maxTokens.toString(),
            onValueChange = { onMaxTokens(it.toIntOrNull()?.coerceIn(0, 32768) ?: state.maxTokens) },
            label = { Text("Макс токенов ответа (0 = дефолт провайдера)") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
        OutlinedTextField(
            value = state.reasoningEffort,
            onValueChange = onReasoningEffort,
            label = { Text("Глубина рассуждений (low / medium / high; пусто = выкл)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(Modifier.height(4.dp))
        Text("Агент", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.maxSteps.toString(),
            onValueChange = { onMaxSteps(it.toIntOrNull()?.coerceIn(1, 100_000) ?: state.maxSteps) },
            label = { Text("Максимум шагов за запуск (1 – 100000)") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
        OutlinedTextField(
            value = state.systemPrompt,
            onValueChange = onSystemPrompt,
            label = { Text("Системный промпт (пусто = встроенный по умолчанию)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 8,
        )

        Spacer(Modifier.height(4.dp))
        Text("Захват экрана", style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (state.screenFps <= 0f) "Режим: Авто (захват по требованию агента)"
            else "Режим: ${"%.1f".format(state.screenFps)} fps",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = state.screenFps,
            onValueChange = onScreenFps,
            valueRange = 0f..10f,
            steps = 19, // 0.5 step increments
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "0 = авто (только когда агент явно просит). Иначе ограничение скорости — например, " +
                "при 2 fps агент не сможет сделать больше двух скриншотов в секунду.",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(4.dp))
        Text("Голос", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.ttsRate.toString(),
            onValueChange = {
                val v = it.toFloatOrNull()?.coerceIn(0.5f, 2.5f) ?: state.ttsRate
                onTtsRate(v)
            },
            label = { Text("Скорость TTS (0.5 – 2.5; 1.0 = норма)") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip("groq Whisper", state.sttProvider == "groq") { onSttProvider("groq") }
            ChoiceChip("Android STT", state.sttProvider == "android") { onSttProvider("android") }
        }
        Text(
            "groq использует whisper-large-v3 поверх вашего API-ключа Groq (точность выше, требует интернет). " +
                "Android — встроенный распознаватель Google (бесплатно, на устройстве, короче).",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(4.dp))
        Text("Источник аудио", style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip("Микрофон", state.audioSource == "mic") { onAudioSource("mic") }
            ChoiceChip("Системный звук", state.audioSource == "system") { onAudioSource("system") }
        }
        Text(
            "Захват системного звука работает только на Android 10+ и только если приложение-источник " +
                "не помечает свой звук как DO_NOT_CAPTURE (большинство игр и мессенджеров блокируют).",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(4.dp))
        Text("Прозрачность overlay", style = MaterialTheme.typography.titleMedium)
        Text("${(state.overlayAlpha * 100).toInt()} %", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = state.overlayAlpha,
            onValueChange = onOverlayAlpha,
            valueRange = 0.1f..1.0f,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Text("Зрение модели (vision)", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(
                checked = state.sendScreenshots,
                onCheckedChange = onSendScreenshots,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (state.sendScreenshots) "Отправлять скриншоты в модель"
                else "Скриншоты не отправляются (только текст)",
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "Включай ТОЛЬКО для vision-моделей. На Groq это: " +
                "meta-llama/llama-4-scout-17b-16e-instruct, " +
                "meta-llama/llama-4-maverick-17b-128e-instruct. " +
                "Текстовая openai/gpt-oss-120b картинки НЕ принимает и вернёт 400.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.sendScreenshots) {
            Text(
                "Размер скриншота: ${state.screenshotMaxDim} px (макс. сторона)",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = state.screenshotMaxDim.toFloat(),
                onValueChange = { onScreenshotMaxDim(it.toInt()) },
                valueRange = 384f..1536f,
                steps = 7,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(8.dp))
        Text("Поведение агента", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(
                checked = state.autoScreenshotEachTurn,
                onCheckedChange = onAutoScreenshot,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Скрин перед каждым шагом (агент всегда видит экран)",
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "Снимок добавляется в контекст модели автоматически в начале каждого шага. " +
                "Работает только если выбрана vision-модель.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(
                checked = state.autoPauseOnIdle,
                onCheckedChange = onAutoPauseOnIdle,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (state.autoPauseOnIdle) "Пауза, когда модель отвечает 'готово'"
                else "Не паузить — агент работает до нажатия СТОП",
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "Выкл = режим помощника в игре: агент не закроется сам, продолжает крутиться " +
                "пока ты не нажмёшь красную кнопку «СТОП» в overlay.",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(8.dp))
        Text("Двухмодельный режим (глаза + мозг)", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(
                checked = state.useVisionDescriber,
                onCheckedChange = onUseVisionDescriber,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (state.useVisionDescriber) "ВКЛ: vision-модель описывает экран, главная управляет"
                else "ВЫКЛ: одна модель и видит, и управляет",
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "Когда ВКЛ: перед каждым шагом скрин уходит в модель-наблюдателя — она пишет " +
                "текстовое описание экрана. Главная модель (поле «Модель» сверху) получает " +
                "это описание + дерево спецвозможностей и решает что делать. Так можно " +
                "поставить мощную текстовую gpt-oss-120b как «мозг», а llama-4-scout — " +
                "как «глаза».",
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.useVisionDescriber) {
            OutlinedTextField(
                value = state.visionDescriberModel,
                onValueChange = onVisionDescriberModel,
                label = { Text("Модель-наблюдатель (vision)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Text(
                "Должна поддерживать картинки. На Groq это, например, " +
                    "meta-llama/llama-4-scout-17b-16e-instruct.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Text(
            "Поддерживается любой OpenAI-совместимый chat completions API с tool calling. Примеры: " +
                "Groq — https://api.groq.com/openai/v1, модель openai/gpt-oss-120b. " +
                "OpenAI — https://api.openai.com/v1, модель gpt-4o-mini. " +
                "OpenRouter — https://openrouter.ai/api/v1. " +
                "Локальный llama.cpp — http://10.0.2.2:8080/v1.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun PermissionsTab(
    state: UiState,
    onOpenAccessibility: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestManageStorage: () -> Unit,
    onRequestMic: () -> Unit,
    onRequestCamera: () -> Unit,
    onPickFolder: () -> Unit,
    onOpenAgentFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onSetFileMode: (String) -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Системные доступы", style = MaterialTheme.typography.titleMedium)

        PermissionRow(
            label = "Спецвозможности (управление экраном)",
            granted = state.serviceEnabled,
            actionLabel = if (state.serviceEnabled) "Отключить" else "Включить",
            onClick = onOpenAccessibility,
        )
        PermissionRow(
            label = "Overlay поверх других приложений (50%)",
            granted = state.overlayGranted,
            actionLabel = if (state.overlayGranted) "Отключить" else "Разрешить",
            onClick = onRequestOverlay,
        )
        PermissionRow(
            label = "Микрофон (для listen / record_audio)",
            granted = state.micGranted,
            actionLabel = if (state.micGranted) "Отозвать" else "Разрешить",
            onClick = onRequestMic,
        )
        PermissionRow(
            label = "Камера (для take_camera_photo)",
            granted = state.cameraGranted,
            actionLabel = if (state.cameraGranted) "Отозвать" else "Разрешить",
            onClick = onRequestCamera,
        )

        Spacer(Modifier.height(4.dp))
        Text("Файлы и папки", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onOpenAgentFolder, modifier = Modifier.fillMaxWidth()) {
            Text("📁  Папка агента (скопировать путь)")
        }
        Text(
            "По умолчанию агент видит только папки, которые вы выберете ниже (Storage Access Framework). " +
                "Альтернативно можно выдать полный доступ ко всем файлам — Android запросит специальное " +
                "разрешение в системных настройках.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip("Только выбранные папки", state.fileAccessMode == "saf") { onSetFileMode("saf") }
            ChoiceChip("Все файлы", state.fileAccessMode == "all") { onSetFileMode("all") }
            ChoiceChip("Только приватная папка", state.fileAccessMode == "app") { onSetFileMode("app") }
        }
        if (state.fileAccessMode == "all") {
            PermissionRow(
                label = "MANAGE_EXTERNAL_STORAGE (полный доступ)",
                granted = state.manageStorageGranted,
                actionLabel = if (state.manageStorageGranted) "Отключить" else "Разрешить",
                onClick = onRequestManageStorage,
            )
        }
        if (state.fileAccessMode == "saf") {
            Button(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
                Text("➕  Добавить папку, к которой агент получит доступ")
            }
            if (state.allowedFolders.isEmpty()) {
                Text(
                    "Нет добавленных папок. Пока что агент сможет работать только с приватной папкой " +
                        "приложения (/Android/data/com.aiagent.android/files).",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                state.allowedFolders.forEach { uri ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(uri, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { onRemoveFolder(uri) }) { Text("✕") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    actionLabel: String,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (granted) Color(0xFFDCEDC8) else Color(0xFFFFE0B2),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                Column {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (granted) "Разрешено" else "Не разрешено",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (granted) Color(0xFF2E7D32) else Color(0xFFC62828),
                    )
                }
            }
            OutlinedButton(onClick = onClick) { Text(actionLabel) }
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val container = if (selected) Color(0xFF1976D2) else Color(0xFFE0E0E0)
    val content = if (selected) Color.White else Color.Black
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) { Text(label) }
}
