package com.example.qsconnection.ui.theme

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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val PlayStationColorScheme = darkColorScheme(
    primary = PS_Blue,
    onPrimary = PS_White,
    primaryContainer = PS_DarkBlue,
    onPrimaryContainer = PS_White,
    secondary = PS_LightBlue,
    onSecondary = PS_White,
    background = PS_Black,
    surface = PS_DarkBlue,
    onBackground = PS_White,
    onSurface = PS_White,
    error = PS_Circle
)

@Composable
fun QsconnectionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Forzamos el tema oscuro para el estilo PlayStation
    content: @Composable () -> Unit
) {
    val colorScheme = PlayStationColorScheme
    val view = LocalView.current
    
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = PS_Black.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
