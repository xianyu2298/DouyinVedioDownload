package com.xianyu.vediodownload.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * 「下载音频」来源选择弹窗。
 *
 * 同一个作品可能既有曲库背景音乐、又有现场原声，两者内容完全不同，
 * 所以不做自动选择，交由用户明确点选。不可用的来源仍然展示（置灰 + 原因），
 * 避免用户误以为功能缺失。
 */
@Composable
fun AudioSourceDialog(
    state: AudioDialogState,
    onSelect: (AudioSource) -> Unit,
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
            Column {
                Text(
                    text = "下载音频",
                    color = tokens.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "请选择要保存的音频来源",
                    color = tokens.textTertiary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (state.musicLabel.isNotEmpty()) {
                    Text(
                        text = "🎵 ${state.musicLabel}",
                        color = tokens.textSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                state.options.forEachIndexed { index, option ->
                    if (index > 0) {
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    AudioOptionRow(
                        title = option.title,
                        description = option.description,
                        enabled = option.enabled,
                        onClick = { onSelect(option.source) },
                    )
                }
            }
        },
        dismissButton = {
            NeumorphicButton(
                text = stringResource(R.string.history_action_cancel),
                onClick = onDismiss,
                isPrimary = false,
            )
        },
        confirmButton = {},
    )
}

@Composable
private fun AudioOptionRow(
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalGlassTokens.current
    val contentAlpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (enabled) Color(0xFF3B82F6).copy(alpha = 0.08f) else Color(0xFF9CA3AF).copy(alpha = 0.08f)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = tokens.textPrimary.copy(alpha = contentAlpha),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description,
                color = tokens.textTertiary.copy(alpha = contentAlpha),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (enabled) "›" else "不可用",
            color = if (enabled) Color(0xFF3B82F6) else tokens.textTertiary,
            fontSize = if (enabled) 20.sp else 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
