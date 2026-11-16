package com.aistudio.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = GeoOnBackground,
    surface = GeoOnBackground,
    onPrimary = GeoOnPrimaryContainer,
    onBackground = GeoBackground,
    onSurface = GeoBackground
  )

private val LightColorScheme =
  lightColorScheme(
    primary = GeoPrimary,
    onPrimary = GeoOnPrimary,
    primaryContainer = GeoPrimaryContainer,
    onPrimaryContainer = GeoOnPrimaryContainer,
    secondary = GeoSecondary,
    onSecondary = GeoOnSecondary,
    secondaryContainer = GeoSecondaryContainer,
    onSecondaryContainer = GeoOnSecondaryContainer,
    surface = GeoSurfaceValue,
    onSurface = GeoOnSurfaceValue,
    background = GeoBackground,
    onBackground = GeoOnBackground,
    error = GeoError,
    outline = GeoBorder,
    surfaceVariant = GeoSurfaceValue,
    onSurfaceVariant = GeoTextSecondary
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false, // Set to false to force the Geometric Balance design colors
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
