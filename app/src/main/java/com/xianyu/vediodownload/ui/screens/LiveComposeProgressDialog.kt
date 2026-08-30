package com.xianyu.vediodownload.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.xianyu.vediodownload.ui.designsystem.component.NeumorphicButton
import com.xianyu.vediodownload.ui.designsystem.theme.LocalGlassTokens

/**
 * Live 图批量合成进度弹窗 —— 复用 [BatchDownloadProgressDialog] 的视觉骨架，但文案专属"合成"。
 *
 * - 标题"合成 Live 图中…"，正文"正在合成第 N / M 张"；
 * - 单一按钮"后台合成"，回调 [onBackground]（由 ViewModel 隐藏弹窗并切换到通知）；
 * - 仅当 [LiveComposeSession.active] == true 且 dialogDismissed == false 时显示；
 * - 进度条走 M3 1.1.x 兼容 API（progress: Float 直接传）。
 */
@Composable
fun LiveComposeProgressDialog(
    session: LiveComposeSession,
    onBackground: () -> Unit,
) {
    val tokens = LocalGlassTokens.current
    val current = session.current.coerceAtMost(session.total)
    val total = session.total
    val percent = session.percent.coerceIn(0, 100)

    AlertDialog(
        onDismissRequest = { /* 强制走"后台合成"按钮，避免误关错过通知 */ },
        shape = RoundedCornerShape(28.dp),
        containerColor = Color(0xF2FFFFFF),
        tonalElevation = 0.dp,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = {
            Text(
                text = "合成 Live 图中…",
                color = tokens.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "正在合成第 $current / $total 张",
                    color = tokens.textSecondary,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                )
                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ComposeProgressBarTrack(
                        progress = percent / 100f,
                        height = 8.dp,
                        color = Color(0xFF7C3AED),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "$percent%",
                        color = tokens.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (total > 1) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val overall = ((session.successCount + session.failureCount) * 100f / total).toInt().coerceIn(0, 100)
                        ComposeProgressBarTrack(
                            progress = overall / 100f,
                            height = 6.dp,
                            color = Color(0xFFA78BFA),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "$overall%",
                            color = tokens.textTertiary,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "已完成 ${session.successCount + session.failureCount} / $total 张",
                        color = tokens.textTertiary,
                        fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = {
            NeumorphicButton(
                text = "后台合成",
                onClick = onBackground,
                modifier = Modifier.widthIn(min = 132.dp),
                isPrimary = true,
            )
        },
        dismissButton = {},
    )
}

@Composable
private fun ComposeProgressBarTrack(
    progress: Float,
    height: Dp,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val radius = RoundedCornerShape(height / 2)
    Box(
        modifier = modifier
            .height(height)
            .clip(radius)
            .background(Color(0x20000000))
    ) {
        LinearProgressIndicator(
            progress = progress.coerceIn(0f, 1f),
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(radius),
            color = color,
            trackColor = Color.Transparent,
        )
    }
}
