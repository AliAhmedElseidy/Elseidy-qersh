package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

private val LightColorScheme = lightColorScheme(
    primary = SaidiGreenDark,
    onPrimary = Color.White,
    primaryContainer = SaidiGreenLight,
    onPrimaryContainer = SaidiGreenDark,
    secondary = SaidiGoldPrimary,
    onSecondary = Color(0xFF1F1705),
    secondaryContainer = SaidiGoldLight,
    onSecondaryContainer = SaidiGoldDark,
    tertiary = RefundTeal,
    onTertiary = Color.White,
    background = SaidiCreamBackground,
    onBackground = Color(0xFF14211B),
    surface = SaidiCreamSurface,
    onSurface = Color(0xFF14211B),
    surfaceVariant = SaidiCreamSurfaceVariant,
    onSurfaceVariant = Color(0xFF42524A),
    error = ExpenseRed,
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF56C596),
    onPrimary = Color(0xFF06291B),
    primaryContainer = SaidiGreenContainerDark,
    onPrimaryContainer = Color(0xFFD6EDE3),
    secondary = Color(0xFFE5C158),
    onSecondary = Color(0xFF281E05),
    secondaryContainer = Color(0xFF4F3B10),
    onSecondaryContainer = SaidiGoldLight,
    tertiary = Color(0xFF6CD4E6),
    onTertiary = Color(0xFF072A30),
    background = SaidiDarkBackground,
    onBackground = Color(0xFFEAEFE9),
    surface = SaidiDarkSurface,
    onSurface = Color(0xFFEAEFE9),
    surfaceVariant = SaidiDarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFB8C7BF),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF4A0F0F)
)

@Composable
fun SaidiQershTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    SaidiQershTheme(darkTheme = darkTheme, content = content)
}
