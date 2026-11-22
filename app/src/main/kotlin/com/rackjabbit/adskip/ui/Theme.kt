package com.rackjabbit.adskip.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF121511)
private val Paper = Color(0xFFF4F1E8)
private val Brass = Color(0xFFC9A15B)
private val Moss = Color(0xFF24382C)
private val Leaf = Color(0xFF8FBF9F)
private val Clay = Color(0xFFB85C38)
private val NightSurface = Color(0xFF1B221D)
private val DaySurface = Color(0xFFFFFCF6)

private val DarkColors = darkColorScheme(
    primary = Brass,
    onPrimary = Color(0xFF1C1608),
    secondary = Leaf,
    onSecondary = Ink,
    background = Ink,
    onBackground = Color(0xFFF3F0E7),
    surface = NightSurface,
    onSurface = Color(0xFFF3F0E7),
    surfaceVariant = Color(0xFF273129),
    onSurfaceVariant = Color(0xFFB7B2A6),
    error = Clay,
    outline = Color(0xFF3C4941),
)

private val LightColors = lightColorScheme(
    primary = Moss,
    onPrimary = Paper,
    secondary = Color(0xFF8A6232),
    onSecondary = Paper,
    background = Paper,
    onBackground = Color(0xFF1C211C),
    surface = DaySurface,
    onSurface = Color(0xFF1C211C),
    surfaceVariant = Color(0xFFE7E1D4),
    onSurfaceVariant = Color(0xFF5C574E),
    error = Clay,
    outline = Color(0xFFD5CFC2),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        lineHeight = 36.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    ),
)

@Composable
fun AdSkipTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
