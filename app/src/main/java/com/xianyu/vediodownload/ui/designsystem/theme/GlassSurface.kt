package com.xianyu.vediodownload.ui.designsystem.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private fun drawMeshInternal(
    drawScope: androidx.compose.ui.graphics.drawscope.DrawScope,
    tokens: GlassTokens,
    fullSize: Size,
    offset: Offset = Offset.Zero
) {
    drawScope.drawRect(tokens.meshBase, topLeft = offset, size = fullSize)
    val minDim = minOf(fullSize.width, fullSize.height)
    for (spot in tokens.meshSpots) {
        val cx = spot.xFraction * fullSize.width + offset.x
        val cy = spot.yFraction * fullSize.height + offset.y
        val center = Offset(cx, cy)
        val radius = (spot.radiusFraction * minDim).coerceAtLeast(1f)
        drawScope.drawRect(
            Brush.radialGradient(
                colors = listOf(spot.color, Color.Transparent),
                center = center,
                radius = radius
            ),
            topLeft = Offset(cx - radius, cy - radius),
            size = Size(radius * 2, radius * 2)
        )
    }
}

@Composable
fun GlassMeshBackground(
    tokens: GlassTokens,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val fullSize = remember(configuration.screenWidthDp, configuration.screenHeightDp, density) {
        with(density) {
            Size(
                configuration.screenWidthDp.dp.toPx(),
                configuration.screenHeightDp.dp.toPx()
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 离屏层：让网格背景独立成一张纹理图层，
            // Pager 滑动时不会触发本节点重新记录 draw 操作。
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithCache {
                // drawWithCache 仅在 size 或 tokens 变化时重新构建画布命令，
                // 包含其中的所有 Brush.radialGradient 只生成一次。
                val tokensRef = tokens
                val sizeRef = fullSize
                onDrawBehind {
                    drawRect(tokensRef.meshBase, topLeft = Offset.Zero, size = sizeRef)
                    val minDim = minOf(sizeRef.width, sizeRef.height)
                    for (spot in tokensRef.meshSpots) {
                        val cx = spot.xFraction * sizeRef.width
                        val cy = spot.yFraction * sizeRef.height
                        val radius = (spot.radiusFraction * minDim).coerceAtLeast(1f)
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(spot.color, Color.Transparent),
                                center = Offset(cx, cy),
                                radius = radius
                            ),
                            topLeft = Offset(cx - radius, cy - radius),
                            size = Size(radius * 2, radius * 2)
                        )
                    }
                }
            }
    )
}

@Composable
fun Modifier.glassConvex(
    cornerRadius: Dp = 24.dp,
    tokens: GlassTokens = LocalGlassTokens.current
): Modifier {
    val rp = with(LocalDensity.current) { cornerRadius.toPx() }

    return this
        .drawBehind {
            drawRoundRect(
                color = tokens.outerShadow,
                topLeft = Offset(0f, 2.dp.toPx()),
                size = size,
                cornerRadius = CornerRadius(rp, rp)
            )
        }
        .clip(RoundedCornerShape(cornerRadius))
        .drawBehind { drawRect(tokens.tintConvex) }
        .drawWithContent {
            drawContent()
            drawRect(color = tokens.innerHighlight, size = Size(size.width, 1.dp.toPx()))
        }
        .drawBehind {
            drawRoundRect(
                brush = Brush.linearGradient(listOf(tokens.borderHi, tokens.borderLo)),
                cornerRadius = CornerRadius(rp, rp),
                style = Stroke(1.dp.toPx())
            )
        }
}

@Composable
fun Modifier.glassConcave(
    cornerRadius: Dp = 24.dp,
    tokens: GlassTokens = LocalGlassTokens.current
): Modifier {
    val rp = with(LocalDensity.current) { cornerRadius.toPx() }

    return this
        .clip(RoundedCornerShape(cornerRadius))
        .drawBehind { drawRect(tokens.tintConcave) }
        .drawBehind {
            drawRoundRect(
                brush = Brush.linearGradient(listOf(tokens.borderHi, tokens.borderLo)),
                cornerRadius = CornerRadius(rp, rp),
                style = Stroke(1.dp.toPx())
            )
        }
}
