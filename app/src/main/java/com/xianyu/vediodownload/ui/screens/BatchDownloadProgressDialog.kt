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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.xianyu.vediodownload.R
import com.xianyu.vediodownload.ui.designsystem.component.NeumorphicButton
import com.xianyu.vediodownload.ui.designsystem.theme.LocalGlassTokens

/**
 * 批量下载进度弹窗 —— 玻璃风格，跟 BatchDownloadConfirmDialog 同一观感。
 *
 * - 标题始终"下载中…"，正文显示"正在下载第 N / M 个"以及当前文件百分比；
 * - 单一按钮"后台下载"，点击后回调 [onBackground]（由 ViewModel 隐藏弹窗并切换到通知）；
 * - 若批次结束（[BatchSession.active] == false 或 null），则不显示。
 *
 * 进度条走 M3 1.1.x 兼容 API（progress: Float 直接传，1.2 才引入 lambda 重载）。
 */
@Composable
fun BatchDownloadProgressDialog(
    session: BatchSession,
    onBackground: () -> Unit,
) {
    val tokens = LocalGlassTokens.current
    val current = session.current.coerceAtMost(session.total)
    val total = session.total
    val percent = session.percent.coerceIn(0, 100)
    val overall = if (total <= 0) 0
    else (((current - 1) * 100f + percent) / total).toInt().coerceIn(0, 100)

    AlertDialog(
        onDismissRequest = { /* 强制用户走"后台下载"按钮，避免误关错过通知 */ },
        shape = RoundedCornerShape(28.dp),
        containerColor = Color(0xF2FFFFFF),
        tonalElevation = 0.dp,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = {
            Text(
                text = stringResource(R.string.batch_progress_title),
                color = tokens.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.batch_progress_file_label, current, total),
                    color = tokens.textSecondary,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                )
                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProgressBarTrack(
                        progress = percent / 100f,
                        height = 8.dp,
                        color = Color(0xFF2563EB),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.batch_progress_percent, percent),
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
                        ProgressBarTrack(
                            progress = overall / 100f,
                            height = 6.dp,
                            color = Color(0xFF60A5FA),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.batch_progress_percent, overall),
                            color = tokens.textTertiary,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.notify_text_downloading,
                            session.successCount + session.failureCount,
                            total,
                        ),
                        color = tokens.textTertiary,
                        fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = {
            NeumorphicButton(
                text = stringResource(R.string.batch_progress_action_background),
                onClick = onBackground,
                modifier = Modifier.widthIn(min = 132.dp),
                isPrimary = true,
            )
        },
        dismissButton = {},
    )
}

/**
 * 自绘进度条：用 Box 画背景槽 + LinearProgressIndicator 画填充。
 * 这样 progress 颜色完全受控，不被 M3 主题色覆盖。
 */
@Composable
private fun ProgressBarTrack(
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
