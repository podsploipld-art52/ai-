package com.aiagent.android.agent

/**
 * Pure helpers for figuring out what a given model id can/can't do. Shared between
 * the Agent (runtime decisions) and the UI (warnings, auto-toggling vision based on
 * model choice).
 *
 * The naming heuristics are intentionally generous — most providers stick to
 * recognisable substrings (`vision`, `gpt-4o`, `llama-4-scout`, `claude-3`, …) so the
 * detection works well across Groq / OpenAI / Anthropic / Google / OpenRouter / local
 * llama.cpp setups without an explicit allowlist.
 */
object ModelCapabilities {

    /**
     * Best-effort detection of whether the model can ingest images via the OpenAI-style
     * `image_url` content part. Covers the Groq llama-3.2-vision, llama-4 Scout/Maverick
     * lineup, OpenAI gpt-4o / gpt-4-turbo / gpt-4-vision / gpt-5, Anthropic claude-3+,
     * Mistral Pixtral, and Gemini families.
     */
    fun supportsVision(modelId: String): Boolean {
        val id = modelId.lowercase()
        return id.contains("vision") ||
            id.contains("llama-4") ||
            id.contains("llama4") ||
            id.contains("scout") ||
            id.contains("maverick") ||
            id.contains("gpt-4o") ||
            id.contains("gpt-4-turbo") ||
            id.contains("gpt-4-vision") ||
            id.contains("gpt-5") ||
            id.contains("claude-3") ||
            id.contains("claude-4") ||
            id.contains("claude-sonnet") ||
            id.contains("claude-opus") ||
            id.contains("claude-haiku") ||
            id.contains("gemini") ||
            id.contains("pixtral")
    }

    /**
     * Whether the model accepts the OpenAI-style `reasoning_effort` parameter. Sending it
     * to anything else returns HTTP 400 from most providers.
     *
     * Sources: https://console.groq.com/docs/reasoning (Groq supports it on gpt-oss-* and
     * qwen3-32b) and the OpenAI reasoning models (o1 / o3 / o4 / gpt-5 with reasoning).
     */
    fun supportsReasoningEffort(modelId: String): Boolean {
        val id = modelId.lowercase()
        return id.contains("gpt-oss") ||
            id.contains("qwen3") ||
            id.matches(Regex("^o\\d.*")) ||
            id.startsWith("o1") ||
            id.startsWith("o3") ||
            id.startsWith("o4") ||
            id.contains("gpt-5")
    }
}
