// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.ui

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

val MinimalColors = lightColorScheme(
    primary = Color(0xFF2E5A4C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E8DF),
    onPrimaryContainer = Color(0xFF17352C),
    secondary = Color(0xFF8A5A3B),
    background = Color(0xFFF7F3EA),
    onBackground = Color(0xFF1F2522),
    surface = Color(0xFFFFFBF4),
    onSurface = Color(0xFF1F2522),
    surfaceVariant = Color(0xFFE9E2D7),
    onSurfaceVariant = Color(0xFF5A5F5B),
)

val ImmersiveColors = darkColorScheme(
    primary = Color(0xFF9BB8FF),
    onPrimary = Color(0xFF0B1020),
    secondary = Color(0xFFC7A8FF),
    background = Color(0xFF0A0F1E),
    onBackground = Color(0xFFE8ECFF),
    surface = Color(0xFF141A2B),
    onSurface = Color(0xFFE8ECFF),
    surfaceVariant = Color(0xFF1D2540),
    onSurfaceVariant = Color(0xFFB9C1DE),
)

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

val MinimalTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
    ),
)

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
