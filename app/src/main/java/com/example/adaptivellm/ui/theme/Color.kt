package com.example.adaptivellm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Mono палитра редизайна Vertex / Mono. Все акценты — оттенки серого,
 * чтобы UI читался по контрасту и типографике, без цветного шума.
 */
val MonoDarkScheme = darkColorScheme(
    primary              = Color(0xFFDADADA),
    onPrimary            = Color(0xFF1A1A1A),
    primaryContainer     = Color(0xFF3A3A3A),
    onPrimaryContainer   = Color(0xFFF0F0F0),
    secondary            = Color(0xFFC5C5C5),
    onSecondary          = Color(0xFF1A1A1A),
    secondaryContainer   = Color(0xFF2D2D2D),
    onSecondaryContainer = Color(0xFFE8E8E8),
    tertiary             = Color(0xFFB5B5B5),
    tertiaryContainer    = Color(0xFF333333),
    onTertiaryContainer  = Color(0xFFE0E0E0),
    background           = Color(0xFF0A0A0A),
    onBackground         = Color(0xFFE8E8E8),
    surface              = Color(0xFF0A0A0A),
    surfaceVariant       = Color(0xFF1C1C1C),
    onSurface            = Color(0xFFE8E8E8),
    onSurfaceVariant     = Color(0xFFBFBFBF),
    outline              = Color(0xFF7A7A7A),
    outlineVariant       = Color(0xFF2C2C2C),
    error                = Color(0xFFEF8A7A),
    errorContainer       = Color(0xFF5A2A22),
    onErrorContainer     = Color(0xFFFFB8A8),
)

val MonoLightScheme = lightColorScheme(
    primary              = Color(0xFF1A1A1A),
    onPrimary            = Color(0xFFFFFFFF),
    primaryContainer     = Color(0xFFE2E2E2),
    onPrimaryContainer   = Color(0xFF000000),
    secondary            = Color(0xFF5A5A5A),
    onSecondary          = Color(0xFFFFFFFF),
    secondaryContainer   = Color(0xFFEBEBEB),
    onSecondaryContainer = Color(0xFF0A0A0A),
    tertiary             = Color(0xFF6F6F6F),
    tertiaryContainer    = Color(0xFFE0E0E0),
    onTertiaryContainer  = Color(0xFF0C0C0C),
    background           = Color(0xFFFCFCFC),
    onBackground         = Color(0xFF171717),
    surface              = Color(0xFFFCFCFC),
    surfaceVariant       = Color(0xFFECECEC),
    onSurface            = Color(0xFF171717),
    onSurfaceVariant     = Color(0xFF454545),
    outline              = Color(0xFF787878),
    outlineVariant       = Color(0xFFD4D4D4),
    error                = Color(0xFFBA1A1A),
    errorContainer       = Color(0xFFFFDAD6),
    onErrorContainer     = Color(0xFF410002),
)

object AppExtraColors {
    val Ok      = Color(0xFF88C98A) // breathing dot в dark теме
    val OkLight = Color(0xFF1F7A3A) // breathing dot в light теме
}

@Composable
fun okColor(): Color = if (isSystemInDarkTheme()) AppExtraColors.Ok else AppExtraColors.OkLight
