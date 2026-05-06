package com.aiagent.android.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Minimal subset of OpenAI's chat-completions request/response structures, with tool calling.
 * Compatible with OpenAI, OpenRouter, Together, vLLM and most other OpenAI-shaped servers.
 */

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<Tool>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    val temperature: Double? = null,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
)

/**
 * `content` may be either a JSON string (plain text message) or a JSON array of content parts
 * (multimodal — text + image_url). For text-only callers, use [textMessage] / [textOf].
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: JsonElement? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
) {
    /** Best-effort extraction of any human-readable text from this message. */
    val contentText: String?
        get() = when (val c = content) {
            null -> null
            is JsonPrimitive -> c.contentOrNull
            is JsonArray -> c.mapNotNull { part ->
                (part as? JsonObject)?.let { obj ->
                    (obj["text"] as? JsonPrimitive)?.contentOrNull
                }
            }.joinToString("\n").ifEmpty { null }
            else -> null
        }
}

/** Build a standard text message (role + plain string). */
fun textMessage(
    role: String,
    text: String,
    toolCallId: String? = null,
    name: String? = null,
): ChatMessage = ChatMessage(
    role = role,
    content = JsonPrimitive(text),
    toolCallId = toolCallId,
    name = name,
)

/**
 * Build a multimodal user message with text + a single image (data URL or remote URL). Use this
 * to attach a screenshot to the next turn so a vision-capable model actually "sees" the screen.
 */
fun userImageMessage(text: String, imageDataUrl: String, detail: String = "auto"): ChatMessage =
    ChatMessage(
        role = "user",
        content = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                },
            )
            add(
                buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") {
                        put("url", imageDataUrl)
                        put("detail", detail)
                    }
                },
            )
        },
    )

/** Wrap a JSON string content. */
fun textOf(text: String): JsonElement = JsonPrimitive(text)

@Serializable
data class Tool(
    val type: String = "function",
    val function: FunctionDef,
)

@Serializable
data class FunctionDef(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
)

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
data class ChatResponse(
    val id: String? = null,
    val choices: List<Choice>,
)

@Serializable
data class Choice(
    val index: Int = 0,
    val message: ChatMessage,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ApiErrorBody(
    val error: ApiError? = null,
)

@Serializable
data class ModelListResponse(
    val data: List<ModelInfo> = emptyList(),
)

@Serializable
data class ModelInfo(
    val id: String,
    @SerialName("owned_by") val ownedBy: String? = null,
    val created: Long? = null,
    @SerialName("active") val active: Boolean? = null,
    @SerialName("context_window") val contextWindow: Int? = null,
)

@Serializable
data class ApiError(
    val message: String? = null,
    val type: String? = null,
    val code: JsonElement? = null,
)
