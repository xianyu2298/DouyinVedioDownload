package com.xianyu.vediodownload.ui.screens

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.xianyu.vediodownload.R
import com.xianyu.vediodownload.ui.designsystem.component.NeumorphicButton
import com.xianyu.vediodownload.ui.designsystem.theme.LocalGlassTokens

/**
 * 批量下载确认弹窗。
 * 标题"须知"，正文提示抖音风控风险，确定按钮（危险风格）、取消按钮（普通风格）。
 * 玻璃卡片外观，按钮风格与软件其它卡片保持一致。
 *
 * 仍然基于 Material3 AlertDialog，仅把它的容器（shape / containerColor）替换成玻璃风外观，
 * 这样 Dialog 窗口自带的半透明遮罩、点击外部关闭、返回键关闭等行为都直接复用，文字对比度也由
 * 玻璃表面的半透明白底保证，避免出现"背后黑色透过来导致字看不清"的问题。
 */
@Composable
fun BatchDownloadConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalGlassTokens.current

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = Color(0xF2FFFFFF),
        tonalElevation = 0.dp,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
        title = {
            Text(
                text = "须知",
                color = tokens.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                text = "由于抖音风控，短时间下载大量文件可能会遇到下载不全的情况，推荐进行选择下载，是否继续批量下载？",
                color = tokens.textSecondary,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
        },
        // Material3 AlertDialog 的按钮槽位：左侧为 dismissButton，右侧为 confirmButton。
        // 这里左侧放"确定"（危险），右侧放"取消"（普通），沿用用户对左右位置的既有认知。
        dismissButton = {
            NeumorphicButton(
                text = stringResource(R.string.xuzhi),
                onClick = onConfirm,
                isDanger = true,
            )
        },
        confirmButton = {
            NeumorphicButton(
                text = stringResource(R.string.history_action_cancel),
                onClick = onDismiss,
                isPrimary = true,
            )
        },
    )
}
