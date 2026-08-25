package com.xianyu.vediodownload.ui.designsystem.theme

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun getNeumorphicColors(): Triple<Color, Color, Color> {
    val colors = LocalHyperColors.current
    val bgColor = colors.liquidScreenBg
    val lightShadow = Color(0xFFFFFFFF)
    val darkShadow = Color(0xFFC8CDD6)
    return Triple(bgColor, lightShadow, darkShadow)
}

@Composable
fun Modifier.neumorphicConvex(
    cornerRadius: Dp = 24.dp,
    elevation: Dp = 6.dp,
): Modifier {
    if (LocalAppSkin.current == AppSkin.GLASS) {
        return this.glassConvex(cornerRadius, LocalGlassTokens.current)
    }

    val (bgColor, lightShadow, darkShadow) = getNeumorphicColors()

    return this
        .clip(RoundedCornerShape(cornerRadius))
        .background(bgColor)
        .drawBehind {
            drawIntoCanvas { canvas ->
                val composePaint = Paint().apply {
                    asFrameworkPaint().apply {
                        isAntiAlias = true
                        maskFilter = BlurMaskFilter(elevation.toPx(), BlurMaskFilter.Blur.NORMAL)
                    }
                }
                composePaint.color = darkShadow
                canvas.drawRoundRect(
                    left = elevation.toPx() * 0.5f,
                    top = elevation.toPx() * 0.5f,
                    right = size.width + elevation.toPx() * 0.5f,
                    bottom = size.height + elevation.toPx() * 0.5f,
                    radiusX = cornerRadius.toPx(),
                    radiusY = cornerRadius.toPx(),
                    paint = composePaint
                )
                composePaint.color = lightShadow
                canvas.drawRoundRect(
                    left = -elevation.toPx() * 0.5f,
                    top = -elevation.toPx() * 0.5f,
                    right = size.width - elevation.toPx() * 0.5f,
                    bottom = size.height - elevation.toPx() * 0.5f,
                    radiusX = cornerRadius.toPx(),
                    radiusY = cornerRadius.toPx(),
                    paint = composePaint
                )
            }
        }
}

@Composable
fun Modifier.neumorphicConcave(
    cornerRadius: Dp = 24.dp,
    elevation: Dp = 4.dp,
): Modifier {
    if (LocalAppSkin.current == AppSkin.GLASS) {
        return this.glassConcave(cornerRadius, LocalGlassTokens.current)
    }

    val (bgColor, lightShadow, darkShadow) = getNeumorphicColors()

    return this
        .clip(RoundedCornerShape(cornerRadius))
        .background(bgColor)
        .drawWithContent {
            drawIntoCanvas { canvas ->
                val radiusPx = cornerRadius.toPx()
                val elevationPx = elevation.toPx()
                val blurFilter = BlurMaskFilter(elevationPx, BlurMaskFilter.Blur.NORMAL)

                val darkPaint = Paint().apply {
                    color = darkShadow.copy(alpha = 0.8f)
                    asFrameworkPaint().apply {
                        isAntiAlias = true
                        maskFilter = blurFilter
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = elevationPx * 2
                    }
                }
                canvas.drawRoundRect(
                    left = -elevationPx * 0.5f,
                    top = -elevationPx * 0.5f,
                    right = size.width,
                    bottom = size.height,
                    radiusX = radiusPx,
                    radiusY = radiusPx,
                    paint = darkPaint
                )

                val lightPaint = Paint().apply {
                    color = lightShadow
                    asFrameworkPaint().apply {
                        isAntiAlias = true
                        maskFilter = blurFilter
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = elevationPx * 2
                    }
                }
                canvas.drawRoundRect(
                    left = 0f,
                    top = 0f,
                    right = size.width + elevationPx * 0.5f,
                    bottom = size.height + elevationPx * 0.5f,
                    radiusX = radiusPx,
                    radiusY = radiusPx,
                    paint = lightPaint
                )
            }
            drawContent()
        }
}

@Composable
fun Modifier.neumorphic3D(
    cornerRadius: Dp = 28.dp,
    elevation: Dp = 8.dp,
    isOverlay: Boolean = false
): Modifier {
    if (LocalAppSkin.current == AppSkin.GLASS) {
        return this.glassConvex(cornerRadius, LocalGlassTokens.current)
    }
    return this.neumorphicConvex(cornerRadius, elevation)
}
