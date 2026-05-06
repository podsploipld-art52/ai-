package com.aiagent.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Persistent user-configurable settings for the agent. */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // One-shot migration: builds prior to v3 shipped with maxSteps default = 20. The
        // user explicitly asked for ≥ 10000. Bump any persisted value that's still at the
        // old default (or any small value) so they don't have to re-set it after upgrade.
        if (!prefs.getBoolean(KEY_MIGRATED_MAX_STEPS_V3, false)) {
            val current = prefs.getInt(KEY_MAX_STEPS, DEFAULT_MAX_STEPS)
            prefs.edit {
                if (current < 100) putInt(KEY_MAX_STEPS, DEFAULT_MAX_STEPS)
                putBoolean(KEY_MIGRATED_MAX_STEPS_V3, true)
            }
        }
        // One-shot migration: previous builds defaulted reasoning_effort to "low" and
        // unconditionally sent it on every chat call. Most non-OpenAI models reject this
        // with HTTP 400, so we now ship with "" by default. Existing installs are migrated
        // exactly once: any "low" value (the previous default) is cleared.
        if (!prefs.getBoolean(KEY_MIGRATED_REASONING_V3, false)) {
            val current = prefs.getString(KEY_REASONING_EFFORT, "") ?: ""
            prefs.edit {
                if (current == "low") putString(KEY_REASONING_EFFORT, "")
                putBoolean(KEY_MIGRATED_REASONING_V3, true)
            }
        }
        // One-shot migration: previous builds defaulted to a TEXT-ONLY model
        // (openai/gpt-oss-120b) AND defaulted sendScreenshots = false, so the agent could
        // never actually see the screen. The user expectation is "одна модель видит и играет",
        // so we now ship with a vision-capable model and screenshots enabled. Existing
        // installs are migrated exactly once — anyone still on the old text-only default is
        // moved to the new vision-capable default and gets sendScreenshots flipped on.
        if (!prefs.getBoolean(KEY_MIGRATED_VISION_DEFAULTS_V4, false)) {
            val currentModel = prefs.getString(KEY_MODEL, "") ?: ""
            prefs.edit {
                if (currentModel == "openai/gpt-oss-120b" || currentModel.isBlank()) {
                    putString(KEY_MODEL, DEFAULT_MODEL)
                    putBoolean(KEY_SEND_SCREENSHOTS, true)
                }
                putBoolean(KEY_MIGRATED_VISION_DEFAULTS_V4, true)
            }
        }
    }

    var apiKey: String
        get() = prefs.getString(KEY_API, "") ?: ""
        set(value) = prefs.edit { putString(KEY_API, value) }

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit { putString(KEY_BASE_URL, value) }

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit { putString(KEY_MODEL, value) }

    var maxSteps: Int
        get() = prefs.getInt(KEY_MAX_STEPS, DEFAULT_MAX_STEPS)
        set(value) = prefs.edit { putInt(KEY_MAX_STEPS, value) }

    /** Sampling temperature. Stored as a float; 0.0..2.0. */
    var temperature: Float
        get() = prefs.getFloat(KEY_TEMPERATURE, DEFAULT_TEMPERATURE)
        set(value) = prefs.edit { putFloat(KEY_TEMPERATURE, value) }

    /** Maximum completion tokens for a single LLM turn. 0 means "do not send" (use server default). */
    var maxTokens: Int
        get() = prefs.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS)
        set(value) = prefs.edit { putInt(KEY_MAX_TOKENS, value) }

    /** Reasoning effort for reasoning-enabled models (e.g. gpt-oss-*). Empty = do not send. */
    var reasoningEffort: String
        get() = prefs.getString(KEY_REASONING_EFFORT, DEFAULT_REASONING_EFFORT) ?: DEFAULT_REASONING_EFFORT
        set(value) = prefs.edit { putString(KEY_REASONING_EFFORT, value) }

    /** System prompt steering the agent. Empty = use built-in default. */
    var systemPrompt: String
        get() = prefs.getString(KEY_SYSTEM_PROMPT, "") ?: ""
        set(value) = prefs.edit { putString(KEY_SYSTEM_PROMPT, value) }

    // --- Game-assistant features ---------------------------------------------------------------

    /** Frames-per-second for screen captures used by `take_screenshot` / `read_screen_text`.
     *  Special value 0.0 = "auto" (capture on demand, after each agent action). */
    var screenFps: Float
        get() = prefs.getFloat(KEY_SCREEN_FPS, DEFAULT_SCREEN_FPS)
        set(value) = prefs.edit { putFloat(KEY_SCREEN_FPS, value) }

    /** Where the agent listens by default: `mic` (always works) or `system` (MediaProjection,
     *  requires Android 10+ AND that the source app allows playback capture). */
    var audioSource: String
        get() = prefs.getString(KEY_AUDIO_SOURCE, DEFAULT_AUDIO_SOURCE) ?: DEFAULT_AUDIO_SOURCE
        set(value) = prefs.edit { putString(KEY_AUDIO_SOURCE, value) }

    /** Speech-to-text provider: `groq` (Groq Whisper) or `android` (built-in SpeechRecognizer). */
    var sttProvider: String
        get() = prefs.getString(KEY_STT_PROVIDER, DEFAULT_STT_PROVIDER) ?: DEFAULT_STT_PROVIDER
        set(value) = prefs.edit { putString(KEY_STT_PROVIDER, value) }

    /** TTS engine name. Currently only `android` (built-in TextToSpeech) is implemented. */
    var ttsEngine: String
        get() = prefs.getString(KEY_TTS_ENGINE, DEFAULT_TTS_ENGINE) ?: DEFAULT_TTS_ENGINE
        set(value) = prefs.edit { putString(KEY_TTS_ENGINE, value) }

    /** Speech rate for TTS. 1.0 = normal. */
    var ttsRate: Float
        get() = prefs.getFloat(KEY_TTS_RATE, DEFAULT_TTS_RATE)
        set(value) = prefs.edit { putFloat(KEY_TTS_RATE, value) }

    /** File access mode: `saf` (only user-picked folders), `all` (MANAGE_EXTERNAL_STORAGE), or
     *  `app` (only this app's private storage). */
    var fileAccessMode: String
        get() = prefs.getString(KEY_FILE_MODE, DEFAULT_FILE_MODE) ?: DEFAULT_FILE_MODE
        set(value) = prefs.edit { putString(KEY_FILE_MODE, value) }

    /** Set of `content://` SAF tree URIs the user has granted to the agent. */
    var allowedFolders: Set<String>
        get() = prefs.getStringSet(KEY_ALLOWED_FOLDERS, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_ALLOWED_FOLDERS, value) }

    /** Overlay opacity (0..1). Default 0.5 = 50% as the user requested. */
    var overlayAlpha: Float
        get() = prefs.getFloat(KEY_OVERLAY_ALPHA, DEFAULT_OVERLAY_ALPHA)
        set(value) = prefs.edit { putFloat(KEY_OVERLAY_ALPHA, value) }

    /**
     * When true, the agent attaches a downscaled screenshot to the next user message every time
     * `read_screen` / `take_screenshot` runs. Requires a vision-capable model
     * (e.g. meta-llama/llama-4-scout-17b-16e-instruct on Groq, gpt-4o on OpenAI).
     * Text-only models will reject the request.
     */
    var sendScreenshots: Boolean
        get() = prefs.getBoolean(KEY_SEND_SCREENSHOTS, DEFAULT_SEND_SCREENSHOTS)
        set(value) = prefs.edit { putBoolean(KEY_SEND_SCREENSHOTS, value) }

    /** Maximum dimension (px) for screenshots sent to the model. Smaller = fewer tokens. */
    var screenshotMaxDim: Int
        get() = prefs.getInt(KEY_SCREENSHOT_MAX_DIM, DEFAULT_SCREENSHOT_MAX_DIM)
        set(value) = prefs.edit { putInt(KEY_SCREENSHOT_MAX_DIM, value) }

    /**
     * When true, the agent loop pauses after the model calls `done` or returns a plain text
     * reply with no tool calls. The user resumes with «Продолжить». When false (default for
     * the gameplay use-case) the agent keeps running until the user taps the overlay STOP
     * button — useful when you want it to coach you live during a game.
     */
    var autoPauseOnIdle: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PAUSE, DEFAULT_AUTO_PAUSE)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_PAUSE, value) }

    /**
     * When true, the agent automatically captures a fresh screenshot at the start of every
     * step and injects it into the conversation as a user message. Means the model never has
     * to remember to call `read_screen` itself; it always has the current screen state.
     * Default true — required for gameplay where the screen is changing constantly.
     */
    var autoScreenshotEachTurn: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SCREENSHOT, DEFAULT_AUTO_SCREENSHOT)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_SCREENSHOT, value) }

    /**
     * Two-model mode: the controller (`model`) is text-only, and a separate vision model
     * (`visionDescriberModel`) is asked to describe each fresh screenshot. The controller then
     * receives the textual description plus the accessibility tree and decides actions. Lets
     * the user run a powerful but text-only model (e.g. gpt-oss-120b) as the brain while
     * delegating pixel parsing to a vision-capable model.
     */
    var useVisionDescriber: Boolean
        get() = prefs.getBoolean(KEY_USE_VISION_DESCRIBER, DEFAULT_USE_VISION_DESCRIBER)
        set(value) = prefs.edit { putBoolean(KEY_USE_VISION_DESCRIBER, value) }

    /** Model id used by the vision describer when [useVisionDescriber] is enabled. */
    var visionDescriberModel: String
        get() = prefs.getString(KEY_VISION_DESCRIBER_MODEL, DEFAULT_VISION_DESCRIBER_MODEL)
            ?: DEFAULT_VISION_DESCRIBER_MODEL
        set(value) = prefs.edit { putString(KEY_VISION_DESCRIBER_MODEL, value) }

    // --- Virtual joystick overlay ---------------------------------------------------------------

    /** When true, the floating virtual joystick is shown on top of all apps. */
    var joystickEnabled: Boolean
        get() = prefs.getBoolean(KEY_JOYSTICK_ENABLED, DEFAULT_JOYSTICK_ENABLED)
        set(value) = prefs.edit { putBoolean(KEY_JOYSTICK_ENABLED, value) }

    /** X coordinate (px, screen-space) of the joystick base centre. */
    var joystickX: Int
        get() = prefs.getInt(KEY_JOYSTICK_X, DEFAULT_JOYSTICK_X)
        set(value) = prefs.edit { putInt(KEY_JOYSTICK_X, value) }

    /** Y coordinate (px, screen-space) of the joystick base centre. */
    var joystickY: Int
        get() = prefs.getInt(KEY_JOYSTICK_Y, DEFAULT_JOYSTICK_Y)
        set(value) = prefs.edit { putInt(KEY_JOYSTICK_Y, value) }

    /** Radius (px) of the joystick base. The thumb travels within this circle. */
    var joystickRadius: Int
        get() = prefs.getInt(KEY_JOYSTICK_RADIUS, DEFAULT_JOYSTICK_RADIUS)
        set(value) = prefs.edit { putInt(KEY_JOYSTICK_RADIUS, value) }

    /**
     * When true, dragging the overlay joystick generates a synchronized in-app drag at the
     * SAME screen coordinates via the AccessibilityService. Use case: place the overlay
     * joystick directly over the game's built-in joystick — the gesture passes through.
     */
    var joystickDispatch: Boolean
        get() = prefs.getBoolean(KEY_JOYSTICK_DISPATCH, DEFAULT_JOYSTICK_DISPATCH)
        set(value) = prefs.edit { putBoolean(KEY_JOYSTICK_DISPATCH, value) }

    /**
     * When true, a persistent ⚙️ floating button is shown on top of all apps. Tapping it
     * expands a small panel with toggles for joystick / two-model mode / auto-screenshot etc.
     * Lets the user adjust settings without leaving the game.
     */
    var settingsOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_SETTINGS_OVERLAY, DEFAULT_SETTINGS_OVERLAY)
        set(value) = prefs.edit { putBoolean(KEY_SETTINGS_OVERLAY, value) }

    companion object {
        const val PREFS_NAME = "agent_prefs"
        const val DEFAULT_BASE_URL = "https://api.groq.com/openai/v1"
        // Default to a vision-capable model so the agent can SEE the screen out of the box.
        // Llama 4 Scout is free/fast on Groq and supports image inputs. The user can switch
        // to any other model in Settings; vision-related toggles auto-adjust based on the model.
        const val DEFAULT_MODEL = "meta-llama/llama-4-scout-17b-16e-instruct"
        const val DEFAULT_MAX_STEPS = 10_000
        const val DEFAULT_TEMPERATURE = 0.2f
        const val DEFAULT_MAX_TOKENS = 2048
        // Empty by default: most providers/models do NOT accept this parameter and will
        // return HTTP 400 if it is sent. The Settings UI lets the user opt in.
        const val DEFAULT_REASONING_EFFORT = ""

        /** 0.0 = auto (capture-on-demand). Otherwise frames per second. */
        const val DEFAULT_SCREEN_FPS = 0.0f

        const val DEFAULT_AUDIO_SOURCE = "mic"
        const val DEFAULT_STT_PROVIDER = "groq"
        const val DEFAULT_TTS_ENGINE = "android"
        const val DEFAULT_TTS_RATE = 1.0f
        const val DEFAULT_FILE_MODE = "saf"
        const val DEFAULT_OVERLAY_ALPHA = 0.5f
        // Vision is ON by default — combined with auto-screenshot it means the model always
        // sees the current screen, which is the only way the agent can play games like Among Us.
        const val DEFAULT_SEND_SCREENSHOTS = true
        const val DEFAULT_SCREENSHOT_MAX_DIM = 1024
        // Game-mode defaults: never auto-pause, always have a fresh screenshot in front of the
        // model. The user can flip these in Settings if they prefer the "ask once, get answer"
        // pattern of a normal chatbot.
        const val DEFAULT_AUTO_PAUSE = false
        const val DEFAULT_AUTO_SCREENSHOT = true
        // Two-model mode is OFF by default; user opts in. When ON the default observer is
        // Llama-4 Scout on Groq (free, fast, vision-capable, returns Russian-friendly prose).
        const val DEFAULT_USE_VISION_DESCRIBER = false
        const val DEFAULT_VISION_DESCRIBER_MODEL = "meta-llama/llama-4-scout-17b-16e-instruct"

        const val DEFAULT_JOYSTICK_ENABLED = false
        const val DEFAULT_JOYSTICK_X = 250
        const val DEFAULT_JOYSTICK_Y = 900
        const val DEFAULT_JOYSTICK_RADIUS = 180
        const val DEFAULT_JOYSTICK_DISPATCH = true
        const val DEFAULT_SETTINGS_OVERLAY = false

        private const val KEY_API = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_MAX_STEPS = "max_steps"
        private const val KEY_MIGRATED_MAX_STEPS_V3 = "migrated_max_steps_v3"
        private const val KEY_MIGRATED_REASONING_V3 = "migrated_reasoning_v3"
        private const val KEY_MIGRATED_VISION_DEFAULTS_V4 = "migrated_vision_defaults_v4"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_REASONING_EFFORT = "reasoning_effort"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"

        private const val KEY_SCREEN_FPS = "screen_fps"
        private const val KEY_AUDIO_SOURCE = "audio_source"
        private const val KEY_STT_PROVIDER = "stt_provider"
        private const val KEY_TTS_ENGINE = "tts_engine"
        private const val KEY_TTS_RATE = "tts_rate"
        private const val KEY_FILE_MODE = "file_mode"
        private const val KEY_ALLOWED_FOLDERS = "allowed_folders"
        private const val KEY_OVERLAY_ALPHA = "overlay_alpha"
        private const val KEY_SEND_SCREENSHOTS = "send_screenshots"
        private const val KEY_SCREENSHOT_MAX_DIM = "screenshot_max_dim"
        private const val KEY_AUTO_PAUSE = "auto_pause_on_idle"
        private const val KEY_AUTO_SCREENSHOT = "auto_screenshot_each_turn"
        private const val KEY_USE_VISION_DESCRIBER = "use_vision_describer"
        private const val KEY_VISION_DESCRIBER_MODEL = "vision_describer_model"
        private const val KEY_JOYSTICK_ENABLED = "joystick_enabled"
        private const val KEY_JOYSTICK_X = "joystick_x"
        private const val KEY_JOYSTICK_Y = "joystick_y"
        private const val KEY_JOYSTICK_RADIUS = "joystick_radius"
        private const val KEY_JOYSTICK_DISPATCH = "joystick_dispatch"
        private const val KEY_SETTINGS_OVERLAY = "settings_overlay"
    }
}
