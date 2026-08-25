package com.xianyu.vediodownload.ui.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianyu.vediodownload.ui.designsystem.theme.LocalGlassTokens
import com.xianyu.vediodownload.ui.designsystem.theme.glassConvex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun NeumorphicButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isPrimary: Boolean = true,
    isDanger: Boolean = false,
    isLoading: Boolean = false,
    minHeight: Dp = 52.dp,
    cornerRadius: Dp = 26.dp,
) {
    val haptic = LocalHapticFeedback.current
    val tokens = LocalGlassTokens.current

    var isPressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scaleAnim = remember { Animatable(1f) }

    fun pressDown() { isPressed = true; scope.launch { scaleAnim.snapTo(0.95f) } }
    fun pressUp() { isPressed = false; scope.launch { delay(60); scaleAnim.animateTo(1f, tween(200, easing = FastOutSlowInEasing)) } }

    val bgAlpha = when {
        !enabled -> 0.25f
        isPressed -> 0.45f
        else -> 0.35f
    }

    val tintColor = when {
        isDanger -> if (isPressed) Color(0x80EF4444) else Color(0x60EF4444)
        isPrimary -> if (isPressed) Color(0x600060E0) else Color(0x400060E0)
        else -> if (isPressed) Color(0x30000000) else Color(0x20000000)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .scale(scaleAnim.value)
            .glassConvex(cornerRadius = cornerRadius)
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        tintColor.copy(alpha = bgAlpha * 1.3f),
                        tintColor.copy(alpha = bgAlpha * 0.7f)
                    )
                )
            )
            .pointerInput(enabled) {
                if (enabled && !isLoading) detectTapGestures(
                    onPress = { pressDown(); tryAwaitRelease(); pressUp() },
                    onTap = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
                )
            }
            .padding(horizontal = 24.dp, vertical = 14.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(22.dp),
                color = when {
                    isDanger -> Color(0xFFEF4444)
                    isPrimary -> Color(0xFF2563EB)
                    else -> tokens.textSecondary
                },
                strokeWidth = 2.dp
            )
        } else {
            Text(
                text = text,
                color = when {
                    isDanger -> Color.White
                    isPrimary -> Color.White
                    else -> tokens.textPrimary
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
