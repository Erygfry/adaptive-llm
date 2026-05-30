@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.example.adaptivellm.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.adaptivellm.R

// Bundled variable TTF (wght axis). Тянутся из res/font/ — оффлайн, без сети.
private fun spaceGrotesk(weight: FontWeight) = Font(
    resId = R.font.space_grotesk,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

private fun manrope(weight: FontWeight) = Font(
    resId = R.font.manrope,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

private fun jetbrainsMono(weight: FontWeight) = Font(
    resId = R.font.jetbrains_mono,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val DisplayFamily: FontFamily = FontFamily(
    spaceGrotesk(FontWeight.Normal),
    spaceGrotesk(FontWeight.Medium),
    spaceGrotesk(FontWeight.SemiBold),
    spaceGrotesk(FontWeight.Bold),
)

val BodyFamily: FontFamily = FontFamily(
    manrope(FontWeight.Normal),
    manrope(FontWeight.Medium),
    manrope(FontWeight.SemiBold),
    manrope(FontWeight.Bold),
)

val MonoFamily: FontFamily = FontFamily(
    jetbrainsMono(FontWeight.Normal),
    jetbrainsMono(FontWeight.Medium),
    jetbrainsMono(FontWeight.SemiBold),
)

val AppTypography = Typography(
    displayLarge   = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold, fontSize = 34.sp,   letterSpacing = (-1.2).sp, lineHeight = 36.sp),
    headlineLarge  = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.Medium,   fontSize = 26.sp,   letterSpacing = (-0.6).sp, lineHeight = 28.sp),
    headlineMedium = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.Medium,   fontSize = 22.sp,   letterSpacing = (-0.4).sp, lineHeight = 26.sp),
    titleMedium    = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,   lineHeight = 20.sp),
    titleSmall     = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.Medium,   fontSize = 14.sp,   lineHeight = 18.sp),
    bodyLarge      = TextStyle(fontFamily = BodyFamily,    fontWeight = FontWeight.Normal,   fontSize = 14.sp,   lineHeight = 22.sp),
    bodyMedium     = TextStyle(fontFamily = BodyFamily,    fontWeight = FontWeight.Normal,   fontSize = 13.5.sp, lineHeight = 20.sp),
    bodySmall      = TextStyle(fontFamily = BodyFamily,    fontWeight = FontWeight.Normal,   fontSize = 12.sp,   lineHeight = 18.sp),
    labelLarge     = TextStyle(fontFamily = MonoFamily,    fontWeight = FontWeight.SemiBold, fontSize = 11.sp,   letterSpacing = 1.sp),
    labelMedium    = TextStyle(fontFamily = MonoFamily,    fontWeight = FontWeight.Medium,   fontSize = 10.5.sp, letterSpacing = 1.5.sp),
    labelSmall     = TextStyle(fontFamily = MonoFamily,    fontWeight = FontWeight.Medium,   fontSize = 9.5.sp,  letterSpacing = 1.sp),
)
