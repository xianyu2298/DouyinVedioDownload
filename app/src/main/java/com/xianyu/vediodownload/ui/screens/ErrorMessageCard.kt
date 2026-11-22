package com.xianyu.vediodownload.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianyu.vediodownload.ui.designsystem.component.GlassCard

/**
 * 统一的解析错误提示卡片。
 *
 * <p>除错误文案外，可选展示「复制诊断信息」入口：用户点一下即把版本号、当前模式、
 * 输入原文、识别到的链接和异常信息写入剪贴板，直接粘贴发给开发者。
 *
 * <p>这样做是因为绝大多数用户不会抓 Logcat。诊断文本不含 Cookie、不含账号信息。
 */
@Composable
fun ErrorMessageCard(
    message: String,
    diagnostics: String? = null,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = message,
            fontSize = 14.sp,
            color = Color(0xFFEF4444)
        )
        if (!diagnostics.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "复制诊断信息",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF3B82F6),
                modifier = Modifier.clickable {
                    clipboard.setText(AnnotatedString(diagnostics))
                    Toast.makeText(context, "已复制，粘贴发给开发者即可", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}
