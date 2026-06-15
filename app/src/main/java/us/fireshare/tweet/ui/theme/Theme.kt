package us.fireshare.tweet.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = XBlue,
    onPrimary = XLightBackground,
    primaryContainer = XDarkBackground,
    onPrimaryContainer = XDarkText,
    inversePrimary = XBlue,
    secondary = XDarkSecondaryText,
    onSecondary = XDarkBackground,
    secondaryContainer = XDarkSurfaceVariant,
    onSecondaryContainer = XDarkText,
    tertiary = XDarkSecondaryText,
    onTertiary = XDarkBackground,
    tertiaryContainer = XDarkSurfaceVariant,
    onTertiaryContainer = XDarkText,
    background = XDarkBackground,
    onBackground = XDarkText,
    surface = XDarkBackground,
    onSurface = XDarkText,
    surfaceVariant = XDarkSurfaceVariant,
    onSurfaceVariant = XDarkSecondaryText,
    surfaceTint = XDarkText,
    inverseSurface = XDarkText,
    inverseOnSurface = XLightText,
    error = XRed,
    onError = XLightBackground,
    errorContainer = XRed,
    onErrorContainer = XLightBackground,
    outline = XDarkBorder,
    outlineVariant = XDarkSurfaceVariant,
    scrim = XDarkBackground
)

private val LightColorScheme = lightColorScheme(
    primary = XBlue,
    onPrimary = XLightBackground,
    primaryContainer = XLightBackground,
    onPrimaryContainer = XLightText,
    inversePrimary = XBlue,
    secondary = XLightSecondaryText,
    onSecondary = XLightBackground,
    secondaryContainer = XLightSurfaceVariant,
    onSecondaryContainer = XLightText,
    tertiary = XLightSecondaryText,
    onTertiary = XLightBackground,
    tertiaryContainer = XLightSurfaceVariant,
    onTertiaryContainer = XLightText,
    background = XLightBackground,
    onBackground = XLightText,
    surface = XLightBackground,
    onSurface = XLightText,
    surfaceVariant = XLightSurfaceVariant,
    onSurfaceVariant = XLightSecondaryText,
    surfaceTint = XLightText,
    inverseSurface = XLightText,
    inverseOnSurface = XDarkText,
    error = XRed,
    onError = XLightBackground,
    errorContainer = XRed,
    onErrorContainer = XLightBackground,
    outline = XLightBorder,
    outlineVariant = XLightSurfaceVariant,
    scrim = XDarkBackground
)

// Theme state manager for reactive theme changes
object ThemeManager {
    var currentThemeMode by mutableStateOf("light")
        private set
    
    fun updateThemeMode(mode: String) {
        currentThemeMode = mode
    }
}

@Composable
fun TweetTheme(
    themeMode: String = "light",
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // Update the theme manager with the current mode
    ThemeManager.updateThemeMode(themeMode)
    
    val darkTheme = when (ThemeManager.currentThemeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme() // "system" or default
    }
    
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                @Suppress("DEPRECATION")
                window.statusBarColor = colorScheme.background.toArgb()
                @Suppress("DEPRECATION")
                window.navigationBarColor = colorScheme.surface.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
