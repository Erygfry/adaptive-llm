package com.example.adaptivellm.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.adaptivellm.R
import com.example.adaptivellm.settings.SettingsRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val themeMode by SettingsRepository.themeMode.collectAsState()
    val crossChatFacts by SettingsRepository.crossChatFactsEnabled.collectAsState()
    val appLanguage by SettingsRepository.appLanguage.collectAsState()
    val materialYou by SettingsRepository.useMaterialYou.collectAsState()
    val activity = LocalContext.current as? Activity

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { viewModel.closeSettings() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                title = {
                    Text(
                        stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineMedium,
                    )
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            // ТЕМА
            SectionLabel(stringResource(R.string.settings_section_theme))
            Spacer(modifier = Modifier.height(10.dp))
            CardGroup {
                RadioRow(
                    label = stringResource(R.string.settings_theme_system),
                    selected = themeMode == SettingsRepository.ThemeMode.SYSTEM,
                    onClick = { SettingsRepository.setThemeMode(SettingsRepository.ThemeMode.SYSTEM) },
                )
                GroupDivider()
                RadioRow(
                    label = stringResource(R.string.settings_theme_light),
                    selected = themeMode == SettingsRepository.ThemeMode.LIGHT,
                    onClick = { SettingsRepository.setThemeMode(SettingsRepository.ThemeMode.LIGHT) },
                )
                GroupDivider()
                RadioRow(
                    label = stringResource(R.string.settings_theme_dark),
                    selected = themeMode == SettingsRepository.ThemeMode.DARK,
                    onClick = { SettingsRepository.setThemeMode(SettingsRepository.ThemeMode.DARK) },
                )
                GroupDivider()
                SwitchRow(
                    title = "Material You",  // бренд Google, не локализуется
                    body = stringResource(R.string.settings_material_you_body),
                    checked = materialYou,
                    onCheckedChange = { SettingsRepository.setUseMaterialYou(it) },
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            // ПАМЯТЬ
            SectionLabel(stringResource(R.string.settings_section_memory))
            Spacer(modifier = Modifier.height(10.dp))
            CardGroup {
                SwitchRow(
                    title = stringResource(R.string.settings_cross_chat_label),
                    body = stringResource(
                        if (crossChatFacts) R.string.settings_cross_chat_on
                        else R.string.settings_cross_chat_off
                    ),
                    checked = crossChatFacts,
                    onCheckedChange = { SettingsRepository.setCrossChatFactsEnabled(it) },
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            // ЯЗЫК
            SectionLabel(stringResource(R.string.settings_section_language))
            Spacer(modifier = Modifier.height(10.dp))
            val languageOptions = listOf(
                SettingsRepository.AppLanguage.SYSTEM to stringResource(R.string.settings_language_system),
                SettingsRepository.AppLanguage.EN to "English",
                SettingsRepository.AppLanguage.RU to "Русский",
                SettingsRepository.AppLanguage.ES to "Español",
                SettingsRepository.AppLanguage.FR to "Français",
                SettingsRepository.AppLanguage.DE to "Deutsch",
                SettingsRepository.AppLanguage.ZH to "中文",
            )
            CardGroup {
                languageOptions.forEachIndexed { index, (lang, label) ->
                    if (index > 0) GroupDivider()
                    RadioRow(
                        label = label,
                        selected = appLanguage == lang,
                        onClick = {
                            SettingsRepository.setAppLanguage(lang)
                            activity?.recreate()
                        },
                    )
                }
            }

            /*
            // ДАННЫЕ — раздел временно скрыт до реализации экспорта (SAF + JSON
            // сериализация всех чатов). Composable DataExportRow и строки
            // settings_section_data / settings_export_all оставлены — раскомментируем,
            // когда фича будет готова.
            Spacer(modifier = Modifier.height(22.dp))
            SectionLabel(stringResource(R.string.settings_section_data))
            Spacer(modifier = Modifier.height(10.dp))
            DataExportRow(
                label = stringResource(R.string.settings_export_all),
                onClick = { /* TODO: export всех чатов */ },
            )
            */
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
    )
}

@Composable
private fun CardGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        content = content,
    )
}

@Composable
private fun GroupDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CustomRadio(selected = selected)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CustomRadio(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .border(
                width = 1.5.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.primary)
                else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onPrimary),
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (body.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

@Composable
private fun DataExportRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Download,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
