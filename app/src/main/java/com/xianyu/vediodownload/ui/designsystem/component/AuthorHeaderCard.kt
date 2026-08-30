package com.xianyu.vediodownload.ui.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.xianyu.vediodownload.ui.designsystem.theme.LocalGlassTokens
import com.xianyu.vediodownload.ui.designsystem.theme.glassConvex

/**
 * 作者主页头部卡片 —— 模仿移动端抖音作者主页头部样式。
 *
 * - 顶层是背景图（使用头像作为兜底，避免空白），加一层底部渐变黑色蒙版让文字可读；
 * - 头像为白色描边圆形；
 * - 标题展示作者昵称、副标题展示抖音号；
 * - 整体外观沿用 glassConvex，与其他 PreviewCards 风格一致。
 */
@Composable
fun AuthorHeaderCard(
    nickname: String,
    avatarUrl: String,
    backgroundUrl: String = "",
    douyinId: String = "",
    totalWorks: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val backgroundSource = backgroundUrl.ifBlank { avatarUrl }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .glassConvex(cornerRadius = 24.dp)
            .clip(RoundedCornerShape(24.dp))
            .height(220.dp)
    ) {
        // 背景层（图片或渐变兜底）
        if (backgroundSource.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(backgroundSource)
                    .crossfade(true)
                    .build(),
                contentDescription = "作者主页背景",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFFFFE4B5),
                                Color(0xFFFFB6C1),
                                Color(0xFFB0E0E6),
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "👤", fontSize = 64.sp)
            }
        }

        // 渐变蒙版：底部加深，确保文字可读
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.20f),
                            Color.Black.copy(alpha = 0.65f),
                        ),
                    )
                )
        )

        // 内容层：头像 + 昵称 + 抖音号
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 头像
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .padding(3.dp)
                    .clip(CircleShape)
            ) {
                if (avatarUrl.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(avatarUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = "作者头像",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(Color(0xFFE0E7FF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "👤", fontSize = 28.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = nickname.ifBlank { "未知作者" },
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (douyinId.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "抖音号：$douyinId",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                    )
                }
                if (totalWorks > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "共 $totalWorks 个作品",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/**
 * 作者主页头部 —— 简化版本（用于在无法获取到作品列表时占位）。
 */
@Composable
fun AuthorHeaderPlaceholder(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .glassConvex(cornerRadius = 24.dp)
            .height(120.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "请粘贴作者主页链接开始解析",
            color = LocalGlassTokens.current.textSecondary,
            fontSize = 14.sp,
        )
    }
}
