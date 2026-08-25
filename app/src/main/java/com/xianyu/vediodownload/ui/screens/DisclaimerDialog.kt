package com.xianyu.vediodownload.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.xianyu.vediodownload.ui.designsystem.component.NeumorphicButton
import com.xianyu.vediodownload.ui.designsystem.theme.LocalGlassTokens

/**
 * 免责声明弹窗。
 * 玻璃卡片外观，单按钮（"我已知悉"），首次启动时强制展示，点击外部 / 返回键无法关闭。
 * 风格与 BatchDownloadConfirmDialog 保持一致。
 */
@Composable
fun DisclaimerDialog(
    onConfirm: () -> Unit,
) {
    val tokens = LocalGlassTokens.current

    AlertDialog(
        onDismissRequest = { /* 首次启动强制展示，禁止点击外部关闭 */ },
        shape = RoundedCornerShape(28.dp),
        containerColor = Color(0xF2FFFFFF),
        tonalElevation = 0.dp,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = {
            Text(
                text = "免责声明",
                color = tokens.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                text = "本项目仅供本人学习研究，严禁用于任何商业用途。\n" +
                        "\n" +
                        "用户使用本软件产生的一切行为，全部由使用者本人承担全部法律责任。\n" +
                        "\n" +
                        "因违规使用本软件造成的版权侵权、民事赔偿、行政处罚等一切后果，项目开发者不承担任何连带责任。",
                color = tokens.textSecondary,
                fontSize = 15.sp,
                lineHeight = 24.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
            )
        },
        confirmButton = {
            NeumorphicButton(
                text = "我已知悉",
                onClick = onConfirm,
                isPrimary = true,
                modifier = Modifier.fillMaxWidth(),
                minHeight = 48.dp,
                cornerRadius = 24.dp,
            )
        },
    )
}
