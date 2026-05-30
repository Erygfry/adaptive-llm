package com.example.adaptivellm.ui

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.adaptivellm.R
import com.example.adaptivellm.model.DownloadState
import com.example.adaptivellm.model.ModelVariant
import com.example.adaptivellm.ui.theme.MonoFamily
import com.example.adaptivellm.ui.theme.okColor

@Composable
fun SetupScreen(viewModel: MainViewModel) {
    val selectedModel by viewModel.selectedModel.collectAsState()
    val profile = viewModel.deviceProfile
    val downloadedModels by viewModel.downloadedModels.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    val availableUpdate by viewModel.availableUpdate.collectAsState()

    // Подтверждение удаления модели (v1.1.3) — anchor — IconButton корзины в ModelCard.
    var pendingDelete by remember { mutableStateOf<ModelVariant?>(null) }
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
                    Text(
                        stringResource(R.string.common_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // Update dialog (kept from original — unchanged behavior)
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
                ) { Text(stringResource(R.string.update_button)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissUpdate() },
                    enabled = updateProgress == null,
                ) { Text(stringResource(R.string.update_later)) }
            },
        )
    }

    val context = LocalContext.current
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 12.dp, bottom = 100.dp),
            ) {
                AppIdentityRow(versionName = versionName, onSettings = { viewModel.openSettings() })

                Spacer(Modifier.height(18.dp))
                StatusPill(label = "READY · NO NETWORK")

                Spacer(Modifier.height(24.dp))
                SectionLabel(stringResource(R.string.setup_device_profile))
                Spacer(Modifier.height(10.dp))
                DeviceCard(profile)

                Spacer(Modifier.height(24.dp))
                SectionLabel("QWEN 3.5 · 4B ПАРАМЕТРОВ")
                Spacer(Modifier.height(10.dp))

                if (viewModel.compatibleModels.isEmpty()) {
                    DeviceNotSupportedCard()
                } else {
                    val variants = viewModel.compatibleModels.values.flatten()
                    variants.forEach { variant ->
                        val isDownloaded = variant.fileName in downloadedModels
                        val isSelected = variant == selectedModel
                        val isRecommended = variant == viewModel.recommendedModel
                        val activeDl = downloadState
                        val isDownloading = activeDl is DownloadState.Progress &&
                            activeDl.label == "model" && isSelected
                        val downloadPercent = if (isDownloading)
                            activeDl.percent else 0f

                        ModelCard(
                            variant = variant,
                            isSelected = isSelected,
                            isDownloaded = isDownloaded,
                            isRecommended = isRecommended,
                            isDownloading = isDownloading,
                            downloadPercent = downloadPercent,
                            onSelect = { viewModel.selectModel(variant) },
                            onDelete = { pendingDelete = variant },
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    Spacer(Modifier.height(8.dp))
                    RecommendedLine(viewModel.recommendedModel.displayName)
                }
            }

            // Sticky bottom CTA
            if (viewModel.compatibleModels.isNotEmpty()) {
                val isSelectedDownloaded = selectedModel.fileName in downloadedModels
                StickyBottomCta(
                    label = stringResource(
                        if (isSelectedDownloaded) R.string.setup_start_chat
                        else R.string.setup_download_and_start
                    ),
                    onClick = { viewModel.startDownload() },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@Composable
private fun AppIdentityRow(versionName: String, onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displayLarge,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "OFFLINE · v$versionName",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onSettings) {
            Icon(
                Icons.Default.Settings,
                contentDescription = stringResource(R.string.common_settings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusPill(label: String) {
    val ok = okColor()
    val transition = rememberInfiniteTransition(label = "breathe")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe-scale",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .wrapContentSize()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(ok),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
    )
}

@Composable
private fun DeviceCard(profile: com.example.adaptivellm.device.DeviceProfile) {
    val backend = if (profile.hasVulkan) "VULKAN+CPU" else "CPU"
    val ramGb = String.format("%.1f", profile.totalRamMb / 1024f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = profile.deviceModel,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(Modifier.height(14.dp))

        // 2x2 grid
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatCell(label = "SOC", value = profile.socModel, modifier = Modifier.weight(1f))
                StatCell(label = "RAM", value = "$ramGb GB", modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatCell(label = "CORES", value = profile.cpuCores.toString(), modifier = Modifier.weight(1f))
                StatCell(label = "BACKEND", value = backend, modifier = Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(14.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Spacer(Modifier.height(12.dp))

        Text(
            text = "FEATURES",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(6.dp))
        val featuresText = if (profile.cpuFeatures.isEmpty()) "—"
            else profile.cpuFeatures.joinToString(" · ")
        Text(
            text = featuresText,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily, fontSize = 11.sp, lineHeight = 17.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value.uppercase(),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily, fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ModelCard(
    variant: ModelVariant,
    isSelected: Boolean,
    isDownloaded: Boolean,
    isRecommended: Boolean,
    isDownloading: Boolean,
    downloadPercent: Float,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val isActive = isDownloaded && isSelected
    val bg = if (isActive) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    val downloadedSuffix = stringResource(R.string.setup_downloaded).lowercase()
    val modelSpecsBase = stringResource(R.string.setup_model_specs, variant.fileSizeMb, variant.nominalMinRamGb)
    val specsText = buildString {
        append(modelSpecsBase)
        if (isDownloaded) append(" · ").append(downloadedSuffix)
        if (isDownloading) append(" · ${(downloadPercent * 100).toInt()}%")
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onSelect),
    ) {
        // Animated progress strip behind text
        if (isDownloading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = downloadPercent.coerceIn(0f, 1f))
                    .heightIn(min = 64.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Custom radio
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .border(
                        width = 1.5.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = variant.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                    )
                    if (isRecommended) {
                        Spacer(Modifier.width(8.dp))
                        RecChip()
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = specsText,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.5.sp),
                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isDownloaded) {
                IconButton(
                    onClick = onDelete,
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

@Composable
private fun RecChip() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = "REC",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 1.sp),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun RecommendedLine(name: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.setup_recommended_label) + " ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StickyBottomCta(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(100.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 18.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun DeviceNotSupportedCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(16.dp),
    ) {
        Text(
            stringResource(R.string.setup_not_supported_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.setup_not_supported_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
