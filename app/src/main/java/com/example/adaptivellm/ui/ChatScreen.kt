package com.example.adaptivellm.ui

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.outlined.Psychology
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.adaptivellm.R
import com.example.adaptivellm.inference.InferenceEngine
import com.example.adaptivellm.ui.theme.BodyFamily
import com.example.adaptivellm.ui.theme.okColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: MainViewModel) {
    val messages by viewModel.messages.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val isSwitchingChat by viewModel.isSwitchingChat.collectAsState()
    val messagesLoaded by viewModel.messagesLoaded.collectAsState()
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
                        val showDot = engineState is InferenceEngine.State.ModelLoaded ||
                                engineState is InferenceEngine.State.Generating
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (showDot) {
                                BreathingDot()
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                statusText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
            if (!messagesLoaded) {
                // Существующий чат подгружается из БД — спиннер вместо empty-state,
                // чтобы пользователь не подумал, что попал в новый пустой чат.
                // Используем УЗКИЙ флаг messagesLoaded (~ms после DB-запроса), а не
                // isSwitchingChat (включает медленные model load + KV restoration,
                // секунды — на эти стадии сообщения уже видны, но инпут заблокирован).
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (messages.isEmpty()) {
                EmptyChatHero(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onPromptClick = { prompt -> viewModel.sendMessage(prompt) },
                )
            } else {
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

                // Thinking-mode trigger (brain button) + dropdown
                var menuExpanded by remember { mutableStateOf(false) }
                Spacer(modifier = Modifier.width(6.dp))
                Box {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .shadow(2.dp, CircleShape, clip = false)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(enabled = !isGenerating) { menuExpanded = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Psychology,
                            contentDescription = stringResource(R.string.chat_thinking_header),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        // Header: brain icon + "Режим мышления"
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Psychology,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.chat_thinking_header).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        val thinkingLabels = listOf(
                            stringResource(R.string.chat_thinking_auto) to stringResource(R.string.chat_thinking_auto_hint),
                            stringResource(R.string.chat_thinking_always) to stringResource(R.string.chat_thinking_always_hint),
                            stringResource(R.string.chat_thinking_off) to stringResource(R.string.chat_thinking_off_hint),
                        )
                        thinkingLabels.forEachIndexed { index, (label, hint) ->
                            val selected = thinkingMode == index
                            DropdownMenuItem(
                                text = {
                                    Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (selected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = hint,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.setThinkingMode(index)
                                    menuExpanded = false
                                },
                                modifier = if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)) else Modifier,
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.SystemUpdateAlt,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        stringResource(R.string.chat_check_for_updates),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            },
                            onClick = {
                                viewModel.checkForUpdates()
                                menuExpanded = false
                            },
                        )
                    }
                }
                Spacer(modifier = Modifier.width(2.dp))

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
        Column(
            modifier = Modifier.fillMaxWidth(if (message.isUser) 0.78f else 0.88f),
            horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start,
        ) {
            var thinkingExpanded by remember { mutableStateOf(false) }
            val hasThinking = !message.isUser && message.thinkingText.isNotEmpty()
            if (hasThinking) {
                ThinkingChip(
                    expanded = thinkingExpanded,
                    stepCount = message.thinkingText.split("\n").count { it.isNotBlank() },
                    onClick = { thinkingExpanded = !thinkingExpanded },
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            Box(
                modifier = Modifier
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
                Column(modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = if (message.isUser) 10.dp else 12.dp)
                    .padding(end = 24.dp)
                ) {
                    if (hasThinking && thinkingExpanded) {
                        ThinkingQuoteBlock(message.thinkingText)
                        Spacer(modifier = Modifier.height(8.dp))
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
                            contentDescription = stringResource(R.string.chat_copy_message),
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
}

@Composable
private fun ThinkingChip(expanded: Boolean, stepCount: Int, onClick: () -> Unit) {
    val bg = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (expanded) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .then(
                if (expanded) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(start = 9.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
    ) {
        Icon(
            Icons.Outlined.Psychology,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.chat_thinking_steps, stepCount),
            style = MaterialTheme.typography.labelMedium,
            color = fg,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(14.dp).rotate(0f),
        )
    }
}

@Composable
private fun ThinkingQuoteBlock(thinking: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x2E000000))
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(topStart = 0.dp, topEnd = 8.dp, bottomEnd = 8.dp, bottomStart = 0.dp),
            )
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Text(
            text = thinking,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = BodyFamily,
                fontStyle = FontStyle.Italic,
            ),
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
        )
    }
}

@Composable
private fun EmptyChatHero(modifier: Modifier = Modifier, onPromptClick: (String) -> Unit) {
    val prompts = listOf(
        stringResource(R.string.empty_tag_code)  to stringResource(R.string.empty_prompt_code),
        stringResource(R.string.empty_tag_write) to stringResource(R.string.empty_prompt_write),
        stringResource(R.string.empty_tag_ideas) to stringResource(R.string.empty_prompt_ideas),
        stringResource(R.string.empty_tag_learn) to stringResource(R.string.empty_prompt_learn),
    )
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(modifier = Modifier.height(36.dp))
        // Hero block (centered)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.empty_hello),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.empty_question),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.empty_offline_private).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.empty_quick_start).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(10.dp))
        prompts.forEach { (tag, text) ->
            QuickPromptRow(tag = tag, text = text, onClick = { onPromptClick(text) })
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun QuickPromptRow(tag: String, text: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        // [TAG] chip
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp),
        ) {
            Text(
                text = tag,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "→",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun BreathingDot() {
    val transition = rememberInfiniteTransition(label = "chat-breathe")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "chat-breathe-scale",
    )
    Box(
        modifier = Modifier
            .size(6.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(okColor()),
    )
}
