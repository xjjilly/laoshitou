// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.ui

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

val HifiColors = darkColorScheme(
    primary = Color(0xFFE6B450),
    onPrimary = Color(0xFF1A1408),
    secondary = Color(0xFF63D2C1),
    background = Color(0xFF0D0F12),
    onBackground = Color(0xFFE7E7E7),
    surface = Color(0xFF161A1F),
    onSurface = Color(0xFFE7E7E7),
    surfaceVariant = Color(0xFF1F252C),
    onSurfaceVariant = Color(0xFF8B949E),
)

val HifiAmber = Color(0xFFE6B450)
val HifiCyan = Color(0xFF63D2C1)
val HifiMuted = Color(0xFF8B949E)
val HifiGreen = Color(0xFF8BE28B)

val HifiTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
    ),
    bodySmall = TextStyle(fontFamily = FontFamily.Monospace),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace),
)
