package com.example.adaptivellm.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.example.adaptivellm.R
import com.example.adaptivellm.inference.InferenceEngine
import com.example.adaptivellm.storage.ChatRepository
import com.example.adaptivellm.ui.theme.okColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(viewModel: MainViewModel) {
    val chats by viewModel.chats.collectAsState()
    val isSwitchingChat by viewModel.isSwitchingChat.collectAsState()
    val engineState by viewModel.engine.state.collectAsState()
    val loadedModelName by viewModel.loadedModelName.collectAsState()

    // Pending rename — открывает диалог с TextField для нового названия.
    var pendingRename by remember { mutableStateOf<ChatRepository.ChatInfo?>(null) }
    pendingRename?.let { chat ->
        val initial = chat.title?.ifBlank { null } ?: stringResource(R.string.common_untitled)
        var newTitle by remember(chat.id) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text(stringResource(R.string.chatlist_rename_chat)) },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text(stringResource(R.string.chatlist_rename_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameChat(chat.id, newTitle)
                    pendingRename = null
                }) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // Confirmation dialog для удаления
    var pendingDelete by remember { mutableStateOf<ChatRepository.ChatInfo?>(null) }
    pendingDelete?.let { chat ->
        val title = chat.title?.ifBlank { null } ?: stringResource(R.string.common_untitled)
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.chatlist_delete_title)) },
            text = { Text(stringResource(R.string.chatlist_delete_msg, title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteChat(chat.id)
                        pendingDelete = null
                    }
                ) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { viewModel.goBackToSetup() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.chatlist_back_to_setup),
                        )
                    }
                },
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.chatlist_title),
                                style = MaterialTheme.typography.headlineMedium,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            val atLimit = chats.size >= MainViewModel.MAX_CHATS
                            Text(
                                "${chats.size} / ${MainViewModel.MAX_CHATS}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (atLimit)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val statusWord = when (engineState) {
                            is InferenceEngine.State.LoadingModel -> stringResource(R.string.chat_loading_model)
                            is InferenceEngine.State.ModelLoaded -> stringResource(R.string.common_ready)
                            is InferenceEngine.State.Error -> stringResource(R.string.common_error)
                            else -> stringResource(R.string.common_initializing)
                        }
                        val modelTag = loadedModelName.ifBlank { "—" }.lowercase()
                        Text(
                            "$modelTag · $statusWord",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    // Stage 7 — Память: просмотр всех сохранённых фактов
                    IconButton(onClick = { viewModel.openFactsMemory() }) {
                        Icon(
                            MemoryLibraryIcon,
                            contentDescription = stringResource(R.string.chatlist_memory),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Stage 7 — Настройки
                    IconButton(onClick = { viewModel.openSettings() }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.common_settings),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        },
        floatingActionButton = {
            // Lazy load: FAB активен даже когда engine не loaded — клик триггерит
            // загрузку модели в selectChatInternal. Блокировка если:
            //   - уже идёт chat switching (чтобы не запустить второй параллельный)
            //   - достигнут лимит MAX_CHATS (см. MainViewModel — пользователь должен
            //     удалить старый чат прежде чем создать новый, иначе disk usage
            //     уходит в космос когда KV cache persistence заработает)
            val atLimit = chats.size >= MainViewModel.MAX_CHATS
            val canCreate = !isSwitchingChat && !atLimit
            FloatingActionButton(
                onClick = { if (canCreate) viewModel.createNewChat() },
                modifier = Modifier
                    .padding(end = 4.dp, bottom = 16.dp)
                    .size(56.dp)
                    .shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(18.dp),
                        clip = false,
                        ambientColor = MaterialTheme.colorScheme.primary,
                        spotColor = MaterialTheme.colorScheme.primary,
                    ),
                shape = RoundedCornerShape(18.dp),
                containerColor = if (canCreate)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = if (atLimit)
                        stringResource(R.string.chatlist_limit_reached, MainViewModel.MAX_CHATS)
                    else
                        stringResource(R.string.chatlist_new_chat),
                    tint = if (canCreate)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        },
    ) { padding ->
        if (chats.isEmpty()) {
            // Empty state — guide пользователя к созданию первого чата
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.chatlist_empty_title),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.chatlist_empty_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp, end = 12.dp, top = 8.dp, bottom = 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(chats, key = { it.id }) { chat ->
                    ChatListItem(
                        chat = chat,
                        onClick = { viewModel.selectChat(chat.id) },
                        onLongClick = { pendingRename = chat },
                        onRenameClick = { pendingRename = chat },
                        onDeleteClick = { pendingDelete = chat },
                        enabled = !isSwitchingChat,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatListItem(
    chat: ChatRepository.ChatInfo,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
    enabled: Boolean,
) {
    val nowSec = System.currentTimeMillis() / 1000L
    val isRecent = (nowSec - chat.lastActiveAt) < 60
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (enabled) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chat.title?.ifBlank { null } ?: stringResource(R.string.common_untitled),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isRecent) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(okColor()),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = formatRelativeTime(chat.lastActiveAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
        IconButton(
            onClick = onRenameClick,
            enabled = enabled,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.Default.Create,
                contentDescription = stringResource(R.string.chatlist_rename_chat),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.7f else 0.3f),
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(
            onClick = onDeleteClick,
            enabled = enabled,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.chatlist_delete_chat),
                tint = MaterialTheme.colorScheme.error.copy(alpha = if (enabled) 0.7f else 0.3f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Локализованное форматирование "last active" через Android plurals.
 * @Composable нужен для `pluralStringResource`.
 */
@Composable
private fun formatRelativeTime(unixSec: Long): String {
    val now = System.currentTimeMillis() / 1000L
    val diff = now - unixSec
    return when {
        diff < 60 -> stringResource(R.string.time_just_now)
        diff < 3600 -> {
            val n = (diff / 60).toInt()
            pluralStringResource(R.plurals.time_minutes_ago, n, n)
        }
        diff < 86400 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(unixSec * 1000L))
        else -> SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(unixSec * 1000L))
    }
}
