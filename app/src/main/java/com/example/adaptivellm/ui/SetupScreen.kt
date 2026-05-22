package com.example.adaptivellm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.adaptivellm.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.dp

@Composable
fun SetupScreen(viewModel: MainViewModel) {
    val selectedModel by viewModel.selectedModel.collectAsState()
    val profile = viewModel.deviceProfile
    val downloadedModels by viewModel.downloadedModels.collectAsState()
    val availableUpdate by viewModel.availableUpdate.collectAsState()

    // Pending model deletion — открывает confirmation dialog перед удалением.
    var pendingDelete by remember { mutableStateOf<com.example.adaptivellm.model.ModelVariant?>(null) }
    pendingDelete?.let { variant ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.setup_delete_model_title)) },
            text = {
                Text(stringResource(
                    R.string.setup_delete_model_msg,
                    variant.displayName,
                    variant.fileSizeMb.toInt(),
                ))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteModel(variant)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.common_delete),
                        color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // Update dialog
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

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )

            IconButton(onClick = { viewModel.openSettings() }) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = stringResource(R.string.common_settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Device info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.setup_device_profile),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(profile.toDisplayString(), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Recommended model
        Text(
            stringResource(R.string.setup_recommended, viewModel.recommendedModel.displayName),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Model selection
        Text(
            stringResource(R.string.setup_available_models),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (viewModel.compatibleModels.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.setup_not_supported_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.setup_not_supported_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        } else {
            viewModel.compatibleModels.forEach { (size, variants) ->
                Text(
                    stringResource(R.string.setup_param_size_header, size),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                variants.forEach { variant ->
                    val isDownloaded = downloadedModels.contains(variant.fileName)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectModel(variant) }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(
                            selected = variant == selectedModel,
                            onClick = { viewModel.selectModel(variant) },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(variant.displayName, style = MaterialTheme.typography.bodyMedium)
                                if (isDownloaded) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        stringResource(R.string.setup_downloaded),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Text(
                                stringResource(
                                    R.string.setup_model_specs,
                                    variant.fileSizeMb.toInt(),
                                    variant.nominalMinRamGb,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isDownloaded) {
                            IconButton(
                                onClick = { pendingDelete = variant },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.setup_delete_model),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            val isSelectedDownloaded = downloadedModels.contains(selectedModel.fileName)
            Button(
                onClick = { viewModel.startDownload() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(
                    if (isSelectedDownloaded) R.string.setup_start_chat
                    else R.string.setup_download_and_start
                ))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        val context = LocalContext.current
        val versionName = remember {
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
            } catch (_: Exception) { "" }
        }
        if (versionName.isNotEmpty()) {
            Text(
                text = "v$versionName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    }
}
