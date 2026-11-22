package com.xianyu.vediodownload.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.xianyu.vediodownload.R
import com.xianyu.vediodownload.ui.designsystem.component.NeumorphicButton
import com.xianyu.vediodownload.ui.designsystem.theme.LocalGlassTokens
import com.xianyu.vediodownload.update.UpdateInfo

/**
 * 发现新版本弹窗。
 *
 * 更新内容直接展示 Gitee release 的 body 原文（纯文本，保留换行），不引入 Markdown 渲染。
 * 外观与 [BatchDownloadConfirmDialog] 保持一致（玻璃白底、NeumorphicButton）。
 */
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalGlassTokens.current
    val context = LocalContext.current

    val subtitle = buildString {
        if (info.title().isNotEmpty()) {
            append(info.title())
        } else {
            append("v").append(info.version())
        }
        if (info.publishedAt().isNotEmpty()) {
            append(" · ").append(info.publishedAt())
        }
    }

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
                    text = stringResource(R.string.update_dialog_title),
                    color = tokens.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = subtitle,
                    color = tokens.textTertiary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        },
        text = {
            val changelog = info.changelog()
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .trim()
            val changelogText = if (changelog.isEmpty()) {
                stringResource(R.string.update_dialog_empty_changelog)
            } else {
                changelog
            }
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = changelogText,
                    color = tokens.textSecondary,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                )
            }
        },
        dismissButton = {
            NeumorphicButton(
                text = stringResource(R.string.update_btn_later),
                onClick = onDismiss,
                isPrimary = false,
            )
        },
        confirmButton = {
            NeumorphicButton(
                text = stringResource(R.string.update_btn_now),
                onClick = {
                    openExternalUrl(context, info.downloadUrlOrDefault())
                    onUpdate()
                },
                isPrimary = true,
            )
        },
    )
}

/**
 * 用浏览器打开外部链接。
 *
 * 只做跳转，不做应用内下载与静默安装：静默安装需要 REQUEST_INSTALL_PACKAGES 权限，
 * 且与签名绑定，收益低风险高。
 */
internal fun openExternalUrl(context: Context, url: String) {
    if (url.isBlank()) {
        Toast.makeText(context, R.string.update_open_failed, Toast.LENGTH_LONG).show()
        return
    }
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, R.string.update_open_failed, Toast.LENGTH_LONG).show()
    }
}
