package com.xianyu.vediodownload.ui.designsystem.component

import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianyu.vediodownload.ui.designsystem.theme.LocalGlassTokens
import com.xianyu.vediodownload.ui.designsystem.theme.glassConcave

/**
 * 新拟态输入框。
 *
 * 右侧内置两个小按钮（由 [showActions] 控制）：
 * - 剪贴板导入：读取系统剪贴板文本填入输入框
 * - 一键清除：清空当前内容（仅在有内容时显示）
 *
 * 粘贴/清除优先读取 Compose 的 ClipboardManager 文本，空剪贴板时给出 Toast 提示。
 */
@Composable
fun NeumorphicInputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isFocused: Boolean = false,
    showActions: Boolean = true,
) {
    val tokens = LocalGlassTokens.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .glassConcave(cornerRadius = 16.dp)
            .border(
                width = if (isFocused) 1.5.dp else 0.5.dp,
                color = if (isFocused) Color(0xFF3B82F6) else Color(0x30000000),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(
                        color = tokens.textPrimary,
                        fontSize = 16.sp
                    ),
                    cursorBrush = SolidColor(Color(0xFF3B82F6)),
                    decorationBox = { inner ->
                        Box {
                            if (value.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    color = tokens.textTertiary,
                                    fontSize = 16.sp
                                )
                            }
                            inner()
                        }
                    }
                )
            }

            if (showActions) {
                // 从剪贴板导入
                IconButton(
                    onClick = {
                        val text = clipboard.getText()?.text.orEmpty().trim()
                        if (text.isEmpty()) {
                            Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
                        } else {
                            onValueChange(text)
                            Toast.makeText(context, "已从剪贴板导入", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = "从剪贴板导入",
                        tint = Color(0xFF3B82F6),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // 一键清除（仅有内容时显示，避免空输入框多一个无效按钮）
                if (value.isNotEmpty()) {
                    IconButton(
                        onClick = { onValueChange("") },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "清除输入",
                            tint = tokens.textTertiary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
