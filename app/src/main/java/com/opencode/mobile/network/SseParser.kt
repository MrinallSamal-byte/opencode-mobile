package com.opencode.mobile.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class ToolCallDelta(
    val index: Int,
    val id: String? = null,
    val name: String? = null,
    val arguments: String? = null,
)

data class SseParseResult(
    val content: String? = null,
    val reasoning: String? = null,
    val toolCalls: List<ToolCallDelta> = emptyList(),
    val finished: Boolean = false,
    val error: String? = null,
)

object SseParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(data: String): SseParseResult? {
        val payload = data.trim()
        if (payload.isEmpty()) return null
        if (payload == "[DONE]") return SseParseResult(finished = true)

        val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null

        // 1. Check for error object or string
        val errorElement = root["error"]
        if (errorElement != null) {
            val errorMessage = when (errorElement) {
                is JsonObject -> (errorElement["message"] as? JsonPrimitive)?.contentOrNull
                    ?: (errorElement["msg"] as? JsonPrimitive)?.contentOrNull
                    ?: (errorElement["detail"] as? JsonPrimitive)?.contentOrNull
                    ?: errorElement.toString()
                is JsonPrimitive -> errorElement.contentOrNull
                else -> errorElement.toString()
            }
            return SseParseResult(error = errorMessage?.ifBlank { null } ?: "The model returned an error.")
        }

        // Check for root-level error detail
        val rootDetail = (root["detail"] as? JsonPrimitive)?.contentOrNull
        if (!rootDetail.isNullOrBlank()) {
            return SseParseResult(error = rootDetail)
        }

        val choices = root["choices"] as? JsonArray ?: return null
        val choice = choices.firstOrNull() as? JsonObject ?: return null
        val delta = (choice["delta"] as? JsonObject) ?: (choice["message"] as? JsonObject)
        val content = (delta?.get("content") as? JsonPrimitive)?.contentOrNull
        val reasoning = (delta?.get("reasoning_content") as? JsonPrimitive)?.contentOrNull
            ?: (delta?.get("reasoning") as? JsonPrimitive)?.contentOrNull
            ?: (delta?.get("thought") as? JsonPrimitive)?.contentOrNull
        val toolCalls = (delta?.get("tool_calls") as? JsonArray)?.mapIndexedNotNull { fallbackIndex, element ->
            val call = element as? JsonObject ?: return@mapIndexedNotNull null
            val function = call["function"] as? JsonObject
            ToolCallDelta(
                index = (call["index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: fallbackIndex,
                id = (call["id"] as? JsonPrimitive)?.contentOrNull,
                name = (function?.get("name") as? JsonPrimitive)?.contentOrNull,
                arguments = (function?.get("arguments") as? JsonPrimitive)?.contentOrNull,
            )
        }.orEmpty()
        val finished = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull != null

        if (content.isNullOrEmpty() && reasoning.isNullOrEmpty() && toolCalls.isEmpty() && !finished) {
            return null
        }
        return SseParseResult(
            content = content?.takeIf { it.isNotEmpty() },
            reasoning = reasoning?.takeIf { it.isNotEmpty() },
            toolCalls = toolCalls,
            finished = finished,
        )
    }
}
