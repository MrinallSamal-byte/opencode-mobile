package com.opencode.mobile.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.opencode.mobile.AppContainer
import com.opencode.mobile.data.AppSettings
import com.opencode.mobile.vm.AppViewModelFactory
import com.opencode.mobile.vm.ChatListViewModel
import com.opencode.mobile.vm.ChatViewModel
import com.opencode.mobile.vm.SettingsViewModel
import com.opencode.mobile.vm.UsageViewModel
import java.util.UUID

sealed interface Screen {
    val key: String

    data object Chats : Screen {
        override val key = "chats"
    }

    data class Chat(val conversationId: String?, override val key: String) : Screen

    data object Settings : Screen {
        override val key = "settings"
    }

    data object Usage : Screen {
        override val key = "usage"
    }
}

@Composable
fun OpenCodeRoot(
    container: AppContainer,
    hasApiKey: Boolean,
    onSettingsSaved: (AppSettings) -> Unit,
) {
    val baseFactory = remember(container) { AppViewModelFactory(container) }
    val listViewModel: ChatListViewModel = viewModel(factory = baseFactory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = baseFactory)
    val stack = rememberSaveable(
        saver = listSaver(
            save = { list ->
                list.map { screen ->
                    when (screen) {
                        is Screen.Chats -> "chats"
                        is Screen.Settings -> "settings"
                        is Screen.Usage -> "usage"
                        is Screen.Chat -> "chat:${screen.conversationId.orEmpty()}:${screen.key}"
                    }
                }
            },
            restore = { restored ->
                val list = restored.mapNotNull { item ->
                    when {
                        item == "chats" -> Screen.Chats
                        item == "settings" -> Screen.Settings
                        item == "usage" -> Screen.Usage
                        item.startsWith("chat:") -> {
                            val parts = item.split(":", limit = 3)
                            val convId = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
                            val key = parts.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: convId ?: UUID.randomUUID().toString()
                            Screen.Chat(conversationId = convId, key = key)
                        }
                        else -> null
                    }
                }
                mutableStateListOf<Screen>().apply {
                    if (list.isEmpty()) add(Screen.Chats) else addAll(list)
                }
            },
        ),
    ) {
        mutableStateListOf<Screen>(Screen.Chats)
    }
    val current = stack.lastOrNull() ?: Screen.Chats
    val listState by listViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun push(screen: Screen) {
        if (stack.lastOrNull() != screen) stack.add(screen)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    LaunchedEffect(current) {
        if (current is Screen.Chats) listViewModel.refresh()
    }

    when (val screen = current) {
        is Screen.Chats -> ChatListScreen(
            state = listState,
            onNewChat = { push(Screen.Chat(conversationId = null, key = UUID.randomUUID().toString())) },
            onOpenChat = { id -> push(Screen.Chat(conversationId = id, key = id)) },
            onDeleteChat = listViewModel::delete,
            onRenameChat = listViewModel::rename,
            onForkChat = { id ->
                listViewModel.fork(id)
            },
            onShareChat = { id ->
                scope.launch {
                    val markdown = listViewModel.getMarkdown(id) ?: return@launch
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, markdown)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share conversation"))
                }
            },
            onSearchQueryChange = listViewModel::setSearchQuery,
            onOpenUsage = { push(Screen.Usage) },
            onOpenSettings = { push(Screen.Settings) },
        )

        is Screen.Chat -> {
            val factory = remember(screen.key) {
                AppViewModelFactory(container, conversationId = screen.conversationId)
            }
            val chatViewModel: ChatViewModel = viewModel(
                key = "chat-${screen.key}",
                factory = factory,
            )
            ChatScreen(
                viewModel = chatViewModel,
                hasApiKey = hasApiKey,
                onBack = ::pop,
                onOpenUsage = { push(Screen.Usage) },
                onOpenSettings = { push(Screen.Settings) },
                onDeleted = {
                    pop()
                    listViewModel.refresh()
                },
                onForked = { forkedId ->
                    push(Screen.Chat(conversationId = forkedId, key = forkedId))
                },
            )
        }

        is Screen.Usage -> {
            val usageViewModel: UsageViewModel = viewModel(factory = baseFactory)
            UsageScreen(
                viewModel = usageViewModel,
                onBack = ::pop,
                onOpenSettings = { push(Screen.Settings) },
            )
        }

        is Screen.Settings -> SettingsScreen(
            viewModel = settingsViewModel,
            onBack = ::pop,
            onSaved = { settings ->
                onSettingsSaved(settings)
                pop()
            },
        )
    }
}
