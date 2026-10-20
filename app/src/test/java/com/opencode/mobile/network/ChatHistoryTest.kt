package com.opencode.mobile.network

import com.opencode.mobile.data.ChatMessage
import com.opencode.mobile.data.ChatToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryTest {
    private val client = ChatClient()

    @Test
    fun `retains complete tool calls and responses`() {
        val messages = listOf(
            ChatMessage(id = "1", role = ChatMessage.ROLE_USER, content = "Search", timestamp = 100),
            ChatMessage(
                id = "2",
                role = ChatMessage.ROLE_ASSISTANT,
                content = "",
                timestamp = 200,
                toolCalls = listOf(ChatToolCall("call_1", "web_search", "{\"query\":\"test\"}")),
            ),
            ChatMessage(
                id = "3",
                role = ChatMessage.ROLE_TOOL,
                content = "Result 1",
                timestamp = 300,
                toolCallId = "call_1",
            ),
        )

        val trimmed = client.trimmedHistory(messages)
        assertEquals(3, trimmed.size)
        assertEquals("1", trimmed[0].id)
        assertEquals("2", trimmed[1].id)
        assertEquals("3", trimmed[2].id)
    }

    @Test
    fun `purges orphan tool message when assistant message was dropped`() {
        val messages = listOf(
            ChatMessage(id = "1", role = ChatMessage.ROLE_USER, content = "Hello", timestamp = 100),
            ChatMessage(
                id = "orphan_tool",
                role = ChatMessage.ROLE_TOOL,
                content = "Some result",
                timestamp = 200,
                toolCallId = "call_missing",
            ),
            ChatMessage(id = "2", role = ChatMessage.ROLE_USER, content = "Next question", timestamp = 300),
        )

        val trimmed = client.trimmedHistory(messages)
        assertEquals(2, trimmed.size)
        assertEquals("1", trimmed[0].id)
        assertEquals("2", trimmed[1].id)
        assertTrue(trimmed.none { it.id == "orphan_tool" })
    }

    @Test
    fun `discards assistant message and tool result if one tool call is missing result`() {
        val messages = listOf(
            ChatMessage(id = "1", role = ChatMessage.ROLE_USER, content = "Run two tools", timestamp = 100),
            ChatMessage(
                id = "2",
                role = ChatMessage.ROLE_ASSISTANT,
                content = "",
                timestamp = 200,
                toolCalls = listOf(
                    ChatToolCall("call_1", "web_search", "{}"),
                    ChatToolCall("call_2", "shell", "{}"),
                ),
            ),
            ChatMessage(
                id = "3",
                role = ChatMessage.ROLE_TOOL,
                content = "Result 1",
                timestamp = 300,
                toolCallId = "call_1",
            ),
            // call_2 result is missing
        )

        val trimmed = client.trimmedHistory(messages)
        // Since call_2 is missing, assistant message 2 is invalid, and orphan tool message 3 must also be removed
        assertEquals(1, trimmed.size)
        assertEquals("1", trimmed[0].id)
    }

    @Test
    fun `never starts with tool message`() {
        val messages = listOf(
            ChatMessage(
                id = "t1",
                role = ChatMessage.ROLE_TOOL,
                content = "Result",
                timestamp = 100,
                toolCallId = "c1",
            ),
            ChatMessage(id = "u1", role = ChatMessage.ROLE_USER, content = "Hello", timestamp = 200),
        )

        val trimmed = client.trimmedHistory(messages)
        assertEquals(1, trimmed.size)
        assertEquals("u1", trimmed[0].id)
    }
}
