package com.uhmk.pos.core.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.uhmk.pos.core.prefs.ThemeMode

/** A selectable brand accent. Index order is what Settings persists. */
data class Accent(val label: String, val seed: Color, val onSeed: Color, val container: Color, val onContainer: Color)

val Accents = listOf(
    Accent("Chili Red", Color(0xFFC0392B), Color.White, Color(0xFFFFDAD4), Color(0xFF410100)),
    Accent("Gochujang", Color(0xFFD1512D), Color.White, Color(0xFFFFDBCF), Color(0xFF3B0900)),
    Accent("Seoul Indigo", Color(0xFF3F51B5), Color.White, Color(0xFFDEE0FF), Color(0xFF00105C)),
    Accent("Jade", Color(0xFF00796B), Color.White, Color(0xFF7DF8E0), Color(0xFF00201A)),
    Accent("Charcoal", Color(0xFF37474F), Color.White, Color(0xFFD3E4EE), Color(0xFF0B1D26)),
)

private fun lightScheme(a: Accent): ColorScheme = lightColorScheme(
    primary = a.seed,
    onPrimary = a.onSeed,
    primaryContainer = a.container,
    onPrimaryContainer = a.onContainer,
    secondary = a.seed.copy(alpha = 1f).darken(0.15f),
    onSecondary = Color.White,
    secondaryContainer = a.container,
    onSecondaryContainer = a.onContainer,
    tertiary = Color(0xFF5D6060),
    surfaceTint = a.seed,
)

private fun darkScheme(a: Accent): ColorScheme = darkColorScheme(
    primary = a.seed.lighten(0.35f),
    onPrimary = Color(0xFF201010),
    primaryContainer = a.seed.darken(0.25f),
    onPrimaryContainer = a.container,
    secondary = a.seed.lighten(0.25f),
    onSecondary = Color(0xFF1A1010),
    secondaryContainer = a.seed.darken(0.35f),
    onSecondaryContainer = a.container,
    tertiary = Color(0xFFBFC8CC),
    surfaceTint = a.seed.lighten(0.35f),
)

private fun Color.darken(amount: Float): Color =
    Color(
        red = (red * (1 - amount)).coerceIn(0f, 1f),
        green = (green * (1 - amount)).coerceIn(0f, 1f),
        blue = (blue * (1 - amount)).coerceIn(0f, 1f),
        alpha = alpha,
    )

private fun Color.lighten(amount: Float): Color =
    Color(
        red = (red + (1 - red) * amount).coerceIn(0f, 1f),
        green = (green + (1 - green) * amount).coerceIn(0f, 1f),
        blue = (blue + (1 - blue) * amount).coerceIn(0f, 1f),
        alpha = alpha,
    )

@Composable
fun UhmKPosTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    accentIndex: Int = 0,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val accent = Accents.getOrElse(accentIndex) { Accents.first() }
    val context = LocalContext.current

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> darkScheme(accent)
        else -> lightScheme(accent)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = UhmKTypography,
        content = content,
    )
}
