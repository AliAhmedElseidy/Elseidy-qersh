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
    onPrimaryContainer = Color(0xFF072B1E),
    secondary = SaidiGoldPrimary,
    onSecondary = Color(0xFF1F1705),
    secondaryContainer = SaidiGoldLight,
    onSecondaryContainer = Color(0xFF59400B),
    tertiary = RefundTeal,
    onTertiary = Color.White,
    background = SaidiCreamBackground,
    onBackground = Color(0xFF121F19),
    surface = SaidiCreamSurface,
    onSurface = Color(0xFF121F19),
    surfaceVariant = SaidiCreamSurfaceVariant,
    onSurfaceVariant = Color(0xFF3F5148),
    outline = Color(0xFFB5C2BA),
    error = ExpenseRed,
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF5CD6A5),
    onPrimary = Color(0xFF042619),
    primaryContainer = Color(0xFF163D2D),
    onPrimaryContainer = Color(0xFFD9F5E8),
    secondary = Color(0xFFE8C564),
    onSecondary = Color(0xFF281E05),
    secondaryContainer = Color(0xFF3E3010),
    onSecondaryContainer = Color(0xFFFAEAC9),
    tertiary = Color(0xFF72D9EB),
    onTertiary = Color(0xFF06292F),
    background = Color(0xFF0B1511),
    onBackground = Color(0xFFECF2EE),
    surface = Color(0xFF13221C),
    onSurface = Color(0xFFECF2EE),
    surfaceVariant = Color(0xFF1C3229),
    onSurfaceVariant = Color(0xFFC2D1C9),
    outline = Color(0xFF3C544A),
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
