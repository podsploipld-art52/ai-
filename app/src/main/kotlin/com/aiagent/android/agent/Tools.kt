package com.aiagent.android.agent

import com.aiagent.android.llm.FunctionDef
import com.aiagent.android.llm.Tool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** All tool schemas exposed to the LLM. */
object Tools {

    private val json = Json { encodeDefaults = true }

    fun toolList(): List<Tool> = listOf(
        Tool(function = readScreen),
        Tool(function = readScreenText),
        Tool(function = takeScreenshot),
        Tool(function = tap),
        Tool(function = tapAt),
        Tool(function = swipe),
        Tool(function = swipeAt),
        Tool(function = typeText),
        Tool(function = pressBack),
        Tool(function = pressHome),
        Tool(function = pressRecents),
        Tool(function = openApp),
        Tool(function = waitMs),
        Tool(function = askUser),
        Tool(function = askUserOverlay),
        Tool(function = speak),
        Tool(function = joystickMove),
        Tool(function = listenMic),
        Tool(function = recordAudioAndTranscribe),
        Tool(function = deviceInfo),
        Tool(function = listFiles),
        Tool(function = readFile),
        Tool(function = writeFile),
        Tool(function = makeDir),
        Tool(function = deleteFile),
        Tool(function = startScreenRecording),
        Tool(function = stopScreenRecording),
        Tool(function = listApps),
        Tool(function = getClipboard),
        Tool(function = setClipboard),
        Tool(function = setVolume),
        Tool(function = setBrightness),
        Tool(function = httpFetch),
        Tool(function = takeCameraPhoto),
        Tool(function = projectWrite),
        Tool(function = projectRead),
        Tool(function = projectList),
        Tool(function = projectDelete),
        Tool(function = openProjectInBrowser),
        Tool(function = done),
    )

    private val readScreen = FunctionDef(
        name = "read_screen",
        description = "Capture a textual snapshot of the currently visible UI. " +
            "Returns the foreground app package and a numbered list of interactive nodes. " +
            "Always call this once at the start of a task and again after navigation actions.",
        parameters = obj { put("type", "object"); putJsonObject("properties") {} },
    )

    private val readScreenText = FunctionDef(
        name = "read_screen_text",
        description = "Recognise on-screen text via on-device OCR (ML Kit). Useful for games or " +
            "video players where the Accessibility tree exposes nothing — labels rendered into a " +
            "SurfaceView are still picked up. Returns blocks of recognised text with their pixel " +
            "bounding boxes. Slower than read_screen — prefer read_screen first when an a11y " +
            "tree is available.",
        parameters = obj { put("type", "object"); putJsonObject("properties") {} },
    )

    private val takeScreenshot = FunctionDef(
        name = "take_screenshot",
        description = "Capture the screen as an in-memory image and save it to the app's " +
            "private folder. Returns the absolute path of the saved PNG. Throttled by the FPS " +
            "setting (default = on demand).",
        parameters = obj { put("type", "object"); putJsonObject("properties") {} },
    )

    private val tap = FunctionDef(
        name = "tap",
        description = "Tap a UI node by the integer id from the most recent read_screen call.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("node_id") {
                    put("type", "integer")
                    put("description", "Node id from read_screen output")
                }
            }
            put("required", arr("node_id"))
        },
    )

    private val tapAt = FunctionDef(
        name = "tap_at",
        description = "Tap at absolute screen coordinates in pixels. Prefer tap(node_id) when possible.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("x") { put("type", "integer") }
                putJsonObject("y") { put("type", "integer") }
            }
            put("required", arr("x", "y"))
        },
    )

    private val swipe = FunctionDef(
        name = "swipe",
        description = "Swipe in a cardinal direction (up/down/left/right). " +
            "Use 'up' to scroll content downward (move finger up).",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("direction") {
                    put("type", "string")
                    put("enum", arr("up", "down", "left", "right"))
                }
                putJsonObject("distance") {
                    put("type", "string")
                    put("enum", arr("short", "medium", "long"))
                    put("description", "Defaults to medium")
                }
            }
            put("required", arr("direction"))
        },
    )

    private val swipeAt = FunctionDef(
        name = "swipe_at",
        description = "Swipe between two specific screen coordinates.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("x1") { put("type", "integer") }
                putJsonObject("y1") { put("type", "integer") }
                putJsonObject("x2") { put("type", "integer") }
                putJsonObject("y2") { put("type", "integer") }
                putJsonObject("duration_ms") { put("type", "integer") }
            }
            put("required", arr("x1", "y1", "x2", "y2"))
        },
    )

    private val typeText = FunctionDef(
        name = "type_text",
        description = "Type the given text into an editable field. If node_id is provided, types into that node; " +
            "otherwise types into the currently focused editable field.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") { put("type", "string") }
                putJsonObject("node_id") {
                    put("type", "integer")
                    put("description", "Optional node id of an editable field from read_screen")
                }
            }
            put("required", arr("text"))
        },
    )

    private val pressBack = FunctionDef(
        name = "press_back",
        description = "Press the system Back button.",
        parameters = obj { put("type", "object"); putJsonObject("properties") {} },
    )

    private val pressHome = FunctionDef(
        name = "press_home",
        description = "Go to the home screen.",
        parameters = obj { put("type", "object"); putJsonObject("properties") {} },
    )

    private val pressRecents = FunctionDef(
        name = "press_recents",
        description = "Open the recents/overview screen.",
        parameters = obj { put("type", "object"); putJsonObject("properties") {} },
    )

    private val openApp = FunctionDef(
        name = "open_app",
        description = "Launch an installed app by its package name (e.g. 'com.android.settings').",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("package_name") { put("type", "string") }
            }
            put("required", arr("package_name"))
        },
    )

    private val waitMs = FunctionDef(
        name = "wait",
        description = "Wait for the given number of milliseconds (max 5000) for animations or content to load.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("ms") { put("type", "integer") }
            }
            put("required", arr("ms"))
        },
    )

    private val askUser = FunctionDef(
        name = "ask_user",
        description = "Ask the human user a clarifying question and pause execution until they answer. " +
            "Use this when the task is ambiguous, requires a choice (yes/no, picking an item, " +
            "providing a value the user did not give), or when you need confirmation before a " +
            "potentially destructive action. The user's answer is returned as the tool result. " +
            "Prefer concrete short questions in the user's language.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("question") {
                    put("type", "string")
                    put("description", "The question to show the user, in their language.")
                }
            }
            put("required", arr("question"))
        },
    )

    private val askUserOverlay = FunctionDef(
        name = "ask_user_overlay",
        description = "Ask the user a question via a floating overlay that stays on top of the " +
            "current app (works during gameplay). Pass a question string and optionally an " +
            "`options` array (1–6 short choices) to render as buttons — like a quiz. The user " +
            "can also tap the microphone button to answer by voice without leaving the game, " +
            "or tap '✕' to dismiss. " +
            "Result: the chosen option text, or the spoken answer (text), or 'dismiss'. " +
            "If no options array is provided, the buttons are 'Да' / 'Нет' / '🎤 Голос' / '✕'.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("question") {
                    put("type", "string")
                    put("description", "Short question, one or two sentences max.")
                }
                putJsonObject("options") {
                    put("type", "array")
                    put("description", "Optional 1–6 short answer choices to show as buttons " +
                        "(e.g. ['Идти налево', 'Идти направо', 'Подождать']). If omitted, " +
                        "yes/no buttons are used.")
                    putJsonObject("items") {
                        put("type", "string")
                    }
                }
            }
            put("required", arr("question"))
        },
    )

    private val speak = FunctionDef(
        name = "speak",
        description = "Say the given text out loud via the device's TTS engine. Use this when the user " +
            "asked the agent to speak / give voice feedback during gameplay so they can keep their " +
            "eyes on the game.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "What to say. Keep it short (one sentence is best).")
                }
                putJsonObject("rate") {
                    put("type", "number")
                    put("description", "Speech rate; 1.0 is normal, 0.5 slow, 2.0 very fast. Optional.")
                }
            }
            put("required", arr("text"))
        },
    )

    private val joystickMove = FunctionDef(
        name = "joystick_move",
        description = "Push the virtual joystick overlay in a direction for a duration, then " +
            "release. Useful for moving a character in 2D games. Requires the user to have " +
            "enabled the joystick overlay AND placed it over the game's built-in joystick. " +
            "Direction can be 'north'/'south'/'east'/'west'/'northeast'/'northwest'/" +
            "'southeast'/'southwest', or an arbitrary angle in degrees (0=east, 90=south, " +
            "180=west, 270=north). Returns immediately; the move continues in the background.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("direction") {
                    put("type", "string")
                    put(
                        "description",
                        "One of: north, south, east, west, northeast, northwest, southeast, southwest. " +
                            "Or use 'angle' for an arbitrary direction.",
                    )
                }
                putJsonObject("angle") {
                    put("type", "number")
                    put(
                        "description",
                        "Angle in degrees (0=east, 90=south, 180=west, 270=north). " +
                            "Use this OR `direction`, not both.",
                    )
                }
                putJsonObject("magnitude") {
                    put("type", "number")
                    put(
                        "description",
                        "Stick deflection 0..1 (1 = full push, 0.5 = half). Default 1.0.",
                    )
                }
                putJsonObject("duration_ms") {
                    put("type", "integer")
                    put(
                        "description",
                        "How long to hold the stick (ms) before releasing. Default 600.",
                    )
                }
            }
        },
    )

    private val listenMic = FunctionDef(
        name = "listen",
        description = "Listen to the microphone for one short utterance and return the transcribed " +
            "text. Uses the on-device SpeechRecognizer. Returns immediately when the user stops " +
            "speaking or after a short timeout.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("language") {
                    put("type", "string")
                    put("description", "BCP-47 tag like 'ru-RU' or 'en-US'. Optional.")
                }
            }
        },
    )

    private val recordAudioAndTranscribe = FunctionDef(
        name = "record_audio",
        description = "Record N seconds of microphone audio and transcribe via Whisper (Groq). " +
            "Use this when 'listen' is too short — for example to capture a longer voice chat " +
            "from a game speaker held next to the phone, or to get a higher-accuracy transcript.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("seconds") {
                    put("type", "integer")
                    put("description", "Recording duration. Default 6, max 60.")
                }
                putJsonObject("language") {
                    put("type", "string")
                    put("description", "BCP-47 / ISO-639-1 tag. Optional.")
                }
            }
        },
    )

    private val deviceInfo = FunctionDef(
        name = "device_info",
        description = "Return a multi-section text dump of device information: model / OS / screen / " +
            "RAM / storage / battery / network / hardware features. Useful when the user asks " +
            "'what phone am I on' or 'how much battery is left'.",
        parameters = obj { put("type", "object"); putJsonObject("properties") {} },
    )

    private val listFiles = FunctionDef(
        name = "list_files",
        description = "List the contents of a folder. The path can be either a SAF tree URI " +
            "(content://...), a path relative to one of the allowed folders the user picked in " +
            "settings, or — when 'all-files-access' is enabled — an absolute path " +
            "(/storage/emulated/0/Download).",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") { put("type", "string") }
            }
            put("required", arr("path"))
        },
    )

    private val readFile = FunctionDef(
        name = "read_file",
        description = "Read up to 64 KiB from a text file. The path follows the same rules as list_files.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") { put("type", "string") }
                putJsonObject("max_bytes") {
                    put("type", "integer")
                    put("description", "Optional limit, default 65536")
                }
            }
            put("required", arr("path"))
        },
    )

    private val writeFile = FunctionDef(
        name = "write_file",
        description = "Write text to a file (creating it if needed). Same path rules as list_files.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") { put("type", "string") }
                putJsonObject("content") { put("type", "string") }
                putJsonObject("mime_type") {
                    put("type", "string")
                    put("description", "Optional MIME type for new files (default text/plain)")
                }
            }
            put("required", arr("path", "content"))
        },
    )

    private val makeDir = FunctionDef(
        name = "make_dir",
        description = "Create a folder (and any parent folders) at the given path.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") { put("type", "string") }
            }
            put("required", arr("path"))
        },
    )

    private val deleteFile = FunctionDef(
        name = "delete_file",
        description = "Delete a file or folder (recursively). Use with caution.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") { put("type", "string") }
            }
            put("required", arr("path"))
        },
    )

    private val startScreenRecording = FunctionDef(
        name = "start_screen_recording",
        description = "Begin recording the device screen as an MP4 video. The first call in a session " +
            "will pause until the user grants the system MediaProjection consent dialog (this is a " +
            "hard Android requirement; the agent cannot bypass it). Use this when the user asked you " +
            "to record a guide / demo / how-to video. Always pair it with `stop_screen_recording` once " +
            "the demonstration is finished. The result string contains the path to the saved file.",
        parameters = obj { put("type", "object"); putJsonObject("properties") {} },
    )

    private val stopScreenRecording = FunctionDef(
        name = "stop_screen_recording",
        description = "Stop the current screen recording and finalize the MP4 file. Returns the path " +
            "to the saved video. Safe to call even if no recording is in progress.",
        parameters = obj { put("type", "object"); putJsonObject("properties") {} },
    )

    private val listApps = FunctionDef(
        name = "list_apps",
        description = "List installed applications on the device. By default returns only apps " +
            "that have a launcher icon (apps the user can actually open from the home screen). " +
            "Set include_system=true to also return system apps without launcher icons (kernel " +
            "services, providers, etc.). Each entry is `display_name | package | launchable`.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("include_system") {
                    put("type", "boolean")
                    put("description", "Include all installed packages, not just launchable ones. " +
                        "Default false.")
                }
                putJsonObject("filter") {
                    put("type", "string")
                    put("description", "Optional case-insensitive substring filter applied to the " +
                        "display name AND the package name.")
                }
            }
        },
    )

    private val getClipboard = FunctionDef(
        name = "get_clipboard",
        description = "Read the current text content of the system clipboard. Returns an empty " +
            "string if the clipboard is empty or contains non-text data.",
        parameters = obj { put("type", "object"); putJsonObject("properties") {} },
    )

    private val setClipboard = FunctionDef(
        name = "set_clipboard",
        description = "Replace the system clipboard with the given text. Useful to make data " +
            "available to other apps via paste.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") { put("type", "string") }
            }
            put("required", arr("text"))
        },
    )

    private val setVolume = FunctionDef(
        name = "set_volume",
        description = "Change the device volume for one stream. `stream` is one of: music, ring, " +
            "notification, alarm, voice_call, system. `level` is an integer 0..max where max is " +
            "stream-specific. Alternatively pass `relative` (-100..+100) as a percentage delta.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("stream") {
                    put("type", "string")
                    put("description", "music | ring | notification | alarm | voice_call | system. " +
                        "Default: music.")
                }
                putJsonObject("level") {
                    put("type", "integer")
                    put("description", "Absolute level (0..max for the stream). Mutually exclusive " +
                        "with `relative`.")
                }
                putJsonObject("relative") {
                    put("type", "integer")
                    put("description", "Percentage delta -100..+100. Mutually exclusive with `level`.")
                }
            }
        },
    )

    private val setBrightness = FunctionDef(
        name = "set_brightness",
        description = "Change the screen brightness for the current activity (0..100 percent). " +
            "Note: this only affects this app while it is in the foreground; when overlay is " +
            "shown, also adjusts the overlay window. Cannot change global system brightness " +
            "without WRITE_SETTINGS — that path is not used.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("level") {
                    put("type", "integer")
                    put("description", "0 = dimmest, 100 = brightest. -1 = follow system.")
                }
            }
            put("required", arr("level"))
        },
    )

    private val httpFetch = FunctionDef(
        name = "http_fetch",
        description = "Make an HTTP request and return the response body. Use this to look " +
            "things up on the open internet (game wikis, recipes, weather, REST APIs). " +
            "Only http(s) URLs are accepted; the response is truncated to ~64 KB to keep the " +
            "context window manageable. Default method is GET.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("url") {
                    put("type", "string")
                    put("description", "Full URL including scheme.")
                }
                putJsonObject("method") {
                    put("type", "string")
                    put("enum", arr("GET", "POST", "PUT", "DELETE", "PATCH"))
                    put("description", "HTTP method. Defaults to GET.")
                }
                putJsonObject("headers") {
                    put("type", "object")
                    put("description", "Optional headers as a flat JSON object of strings.")
                }
                putJsonObject("body") {
                    put("type", "string")
                    put("description", "Request body for POST/PUT/PATCH.")
                }
                putJsonObject("max_bytes") {
                    put("type", "integer")
                    put("description", "Truncate response body to this many bytes. Default 65536.")
                }
            }
            put("required", arr("url"))
        },
    )

    private val takeCameraPhoto = FunctionDef(
        name = "take_camera_photo",
        description = "Take a single still photo with the device camera and save it to the " +
            "agent's folder. Returns the absolute path. When vision is enabled, the photo is " +
            "also fed into the model on the next turn so you can describe / analyse it.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("facing") {
                    put("type", "string")
                    put("enum", arr("back", "front"))
                    put("description", "Which camera to use. Defaults to back.")
                }
            }
        },
    )

    private val projectWrite = FunctionDef(
        name = "project_write",
        description = "Write a file inside the agent's own projects folder " +
            "(`Documents/AI-Agent/projects/<project>/<file>` when reachable, otherwise app-private). " +
            "Use this whenever the user asks for a multi-file project (a game, a website, a small " +
            "Android app etc.) — these files do NOT need any storage permission and are visible to " +
            "the user via the file manager. The user can also tap «Сохранить» on a code cell to " +
            "drop one-off snippets into a parallel folder.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("project") {
                    put("type", "string")
                    put("description", "Project name. Letters, digits, dot, dash, underscore only.")
                }
                putJsonObject("file") {
                    put("type", "string")
                    put("description", "Relative path inside the project, e.g. `src/main.kt`. No leading slash, no `..`.")
                }
                putJsonObject("content") {
                    put("type", "string")
                }
            }
            put("required", arr("project", "file", "content"))
        },
    )

    private val projectRead = FunctionDef(
        name = "project_read",
        description = "Read a file from the agent's projects folder. Truncated to 64KB.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("project") { put("type", "string") }
                putJsonObject("file") { put("type", "string") }
                putJsonObject("max_bytes") { put("type", "integer") }
            }
            put("required", arr("project", "file"))
        },
    )

    private val projectList = FunctionDef(
        name = "project_list",
        description = "List the contents of a project (or all projects if no name given). " +
            "Returns one entry per line as `d|f  <relative-path>  <bytes>`.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("project") {
                    put("type", "string")
                    put("description", "Optional. Omit to list every project root.")
                }
            }
        },
    )

    private val openProjectInBrowser = FunctionDef(
        name = "open_project_in_browser",
        description = "Launch the system browser to open a file from a project. Spins up a local " +
            "HTTP server on 127.0.0.1 on the device (random port) that serves files out of the " +
            "agent's projects folder, then opens the URL via Intent.ACTION_VIEW. " +
            "Use this RIGHT AFTER finishing an HTML/CSS/JS project (e.g. a small game, a website) " +
            "so the user can immediately see the result. If `file` is omitted, defaults to " +
            "`index.html`. Returns the URL.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("project") { put("type", "string") }
                putJsonObject("file") {
                    put("type", "string")
                    put("description", "Optional. Default `index.html`.")
                }
            }
            put("required", arr("project"))
        },
    )

    private val projectDelete = FunctionDef(
        name = "project_delete",
        description = "Delete a file inside a project, or the entire project when `file` is omitted.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("project") { put("type", "string") }
                putJsonObject("file") {
                    put("type", "string")
                    put("description", "Optional. Relative path inside the project. Omit to remove the whole project.")
                }
            }
            put("required", arr("project"))
        },
    )

    private val done = FunctionDef(
        name = "done",
        description = "Signal that the user's task has been completed (or cannot be completed). " +
            "Provide a short natural-language summary of what was done or why it failed.",
        // Keep this schema *loose* on purpose: smaller open models routinely emit
        // `success="true"` (string) instead of `success=true` (boolean), which Groq's
        // strict validator rejects with HTTP 400 'expected boolean, but got string'. We
        // accept any type here and parse defensively in Agent.kt.
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("summary") { put("type", "string") }
            }
            put("required", arr("summary"))
        },
    )

    private fun obj(builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject(builder)

    private fun arr(vararg values: String): kotlinx.serialization.json.JsonArray =
        kotlinx.serialization.json.JsonArray(values.map { kotlinx.serialization.json.JsonPrimitive(it) })
}
