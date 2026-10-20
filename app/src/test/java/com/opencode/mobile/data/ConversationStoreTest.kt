package com.opencode.mobile.data

import android.content.ContextWrapper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConversationStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createStore(): ConversationStore {
        val root = tempFolder.newFolder("files")
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = root
        }
        return ConversationStore(context)
    }

    @Test
    fun testExportToMarkdown() {
        val store = createStore()
        val conv = Conversation(
            id = "test-1",
            title = "Test Markdown Export",
            modelId = "glm-5.3",
            createdAt = 1726000000000L,
            updatedAt = 1726000000000L,
            messages = listOf(
                ChatMessage(
                    id = "msg-1",
                    role = ChatMessage.ROLE_USER,
                    content = "How do I sort a list in Kotlin?",
                    timestamp = 1726000000000L,
                ),
                ChatMessage(
                    id = "msg-2",
                    role = ChatMessage.ROLE_ASSISTANT,
                    content = "Use `sorted()` or `sort()`.",
                    reasoning = "Thinking about Kotlin collection sorting methods.",
                    timestamp = 1726000005000L,
                    durationMs = 1200L,
                    model = "glm-5.3",
                ),
                ChatMessage(
                    id = "msg-3",
                    role = ChatMessage.ROLE_TOOL,
                    content = "Sample tool result",
                    toolName = "web_search",
                    toolDetail = "Kotlin collections sort",
                    timestamp = 1726000002000L,
                ),
            ),
        )

        val markdown = store.exportToMarkdown(conv)
        assertTrue(markdown.contains("# Test Markdown Export"))
        assertTrue(markdown.contains("GLM-5.3"))
        assertTrue(markdown.contains("How do I sort a list in Kotlin?"))
        assertTrue(markdown.contains("Use `sorted()` or `sort()`."))
        assertTrue(markdown.contains("Thinking about Kotlin collection sorting methods."))
        assertTrue(markdown.contains("Tool (web_search)"))
    }

    @Test
    fun testRenameConversation() = runTest {
        val store = createStore()
        val original = store.newConversation("glm-5.3", ThinkingEffort.AUTO)
        store.save(original)

        val renamed = store.rename(original.id, "Renamed Project")
        assertNotNull(renamed)
        assertEquals("Renamed Project", renamed?.title)

        // Loading from disk must also reflect renamed title
        val loaded = store.load(original.id)
        assertEquals("Renamed Project", loaded?.title)

        // Renaming to blank should return null and not modify
        val blankResult = store.rename(original.id, "   ")
        assertNull(blankResult)
    }

    @Test
    fun testForkConversation() = runTest {
        val store = createStore()
        val original = store.newConversation("glm-5.3", ThinkingEffort.AUTO).copy(
            title = "Original Chat",
            messages = listOf(
                ChatMessage(
                    id = "m1",
                    role = ChatMessage.ROLE_USER,
                    content = "Hello fork",
                    timestamp = 1000L,
                ),
            ),
        )
        store.save(original)

        val forked = store.fork(original.id)
        assertNotNull(forked)
        assertTrue(forked!!.id != original.id)
        assertEquals("Original Chat (Fork)", forked.title)
        assertEquals(1, forked.messages.size)
        assertEquals("Hello fork", forked.messages[0].content)

        // Ensure both original and fork exist in summaries
        assertEquals(2, store.summaries.value.size)
    }
}
