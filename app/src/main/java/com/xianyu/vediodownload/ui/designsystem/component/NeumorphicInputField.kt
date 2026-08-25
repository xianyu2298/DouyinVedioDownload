package com.xianyu.vediodownload.ui.designsystem.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianyu.vediodownload.ui.designsystem.theme.LocalGlassTokens
import com.xianyu.vediodownload.ui.designsystem.theme.glassConcave

@Composable
fun NeumorphicInputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isFocused: Boolean = false,
) {
    val tokens = LocalGlassTokens.current

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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
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
}
