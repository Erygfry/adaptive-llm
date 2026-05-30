package com.example.adaptivellm.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.example.adaptivellm.settings.SettingsRepository

/**
 * Главный theme wrapper приложения.
 *
 * По умолчанию используется фиксированная Mono палитра редизайна
 * (см. [MonoDarkScheme] / [MonoLightScheme]). Пользователь может включить
 * Material You в настройках — тогда на Android 12+ применяется
 * dynamicColorScheme(context) из обоев.
 *
 * Dark/light режим задаётся через [SettingsRepository.themeMode]:
 *   - SYSTEM → системная тема устройства (default)
 *   - LIGHT  → принудительно светлая
 *   - DARK   → принудительно тёмная
 */
@Composable
fun AdaptiveLLMTheme(
    content: @Composable () -> Unit
) {
    val mode by SettingsRepository.themeMode.collectAsState()
    val useMaterialYou by SettingsRepository.useMaterialYou.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (mode) {
        SettingsRepository.ThemeMode.SYSTEM -> systemDark
        SettingsRepository.ThemeMode.LIGHT -> false
        SettingsRepository.ThemeMode.DARK -> true
    }

    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = if (useMaterialYou && dynamicAvailable) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (darkTheme) MonoDarkScheme else MonoLightScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
