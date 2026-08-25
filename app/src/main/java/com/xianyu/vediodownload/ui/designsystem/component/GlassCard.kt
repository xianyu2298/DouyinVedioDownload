package com.xianyu.vediodownload.ui.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianyu.vediodownload.ui.designsystem.theme.LocalGlassTokens
import com.xianyu.vediodownload.ui.designsystem.theme.glassConvex

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .glassConvex(cornerRadius = 28.dp)
            .padding(20.dp)
    ) {
        Column(content = content)
    }
}

@Composable
fun GlassProgressBar(
    progress: Int,
    modifier: Modifier = Modifier
) {
    val tokens = LocalGlassTokens.current
    val fraction = progress / 100f

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "下载中...",
                fontSize = 14.sp,
                color = tokens.textSecondary
            )
            Text(
                text = "$progress%",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF2563EB)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(
                    color = Color(0x20000000),
                    shape = RoundedCornerShape(4.dp)
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                            colors = listOf(Color(0xFF3B82F6), Color(0xFF2563EB))
                        ),
                        shape = RoundedCornerShape(4.dp)
                    )
            )
        }
    }
}
