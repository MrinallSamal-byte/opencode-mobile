package com.opencode.mobile.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class ConversationStore(context: Context) {
    private val directory = File(context.filesDir, "conversations").apply { mkdirs() }
    private val attachmentsDirectory = File(context.filesDir, "attachments")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val writeMutex = Mutex()
    private val _summaries = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val summaries: StateFlow<List<ConversationSummary>> = _summaries.asStateFlow()

    fun newConversation(modelId: String, thinkingEffort: ThinkingEffort): Conversation {
        val now = System.currentTimeMillis()
        return Conversation(
            id = UUID.randomUUID().toString(),
            modelId = modelId,
            createdAt = now,
            updatedAt = now,
            thinkingEffort = thinkingEffort.name,
        )
    }

    suspend fun load(id: String): Conversation? = withContext(Dispatchers.IO) {
        val file = fileFor(id)
        if (!file.exists()) return@withContext null
        runCatching {
            val conversation = json.decodeFromString<Conversation>(file.readText())
            conversation.copy(
                thinkingEffort = ModelCatalog.resolveEffort(
                    conversation.modelId,
                    ThinkingEffort.from(conversation.thinkingEffort),
                ).name,
                messages = conversation.messages.filterNot { message ->
                    message.role == ChatMessage.ROLE_ASSISTANT &&
                        message.content.isBlank() &&
                        message.reasoning.isBlank() &&
                        message.error == null &&
                        message.toolCalls.isEmpty()
                },
            )
        }.getOrNull()
    }

    suspend fun save(conversation: Conversation): Conversation {
        val updated = conversation.copy(updatedAt = System.currentTimeMillis())
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val target = fileFor(updated.id)
                val temp = File(directory, "${updated.id}.tmp")
                temp.writeText(json.encodeToString(updated))
                if (!temp.renameTo(target)) {
                    target.writeText(temp.readText())
                    temp.delete()
                }
            }
        }
        val summary = updated.toSummary()
        _summaries.update { current ->
            val mutable = current.filterNot { it.id == summary.id }.toMutableList()
            mutable.add(0, summary)
            mutable.sortedByDescending { it.updatedAt }
        }
        return updated
    }

    suspend fun delete(id: String) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                fileFor(id).delete()
                File(attachmentsDirectory, id).deleteRecursively()
            }
        }
        _summaries.update { current -> current.filterNot { it.id == id } }
    }

    suspend fun rename(id: String, newTitle: String): Conversation? {
        val trimmed = newTitle.trim().take(100)
        if (trimmed.isEmpty()) return null
        val loaded = load(id) ?: return null
        if (loaded.title == trimmed) return loaded
        val updated = loaded.copy(title = trimmed)
        return save(updated)
    }

    suspend fun fork(id: String): Conversation? {
        val original = load(id) ?: return null
        val now = System.currentTimeMillis()
        val forkedId = UUID.randomUUID().toString()
        withContext(Dispatchers.IO) {
            val originalAttachDir = File(attachmentsDirectory, id)
            if (originalAttachDir.exists()) {
                val targetAttachDir = File(attachmentsDirectory, forkedId)
                targetAttachDir.mkdirs()
                originalAttachDir.copyRecursively(targetAttachDir, overwrite = true)
            }
        }
        val updatedMessages = original.messages.map { msg ->
            if (msg.attachments.isEmpty()) {
                msg
            } else {
                msg.copy(
                    attachments = msg.attachments.map { att ->
                        val fileName = File(att.localPath).name
                        val targetFile = File(File(attachmentsDirectory, forkedId), fileName)
                        att.copy(localPath = targetFile.absolutePath)
                    },
                )
            }
        }
        val forked = original.copy(
            id = forkedId,
            title = "${original.title} (Fork)",
            createdAt = now,
            updatedAt = now,
            messages = updatedMessages,
        )
        return save(forked)
    }

    fun exportToMarkdown(conversation: Conversation): String = buildString {
        appendLine("# ${conversation.title}")
        appendLine("**Model:** ${ModelCatalog.nameFor(conversation.modelId)} (${conversation.modelId})")
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(conversation.createdAt))
        appendLine("**Date:** $dateStr")
        appendLine()
        appendLine("---")
        appendLine()
        for (message in conversation.messages) {
            when {
                message.isUser -> {
                    appendLine("### User")
                    if (message.attachments.isNotEmpty()) {
                        appendLine("*(Attached: ${message.attachments.joinToString { it.fileName }})*")
                        appendLine()
                    }
                    if (message.content.isNotBlank()) {
                        appendLine(message.content)
                        appendLine()
                    }
                }
                message.isTool -> {
                    val toolName = message.toolName ?: "tool"
                    appendLine("<details>")
                    appendLine("<summary>Tool ($toolName): ${message.toolDetail.orEmpty()}</summary>")
                    appendLine()
                    appendLine("```")
                    appendLine(message.content)
                    appendLine("```")
                    appendLine("</details>")
                    appendLine()
                }
                message.role == ChatMessage.ROLE_ASSISTANT -> {
                    val modelName = message.model?.let { ModelCatalog.nameFor(it) }
                        ?: ModelCatalog.nameFor(conversation.modelId)
                    appendLine("### Assistant ($modelName)")
                    if (message.reasoning.isNotBlank()) {
                        appendLine("<details>")
                        appendLine("<summary>Thinking Process</summary>")
                        appendLine()
                        appendLine(message.reasoning)
                        appendLine("</details>")
                        appendLine()
                    }
                    if (message.content.isNotBlank()) {
                        appendLine(message.content)
                        appendLine()
                    }
                    if (message.error != null) {
                        appendLine("> **Error:** ${message.error}")
                        appendLine()
                    }
                }
            }
        }
    }

    suspend fun refresh() {
        val loaded = withContext(Dispatchers.IO) {
            directory.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".json") }
                ?.mapNotNull { file ->
                    runCatching { json.decodeFromString<Conversation>(file.readText()) }.getOrNull()
                }
                ?.map { it.toSummary() }
                ?.sortedByDescending { it.updatedAt }
                .orEmpty()
        }
        _summaries.value = loaded
    }

    private fun fileFor(id: String): File = File(directory, "$id.json")

    private fun Conversation.toSummary(): ConversationSummary {
        val last = messages.lastOrNull {
            it.isTool || it.content.isNotBlank() || it.reasoning.isNotBlank() || it.error != null
        }
        val rawPreview = when {
            last == null -> "No messages yet"
            last.isTool -> when (last.toolName) {
                "web_search" -> "Web search: ${last.toolDetail.orEmpty()}"
                "shell" -> "Shell: ${last.toolDetail.orEmpty()}"
                else -> "Tool result"
            }
            last.error != null -> "Request failed"
            last.isUser -> last.content.ifBlank { "Attachment" }
            last.content.isNotBlank() -> last.content
            else -> last.reasoning
        }
        return ConversationSummary(
            id = id,
            title = title,
            modelId = modelId,
            updatedAt = updatedAt,
            preview = plainPreview(rawPreview),
            messageCount = messages.size,
        )
    }
}

private val codeFence = Regex("```[^\\n]*\\n?")
private val link = Regex("\\[([^\\]]*)]\\([^)]*\\)")
private val emphasis = Regex("(\\*\\*|__|\\*|~~|`)(?=\\S)(.+?)(?<=\\S)\\1")
private val linePrefix = Regex("(?m)^\\s{0,3}(#{1,6}\\s+|>\\s?|[-*+]\\s+|\\d+\\.\\s+|\\|)")

/** Collapses Markdown to a single line of plain text for the chat list. */
internal fun plainPreview(text: String): String = text
    .replace(codeFence, "")
    .replace(link, "$1")
    .replace(linePrefix, "")
    .replace(emphasis, "$2")
    .replace("|", " ")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(90)
