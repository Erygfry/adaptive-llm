package com.example.adaptivellm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.adaptivellm.inference.InferenceEngine
import com.example.adaptivellm.storage.ChatRepository
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

    // Confirmation dialog для удаления
    var pendingDelete by remember { mutableStateOf<ChatRepository.ChatInfo?>(null) }
    pendingDelete?.let { chat ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить чат?") },
            text = {
                Text(
                    "«${chat.title ?: "Без названия"}» — все сообщения этого чата " +
                    "будут удалены безвозвратно."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteChat(chat.id)
                        pendingDelete = null
                    }
                ) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Отмена")
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
                            contentDescription = "Назад к выбору модели",
                        )
                    }
                },
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Чаты", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.width(8.dp))
                            val atLimit = chats.size >= MainViewModel.MAX_CHATS
                            Text(
                                "${chats.size}/${MainViewModel.MAX_CHATS}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (atLimit)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val status = when (engineState) {
                            is InferenceEngine.State.LoadingModel -> "Загрузка модели..."
                            is InferenceEngine.State.ModelLoaded -> loadedModelName.ifBlank { "Готов" }
                            is InferenceEngine.State.Error -> "Ошибка"
                            else -> "Инициализация..."
                        }
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    // Stage 7 — Память: просмотр всех сохранённых фактов
                    IconButton(onClick = { viewModel.openFactsMemory() }) {
                        Icon(
                            MemoryLibraryIcon,
                            contentDescription = "Память",
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
                containerColor = if (canCreate)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = if (atLimit)
                        "Достигнут лимит чатов (${MainViewModel.MAX_CHATS})"
                    else
                        "Новый чат",
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
                        "У вас пока нет чатов",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Нажмите + чтобы начать первый разговор",
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(chats, key = { it.id }) { chat ->
                    ChatListItem(
                        chat = chat,
                        onClick = { viewModel.selectChat(chat.id) },
                        onDeleteClick = { pendingDelete = chat },
                        enabled = !isSwitchingChat,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatListItem(
    chat: ChatRepository.ChatInfo,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (enabled) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chat.title?.ifBlank { null } ?: "Без названия",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = formatRelativeTime(chat.lastActiveAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        IconButton(
            onClick = onDeleteClick,
            enabled = enabled,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Удалить чат",
                tint = MaterialTheme.colorScheme.error.copy(alpha = if (enabled) 0.7f else 0.3f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Простое форматирование "last active": если меньше минуты — "только что",
 * меньше часа — "N мин назад", сегодня — "HH:mm", иначе — "dd.MM.yyyy".
 *
 * Для MVP — простая логика; на Stage 9 polish можно прикрутить локализацию и
 * множественные формы (1 минута, 2 минуты, 5 минут и т.д.).
 */
private fun formatRelativeTime(unixSec: Long): String {
    val now = System.currentTimeMillis() / 1000L
    val diff = now - unixSec
    return when {
        diff < 60 -> "только что"
        diff < 3600 -> "${diff / 60} мин назад"
        diff < 86400 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(unixSec * 1000L))
        else -> SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(unixSec * 1000L))
    }
}
