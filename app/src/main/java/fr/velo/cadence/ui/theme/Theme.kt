package fr.velo.cadence.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Teal50,
    onPrimary = Slate99,
    primaryContainer = Teal90,
    onPrimaryContainer = Teal10,
    secondary = Sand40,
    onSecondary = Slate99,
    secondaryContainer = Sand90,
    onSecondaryContainer = Slate10,
    tertiary = TrackBlue,
    background = Slate99,
    onBackground = Slate10,
    surface = Slate99,
    onSurface = Slate10,
    surfaceVariant = Slate95,
    onSurfaceVariant = Slate40,
    outline = Slate90,
    error = Coral60,
    onError = Slate99,
    errorContainer = Coral90,
    onErrorContainer = Slate10,
)

private val DarkColors = darkColorScheme(
    primary = Teal70,
    onPrimary = Teal10,
    primaryContainer = Teal30,
    onPrimaryContainer = Teal90,
    secondary = Sand60,
    onSecondary = Slate10,
    secondaryContainer = Sand40,
    onSecondaryContainer = Sand90,
    tertiary = Sky70,
    background = Slate06,
    onBackground = Slate95,
    surface = Slate10,
    onSurface = Slate95,
    surfaceVariant = Slate20,
    onSurfaceVariant = Slate90,
    outline = Slate40,
    error = Coral60,
    onError = Slate99,
)

@Composable
fun CadenceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = CadenceTypography,
        content = content,
    )
}
