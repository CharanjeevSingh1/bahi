package dev.charanjeev.bahi.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Green40,
    secondary = Sand40,
    error = Red40,
)

private val DarkColors = darkColorScheme(
    primary = Green80,
    secondary = Sand80,
    error = Red80,
)

/**
 * Colours with a fixed meaning, which therefore cannot come from the dynamic
 * colour scheme. [warning] is the only one so far: see Amber40's comment for
 * why a wallpaper-derived slot can't carry it.
 *
 * A CompositionLocal rather than a plain object so it follows the same
 * light/dark decision [BahiTheme] already made, instead of asking
 * isSystemInDarkTheme() a second time and disagreeing when a caller forces a
 * theme (a @Preview, or a screenshot test).
 */
data class SemanticColors(val warning: Color)

val LocalSemanticColors = staticCompositionLocalOf {
    SemanticColors(warning = Amber40)
}

@Composable
fun BahiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val semanticColors = SemanticColors(warning = if (darkTheme) Amber80 else Amber40)

    CompositionLocalProvider(LocalSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}
