package com.opencode.mobile.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.mobile.data.ConversationStore
import com.opencode.mobile.data.ConversationSummary
import com.opencode.mobile.data.SettingsStore
import com.opencode.mobile.data.ModelCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatListUiState(
    val conversations: List<ConversationSummary> = emptyList(),
    val hasApiKey: Boolean = false,
    val loading: Boolean = true,
    val searchQuery: String = "",
    val totalCount: Int = 0,
)

class ChatListViewModel(
    private val conversationStore: ConversationStore,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val hasApiKey = MutableStateFlow(false)
    private val loading = MutableStateFlow(true)
    private val searchQuery = MutableStateFlow("")

    val state: StateFlow<ChatListUiState> = combine(
        conversationStore.summaries,
        hasApiKey,
        loading,
        searchQuery,
    ) { conversations, key, isLoading, query ->
        val trimmed = query.trim()
        val filtered = if (trimmed.isEmpty()) {
            conversations
        } else {
            conversations.filter {
                it.title.contains(trimmed, ignoreCase = true) ||
                    it.preview.contains(trimmed, ignoreCase = true) ||
                    ModelCatalog.nameFor(it.modelId).contains(trimmed, ignoreCase = true) ||
                    it.modelId.contains(trimmed, ignoreCase = true)
            }
        }
        ChatListUiState(
            conversations = filtered,
            hasApiKey = key,
            loading = isLoading,
            searchQuery = query,
            totalCount = conversations.size,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatListUiState(),
    )

    init {
        refresh()
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun refresh() {
        viewModelScope.launch {
            hasApiKey.value = settingsStore.load().hasApiKey
            conversationStore.refresh()
            loading.value = false
        }
    }

    fun rename(conversationId: String, newTitle: String) {
        viewModelScope.launch { conversationStore.rename(conversationId, newTitle) }
    }

    fun fork(conversationId: String) {
        viewModelScope.launch { conversationStore.fork(conversationId) }
    }

    fun delete(conversationId: String) {
        viewModelScope.launch { conversationStore.delete(conversationId) }
    }

    suspend fun getMarkdown(conversationId: String): String? {
        val conversation = conversationStore.load(conversationId) ?: return null
        return conversationStore.exportToMarkdown(conversation)
    }
}
