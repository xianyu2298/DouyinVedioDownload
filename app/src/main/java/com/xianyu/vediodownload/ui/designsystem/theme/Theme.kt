package com.xianyu.vediodownload.ui.designsystem.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.xianyu.vediodownload.R

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF3B82F6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF2563EB),
    secondary = Color(0xFF5A6270),
    onSecondary = Color.White,
    background = Color(0xFFF3EFEA),
    onBackground = Color(0xFF1A1B1E),
    surface = Color(0xFFF5F8FC),
    onSurface = Color(0xFF1A1B1E),
    surfaceVariant = Color(0xFFF0F3F7),
    onSurfaceVariant = Color(0xFF5A6270),
    outline = Color(0xFFD1D5DB),
    error = Color(0xFFEF4444),
    onError = Color.White
)

@Composable
fun HyperOSTheme(
    skin: AppSkin = AppSkin.GLASS,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
    }

    CompositionLocalProvider(
        LocalHyperColors provides LightHyperColors,
        LocalGlassTokens provides LightGlassTokens,
        LocalAppSkin provides skin
    ) {
        MaterialTheme(
            colorScheme = LightColorScheme,
            typography = AppTypography,
            content = content
        )
    }
}
