package com.xianyu.vediodownload.ui.player

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest

class LivePhotoPreviewActivity : AppCompatActivity() {

    private var imageUrls: String = ""
    private var videoUrls: String = ""
    private var coverUrls: String = ""
    private var startIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        imageUrls = intent.getStringExtra("image_urls") ?: ""
        videoUrls = intent.getStringExtra("video_urls") ?: ""
        coverUrls = intent.getStringExtra("cover_urls") ?: ""
        startIndex = intent.getIntExtra("current_index", 0)

        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        setContent {
            LivePhotoPreviewContent(
                imageUrls = imageUrls,
                videoUrls = videoUrls,
                coverUrls = coverUrls,
                startIndex = startIndex,
                onFinish = { finish() }
            )
        }
    }
}

@Composable
private fun LivePhotoPreviewContent(
    imageUrls: String,
    videoUrls: String,
    coverUrls: String,
    startIndex: Int,
    onFinish: () -> Unit
) {
    var showControls by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val context = LocalContext.current
    val imageList = remember { imageUrls.split("|||").filter { it.isNotEmpty() } }
    val videoList = remember { videoUrls.split("|||").filter { it.isNotEmpty() } }
    val coverList = remember { coverUrls.split("|||").filter { it.isNotEmpty() } }
    var currentIndex by remember { mutableIntStateOf(startIndex) }
    val listState = rememberLazyListState()

    val currentImageUrl = imageList.getOrNull(currentIndex) ?: ""
    val currentVideoUrl = videoList.getOrNull(currentIndex) ?: ""
    val currentCoverUrl = coverList.getOrNull(currentIndex) ?: currentImageUrl

    LaunchedEffect(currentIndex) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        if (imageList.size > currentIndex) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    BackHandler {
        onFinish()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { showControls = !showControls }
    ) {
        // 主图片
        if (currentImageUrl.isNotEmpty()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(currentImageUrl)
                    .crossfade(true)
                    .addHeader("Referer", "https://www.douyin.com/")
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                    .build(),
                contentDescription = "Live 图片预览",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 4f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    }
            )
        }

        // 控制栏
        if (showControls) {
            // 顶部栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(16.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (imageList.size > 1) {
                        IconButton(
                            onClick = { if (currentIndex > 0) currentIndex-- },
                            enabled = currentIndex > 0
                        ) {
                            Icon(
                                Icons.Default.ArrowBack,
                                "上一张",
                                tint = if (currentIndex > 0) Color.White else Color.Gray
                            )
                        }
                        Text(
                            text = "${currentIndex + 1} / ${imageList.size}",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        IconButton(
                            onClick = { if (currentIndex < imageList.size - 1) currentIndex++ },
                            enabled = currentIndex < imageList.size - 1
                        ) {
                            Icon(
                                Icons.Default.ArrowForward,
                                "下一张",
                                tint = if (currentIndex < imageList.size - 1) Color.White else Color.Gray
                            )
                        }
                    } else {
                        Text(
                            text = "Live 图预览",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                // 关闭按钮
                IconButton(onClick = onFinish) {
                    Icon(Icons.Default.Close, "关闭", tint = Color.White)
                }
            }

            // LIVE 标签 - 移到右上角
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 72.dp, end = 16.dp)
                    .background(Color(0xFFFF6B6B), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "LIVE",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // 底部区域
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 播放视频按钮 - 在缩略图上方
                if (currentVideoUrl.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .offset(y = (-16.dp))
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            .clickable {
                                val intent = android.content.Intent(
                                    context,
                                    VideoPlayerActivity::class.java
                                ).apply {
                                    putExtra("video_url", currentVideoUrl)
                                    putExtra("cover_url", currentCoverUrl)
                                }
                                context.startActivity(intent)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            "播放视频",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                // 提示文字
                Text(
                    text = "点击缩略图切换 • 双指缩放",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    modifier = Modifier.offset(y = (-8.dp))
                )

                // 底部缩略图栏 + LIVE标签
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 缩略图列表
                    if (imageList.size > 1) {
                        LazyRow(
                            state = listState,
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            itemsIndexed(imageList) { index, url ->
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .then(
                                            if (index == currentIndex) {
                                                Modifier.background(Color.White, RoundedCornerShape(8.dp))
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .padding(if (index == currentIndex) 2.dp else 0.dp)
                                        .clip(RoundedCornerShape(if (index == currentIndex) 6.dp else 8.dp))
                                        .clickable { currentIndex = index }
                                ) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(url)
                                            .crossfade(true)
                                            .addHeader("Referer", "https://www.douyin.com/")
                                            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                                            .build(),
                                        contentDescription = "缩略图 $index",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }

                }
            }
        }
    }
}
