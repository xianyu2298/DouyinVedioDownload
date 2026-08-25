package com.xianyu.vediodownload.ui.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.xianyu.vediodownload.ui.designsystem.theme.LocalGlassTokens
import com.xianyu.vediodownload.ui.designsystem.theme.glassConvex

@Composable
fun VideoPreviewCard(
    coverUrl: String,
    authorName: String,
    authorAvatarUrl: String = "",
    description: String = "",
    videoUrl: String = "",
    onPreviewClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tokens = LocalGlassTokens.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassConvex(cornerRadius = 20.dp)
            .padding(16.dp)
    ) {
        // 封面图 - 可点击预览
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(16.dp))
                .clickable { onPreviewClick() }
        ) {
            if (coverUrl.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(coverUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "视频封面",
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
                                    Color(0xFFE0E7FF),
                                    Color(0xFFFDE68A)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "🎬", fontSize = 56.sp)
                }
            }

            // 渐变遮罩
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.4f)
                            ),
                            startY = 200f
                        )
                    )
            )

            // 播放按钮（中央）
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "预览播放",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            // 提示标签
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .background(
                        Color.Black.copy(alpha = 0.7f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "点击预览",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 作者信息
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 作者头像
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (authorAvatarUrl.isNotEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(authorAvatarUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = "作者头像",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // 兜底：显示名字首字
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFF818CF8),
                                        Color(0xFFC084FC)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (authorName.isNotEmpty()) authorName.first().toString() else "?",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = authorName.ifEmpty { "未知作者" },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (authorName.isNotEmpty()) {
                    Text(
                        text = "@$authorName",
                        fontSize = 13.sp,
                        color = tokens.textTertiary,
                        maxLines = 1
                    )
                }
            }
        }

        if (description.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = description,
                fontSize = 14.sp,
                color = tokens.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 22.sp
            )
        }
    }
}

@Composable
fun ImagePreviewCard(
    authorName: String,
    authorAvatarUrl: String = "",
    description: String = "",
    imageCount: Int,
    imageUrls: String = "",
    onPreviewClick: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tokens = LocalGlassTokens.current
    val imageList = remember(imageUrls) { imageUrls.split("|||").filter { it.isNotEmpty() } }
    val hasMultiple = imageList.size > 1

    // 没有传入完整 url 列表时（兼容旧调用），用 coverUrl 的逻辑直接展示
    val fallbackCover = remember(imageList) { imageList.firstOrNull() ?: "" }

    var currentIndex by remember { mutableIntStateOf(0) }
    val thumbnailListState = rememberLazyListState()

    LaunchedEffect(imageList.size) {
        if (currentIndex >= imageList.size) currentIndex = 0
    }

    // 切换时让缩略图跟随滚动并居中
    LaunchedEffect(currentIndex, imageList.size) {
        if (imageList.isNotEmpty()) {
            thumbnailListState.animateScrollToItem(
                index = currentIndex.coerceIn(0, imageList.lastIndex)
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassConvex(cornerRadius = 20.dp)
            .padding(16.dp)
    ) {
        // 主图 + 左右箭头 + 计数
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(16.dp))
        ) {
            if (imageList.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imageList[currentIndex.coerceIn(0, imageList.lastIndex)])
                        .crossfade(true)
                        .build(),
                    contentDescription = "图片 ${currentIndex + 1}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onPreviewClick(currentIndex.coerceIn(0, imageList.lastIndex)) }
                )
            } else if (fallbackCover.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(fallbackCover)
                        .crossfade(true)
                        .build(),
                    contentDescription = "图片封面",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onPreviewClick(0) }
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFFFCE7F3),
                                    Color(0xFFDBEAFE)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "🖼️", fontSize = 56.sp)
                }
            }

            // 左侧箭头
            if (hasMultiple) {
                IconButton(
                    onClick = {
                        if (currentIndex > 0) currentIndex--
                    },
                    enabled = currentIndex > 0,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(4.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "上一张",
                        tint = if (currentIndex > 0) Color.White else Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // 右侧箭头
                IconButton(
                    onClick = {
                        if (currentIndex < imageList.lastIndex) currentIndex++
                    },
                    enabled = currentIndex < imageList.lastIndex,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(4.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    Icon(
                        Icons.Default.ArrowForward,
                        contentDescription = "下一张",
                        tint = if (currentIndex < imageList.lastIndex) Color.White else Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 左下：计数
            if (hasMultiple) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .background(
                            Color.Black.copy(alpha = 0.7f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "${currentIndex + 1} / ${imageList.size}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 右下：图片数量
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .background(
                        Color.Black.copy(alpha = 0.7f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "$imageCount 张图片",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // 右上：点击预览
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .background(
                        Color.Black.copy(alpha = 0.7f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .clickable { onPreviewClick(currentIndex.coerceIn(0, imageList.lastIndex)) }
            ) {
                Text(
                    text = "点击预览",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 缩略图条（仅多图时显示）
        if (hasMultiple) {
            Spacer(modifier = Modifier.height(12.dp))

            LazyRow(
                state = thumbnailListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(imageList) { index, url ->
                    val isSelected = index == currentIndex
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) tokens.textPrimary.copy(alpha = 0.9f)
                                else Color.Transparent
                            )
                            .padding(if (isSelected) 2.dp else 0.dp)
                            .clip(RoundedCornerShape(if (isSelected) 6.dp else 8.dp))
                            .clickable { currentIndex = index }
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(url)
                                .crossfade(true)
                                .build(),
                            contentDescription = "缩略图 ${index + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 作者信息
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (authorAvatarUrl.isNotEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(authorAvatarUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = "作者头像",
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
                                        Color(0xFF34D399),
                                        Color(0xFF6EE7B7)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (authorName.isNotEmpty()) authorName.first().toString() else "?",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = authorName.ifEmpty { "未知作者" },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (authorName.isNotEmpty()) {
                    Text(
                        text = "@$authorName",
                        fontSize = 13.sp,
                        color = tokens.textTertiary,
                        maxLines = 1
                    )
                }
            }
        }

        if (description.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = description,
                fontSize = 14.sp,
                color = tokens.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 22.sp
            )
        }
    }
}

@Composable
fun LivePhotoPreviewCard(
    authorName: String,
    authorAvatarUrl: String = "",
    description: String = "",
    liveCount: Int = 1,
    imageUrls: String = "",
    videoUrls: String = "",
    coverUrls: String = "",
    onPreviewClick: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tokens = LocalGlassTokens.current
    val imageList = remember(imageUrls) { imageUrls.split("|||").filter { it.isNotEmpty() } }
    val hasMultiple = imageList.size > 1

    var currentIndex by remember { mutableIntStateOf(0) }
    val thumbnailListState = rememberLazyListState()

    LaunchedEffect(imageList.size) {
        if (currentIndex >= imageList.size) currentIndex = 0
    }

    LaunchedEffect(currentIndex, imageList.size) {
        if (imageList.isNotEmpty()) {
            thumbnailListState.animateScrollToItem(
                index = currentIndex.coerceIn(0, imageList.lastIndex)
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassConvex(cornerRadius = 20.dp)
            .padding(16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(16.dp))
        ) {
            if (imageList.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imageList[currentIndex.coerceIn(0, imageList.lastIndex)])
                        .crossfade(true)
                        .build(),
                    contentDescription = "实况 ${currentIndex + 1}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onPreviewClick(currentIndex.coerceIn(0, imageList.lastIndex)) }
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFFFEF3C7),
                                    Color(0xFFFCE7F3)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "📸", fontSize = 56.sp)
                }
            }

            // 渐变遮罩
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.35f)
                            ),
                            startY = 300f
                        )
                    )
            )

            // LIVE 标签
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .background(
                        Color(0xFFFF6B6B),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (hasMultiple) "LIVE ×$liveCount" else "LIVE",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // 左下：计数
            if (hasMultiple) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .background(
                            Color.Black.copy(alpha = 0.7f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "${currentIndex + 1} / ${imageList.size}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 右下：实况数量
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .background(
                        Color.Black.copy(alpha = 0.7f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "$liveCount 个实况",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // 左侧箭头
            if (hasMultiple) {
                IconButton(
                    onClick = {
                        if (currentIndex > 0) currentIndex--
                    },
                    enabled = currentIndex > 0,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(4.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "上一个",
                        tint = if (currentIndex > 0) Color.White else Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // 右侧箭头
                IconButton(
                    onClick = {
                        if (currentIndex < imageList.lastIndex) currentIndex++
                    },
                    enabled = currentIndex < imageList.lastIndex,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(4.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    Icon(
                        Icons.Default.ArrowForward,
                        contentDescription = "下一个",
                        tint = if (currentIndex < imageList.lastIndex) Color.White else Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 播放按钮（中央）
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable { onPreviewClick(currentIndex.coerceIn(0, imageList.lastIndex)) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "预览播放",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            // 右上：点击预览
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .background(
                        Color.Black.copy(alpha = 0.7f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .clickable { onPreviewClick(currentIndex.coerceIn(0, imageList.lastIndex)) }
            ) {
                Text(
                    text = "点击预览",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 缩略图条（仅多图时显示）
        if (hasMultiple) {
            Spacer(modifier = Modifier.height(12.dp))

            LazyRow(
                state = thumbnailListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(imageList) { index, url ->
                    val isSelected = index == currentIndex
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) tokens.textPrimary.copy(alpha = 0.9f)
                                else Color.Transparent
                            )
                            .padding(if (isSelected) 2.dp else 0.dp)
                            .clip(RoundedCornerShape(if (isSelected) 6.dp else 8.dp))
                            .clickable { currentIndex = index },
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(url)
                                .crossfade(true)
                                .build(),
                            contentDescription = "缩略图 ${index + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        // 播放角标
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(2.dp)
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(10.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (authorAvatarUrl.isNotEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(authorAvatarUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = "作者头像",
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
                                        Color(0xFFFBBF24),
                                        Color(0xFFFCD34D)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (authorName.isNotEmpty()) authorName.first().toString() else "?",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = authorName.ifEmpty { "未知作者" },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "实况照片",
                    fontSize = 13.sp,
                    color = tokens.textTertiary,
                    maxLines = 1
                )
            }
        }

        if (description.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = description,
                fontSize = 14.sp,
                color = tokens.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 22.sp
            )
        }
    }
}
