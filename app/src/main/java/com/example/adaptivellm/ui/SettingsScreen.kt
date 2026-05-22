package com.example.adaptivellm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import android.app.Activity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.adaptivellm.R
import com.example.adaptivellm.settings.SettingsRepository

/**
 * Stage 7 — экран настроек приложения.
 *
 * Сейчас покрывает: тема, переключатель cross-chat фактов. Языковой
 * переключатель — следующая итерация (требует локализации UI).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val themeMode by SettingsRepository.themeMode.collectAsState()
    val crossChatFacts by SettingsRepository.crossChatFactsEnabled.collectAsState()
    val appLanguage by SettingsRepository.appLanguage.collectAsState()
    // Нужен для recreate() после смены языка — на Android < 13 AppCompatDelegate
    // не пересоздаёт ComponentActivity автоматически (только AppCompatActivity).
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
                        style = MaterialTheme.typography.titleMedium,
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ThemeSection(
                current = themeMode,
                onChange = { SettingsRepository.setThemeMode(it) },
            )

            HorizontalDivider()

            MemorySection(
                crossChatEnabled = crossChatFacts,
                onToggle = { SettingsRepository.setCrossChatFactsEnabled(it) },
            )

            HorizontalDivider()

            LanguageSection(
                current = appLanguage,
                onChange = {
                    SettingsRepository.setAppLanguage(it)
                    // Принудительный recreate — apply locale тут же увидит результат.
                    // На Android 13+ это уже не нужно (LocaleManager сам recreate'ит),
                    // но повторный вызов безопасен.
                    activity?.recreate()
                },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun ThemeSection(
    current: SettingsRepository.ThemeMode,
    onChange: (SettingsRepository.ThemeMode) -> Unit,
) {
    Column {
        SectionTitle(stringResource(R.string.settings_section_theme))
        Spacer(modifier = Modifier.height(4.dp))
        ThemeOption(stringResource(R.string.settings_theme_system),
            selected = current == SettingsRepository.ThemeMode.SYSTEM,
            onClick = { onChange(SettingsRepository.ThemeMode.SYSTEM) })
        ThemeOption(stringResource(R.string.settings_theme_light),
            selected = current == SettingsRepository.ThemeMode.LIGHT,
            onClick = { onChange(SettingsRepository.ThemeMode.LIGHT) })
        ThemeOption(stringResource(R.string.settings_theme_dark),
            selected = current == SettingsRepository.ThemeMode.DARK,
            onClick = { onChange(SettingsRepository.ThemeMode.DARK) })
    }
}

@Composable
private fun ThemeOption(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun MemorySection(
    crossChatEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Column {
        SectionTitle(stringResource(R.string.settings_section_memory))
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { onToggle(!crossChatEnabled) }
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_cross_chat_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(
                        if (crossChatEnabled) R.string.settings_cross_chat_on
                        else R.string.settings_cross_chat_off
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = crossChatEnabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun LanguageSection(
    current: SettingsRepository.AppLanguage,
    onChange: (SettingsRepository.AppLanguage) -> Unit,
) {
    Column {
        SectionTitle(stringResource(R.string.settings_section_language))
        Spacer(modifier = Modifier.height(4.dp))
        // Native-name каждого языка (показываем на самом языке, не транслитом),
        // чтобы юзер находил свой даже если интерфейс на незнакомом языке.
        // Только «Системный» локализуется — остальные специально на родном языке.
        val options = listOf(
            SettingsRepository.AppLanguage.SYSTEM to stringResource(R.string.settings_language_system),
            SettingsRepository.AppLanguage.EN to "English",
            SettingsRepository.AppLanguage.RU to "Русский",
            SettingsRepository.AppLanguage.ES to "Español",
            SettingsRepository.AppLanguage.FR to "Français",
            SettingsRepository.AppLanguage.DE to "Deutsch",
            SettingsRepository.AppLanguage.ZH to "中文",
        )
        for ((lang, label) in options) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onChange(lang) }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = current == lang, onClick = { onChange(lang) })
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}
