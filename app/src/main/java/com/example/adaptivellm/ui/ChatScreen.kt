package com.example.adaptivellm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import com.example.adaptivellm.R
import com.example.adaptivellm.inference.InferenceEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: MainViewModel) {
    val messages by viewModel.messages.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val isSwitchingChat by viewModel.isSwitchingChat.collectAsState()
    val isEvicting by viewModel.isEvicting.collectAsState()
    val evictionStatus by viewModel.evictionStatus.collectAsState()
    val evictionProgress by viewModel.evictionProgress.collectAsState()
    val tps by viewModel.tokensPerSecond.collectAsState()
    val backend by viewModel.backendInfo.collectAsState()
    val engineState by viewModel.engine.state.collectAsState()
    val loadedModelName by viewModel.loadedModelName.collectAsState()
    val thinkingMode by viewModel.thinkingMode.collectAsState()
    val totalTokens by viewModel.totalTokens.collectAsState()
    val availableUpdate by viewModel.availableUpdate.collectAsState()
    val updateCheckState by viewModel.updateCheckState.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val upToDateMessage = stringResource(R.string.update_app_up_to_date)

    // Show snackbar when update check completes with no update
    LaunchedEffect(updateCheckState) {
        if (updateCheckState == false) {
            snackbarHostState.showSnackbar(upToDateMessage)
        }
    }

    // Update dialog (can also appear in chat)
    val updateProgress by com.example.adaptivellm.update.ApkInstaller.progress.collectAsState()
    availableUpdate?.let { update ->
        AlertDialog(
            onDismissRequest = { if (updateProgress == null) viewModel.dismissUpdate() },
            title = {
                Text(
                    if (updateProgress != null) stringResource(R.string.update_in_progress)
                    else stringResource(R.string.update_available, update.versionName)
                )
            },
            text = {
                Column {
                    if (updateProgress != null) {
                        val pct = updateProgress!!
                        if (pct >= 0) {
                            Text(stringResource(R.string.update_downloading, pct))
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { pct / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text(stringResource(R.string.update_installing))
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    } else if (update.releaseNotes.isNotBlank()) {
                        Text(update.releaseNotes)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.installUpdate() },
                    enabled = updateProgress == null,
                ) {
                    Text(stringResource(R.string.update_button))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissUpdate() },
                    enabled = updateProgress == null,
                ) {
                    Text(stringResource(R.string.update_later))
                }
            },
        )
    }

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Scroll to bottom only when a new message is added
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size + 1)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = { viewModel.backToChatList() },
                        enabled = !isGenerating && !isEvicting,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.chat_back_to_list),
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            loadedModelName.ifEmpty { stringResource(R.string.common_loading) },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        val tokenInfo = if (totalTokens > 0) " · $totalTokens/${viewModel.nCtx}" else ""
                        val statusText = when {
                            isEvicting -> evictionStatus.ifBlank { stringResource(R.string.chat_evicting_default) }
                            isSwitchingChat -> stringResource(R.string.chat_loading)
                            engineState is InferenceEngine.State.LoadingModel -> stringResource(R.string.chat_loading_model)
                            engineState is InferenceEngine.State.Generating -> "%.1f t/s · %s%s".format(tps, backend, tokenInfo)
                            engineState is InferenceEngine.State.ModelLoaded -> stringResource(R.string.chat_ready_format, backend, tokenInfo)
                            engineState is InferenceEngine.State.Error -> stringResource(R.string.common_error)
                            else -> stringResource(R.string.common_initializing)
                        }
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (isEvicting) {
                            Spacer(modifier = Modifier.height(4.dp))
                            val p = evictionProgress
                            if (p != null) {
                                LinearProgressIndicator(
                                    progress = { p.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(3.dp),
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().height(3.dp),
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            // Messages
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Spacer(modifier = Modifier.height(8.dp)) }
                itemsIndexed(messages) { index, msg ->
                    MessageBubble(
                        message = msg,
                        isStreaming = isGenerating && index == messages.lastIndex && !msg.isUser,
                    )
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }

            // Input
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
                    enabled = engineState is InferenceEngine.State.ModelLoaded &&
                              !isGenerating && !isSwitchingChat && !isEvicting,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (inputText.isNotBlank()) {
                                viewModel.sendMessage(inputText)
                                inputText = ""
                            }
                        },
                    ),
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                )

                // Settings menu
                var menuExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        enabled = !isGenerating,
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.common_settings),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        // Section: Thinking
                        Text(
                            text = stringResource(R.string.chat_thinking_header),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        val thinkingLabels = listOf(
                            stringResource(R.string.chat_thinking_auto),
                            stringResource(R.string.chat_thinking_always),
                            stringResource(R.string.chat_thinking_off),
                        )
                        thinkingLabels.forEachIndexed { index, label ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = thinkingMode == index,
                                            onClick = null,
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(label)
                                    }
                                },
                                onClick = {
                                    viewModel.setThinkingMode(index)
                                    menuExpanded = false
                                },
                            )
                        }
                        // Update check
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_check_for_updates)) },
                            onClick = {
                                viewModel.checkForUpdates()
                                menuExpanded = false
                            },
                        )
                        // Future: RAG, MCP sections go here
                    }
                }

                // Send / Stop button
                if (isGenerating) {
                    IconButton(onClick = { viewModel.stopGeneration() }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.chat_stop_generation),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                viewModel.sendMessage(inputText)
                                inputText = ""
                            }
                        },
                        enabled = inputText.isNotBlank() &&
                                engineState is InferenceEngine.State.ModelLoaded &&
                                !isSwitchingChat && !isEvicting,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.chat_send),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("DEPRECATION") // LocalClipboard миграция на suspend API — отдельная задача
private fun MessageBubble(message: ChatMessage, isStreaming: Boolean = false) {
    val clipboardManager = LocalClipboardManager.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (message.isUser) 16.dp else 4.dp,
                        bottomEnd = if (message.isUser) 4.dp else 16.dp,
                    )
                )
                .background(
                    if (message.isUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.secondaryContainer,
                ),
        ) {
            Column(modifier = Modifier.padding(12.dp).padding(end = 24.dp)) {
                // Collapsible thinking block
                if (!message.isUser && message.thinkingText.isNotEmpty()) {
                    var expanded by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded }
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (expanded) "Hide thinking" else "Show thinking",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                        )
                    }
                    if (expanded) {
                        MarkdownText(
                            text = message.thinkingText,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }

                if (message.isUser) {
                    Text(
                        text = message.text,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    MarkdownContent(
                        text = message.text,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        isGenerating = isStreaming,
                    )
                }
            }

            // Copy-all button (top-right, semi-transparent)
            if (message.text.isNotEmpty()) {
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(message.text))
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(32.dp),
                ) {
                    Icon(
                        CopyIcon,
                        contentDescription = "Copy message",
                        tint = if (message.isUser)
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.4f)
                        else
                            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.4f),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}
