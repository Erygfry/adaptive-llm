package com.example.adaptivellm.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.example.adaptivellm.settings.SettingsRepository

/**
 * Главный theme wrapper приложения.
 *
 * Поведение dark/light определяется пользовательской настройкой
 * [SettingsRepository.themeMode]:
 *   - SYSTEM → следует системной теме устройства (default)
 *   - LIGHT  → принудительно светлая
 *   - DARK   → принудительно тёмная
 *
 * Переключение реактивно (через collectAsState) — recomposition применяет
 * новую палитру без перезапуска Activity.
 */
@Composable
fun AdaptiveLLMTheme(
    content: @Composable () -> Unit
) {
    val mode by SettingsRepository.themeMode.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (mode) {
        SettingsRepository.ThemeMode.SYSTEM -> systemDark
        SettingsRepository.ThemeMode.LIGHT -> false
        SettingsRepository.ThemeMode.DARK -> true
    }

    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
