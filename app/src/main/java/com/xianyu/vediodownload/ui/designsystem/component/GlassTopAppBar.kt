package com.xianyu.vediodownload.ui.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianyu.vediodownload.ui.designsystem.theme.LocalGlassTokens

/**
 * 玻璃 TopAppBar —— 与 MainScreen 的 GlassTabBar 同款视觉：
 *   - 外阴影 + 圆角 clip + tintConvex 半透明底 + 顶部 innerHighlight 高光 + 渐变描边
 *   - 容器完全透明，让底层 Box 的玻璃层穿透
 *   - 左侧 navigationIcon 默认带"返回"（auto-mirrored），右侧 actions 默认带"解析新作者"
 *
 * 用于作者下载详情页等需要"顶栏 + 返回"的子页面，跟软件整体玻璃/新拟态风格保持一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTopAppBar(
    title: String,
    onBack: () -> Unit,
    onParseNew: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalGlassTokens.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 让出系统状态栏高度（顶部 padding = statusBar inset）
            // 放在 fillMaxWidth 之后，作为整体"被向下挤"的边距，玻璃外壳自然从状态栏下方开始
            .windowInsetsPadding(WindowInsets.statusBars)
            // 外阴影（与 GlassTabBar 一致）
            .drawWithContent {
                drawContent()
                drawRoundRect(
                    color = tokens.outerShadow,
                    topLeft = Offset(0f, 3.dp.toPx()),
                    size = size,
                    cornerRadius = CornerRadius(20.dp.toPx(), 20.dp.toPx())
                )
            }
            .clip(RoundedCornerShape(20.dp))
            // 半透明白底
            .drawBehind { drawRect(tokens.tintConvex) }
            // 顶部内高光
            .drawWithContent {
                drawContent()
                drawRect(
                    color = tokens.innerHighlight,
                    size = Size(size.width, 1.5.dp.toPx())
                )
            }
            // 渐变描边
            .drawBehind {
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(tokens.borderHi, tokens.borderLo)
                    ),
                    cornerRadius = CornerRadius(20.dp.toPx(), 20.dp.toPx()),
                    style = Stroke(1.dp.toPx())
                )
            }
            .height(64.dp)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = tokens.textPrimary,
                    )
                }
            },
            actions = {
                IconButton(onClick = onParseNew) {
                    Icon(
                        imageVector = Icons.Default.PersonAdd,
                        contentDescription = "解析新作者",
                        tint = tokens.textPrimary,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
                navigationIconContentColor = tokens.textPrimary,
                titleContentColor = tokens.textPrimary,
                actionIconContentColor = tokens.textPrimary,
            ),
        )
    }
}
