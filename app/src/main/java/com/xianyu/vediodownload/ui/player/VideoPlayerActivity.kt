package com.xianyu.vediodownload.ui.player

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/**
 * 视频流式预览播放器。
 *
 * 使用 Media3 ExoPlayer 直接对无水印直链做 HTTP 渐进式流式播放：
 * - 边下边播，无需先下载完整文件（大文件秒开）
 * - PlayerView 内置可拖动进度条 / 时间 / 播放暂停控制
 * - RESIZE_MODE_FIT 保证画面居中等比缩放，不再贴顶
 *
 * ⚠️ 仅供个人技术学习使用，禁止商用
 */
class VideoPlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val videoUrl = intent.getStringExtra("video_url") ?: ""

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
            VideoPlayerContent(
                videoUrl = videoUrl,
                onAttachPlayer = { player = it },
                onFinish = { finish() }
            )
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}

private const val DEFAULT_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"

@Composable
private fun VideoPlayerContent(
    videoUrl: String,
    onAttachPlayer: (ExoPlayer?) -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    var showTopBar by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val player = remember(videoUrl) {
        if (videoUrl.isEmpty()) {
            errorMessage = "无法获取视频链接"
            null
        } else {
            ExoPlayer.Builder(context).build().apply {
                // 必须带 Referer + UA，否则抖音 CDN 返回 403
                val dataSourceFactory = DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(
                        mapOf(
                            "Referer" to "https://www.douyin.com/",
                            "User-Agent" to DEFAULT_UA
                        )
                    )
                val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(videoUrl))
                setMediaSource(mediaSource)
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        errorMessage = "播放失败: ${error.errorCode}"
                    }
                })
                prepare()
                playWhenReady = true
            }
        }
    }

    // 退出时释放播放器
    DisposableEffect(Unit) {
        onAttachPlayer(player)
        onDispose {
            player?.release()
            onAttachPlayer(null)
        }
    }

    // 3 秒无操作自动隐藏顶部栏
    LaunchedEffect(showTopBar) {
        if (showTopBar) {
            delay(3000)
            showTopBar = false
        }
    }

    BackHandler {
        player?.stop()
        onFinish()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                showTopBar = !showTopBar
                player?.let { p ->
                    p.playWhenReady = !p.playWhenReady
                }
            }
    ) {
        // ExoPlayer 视图：内置进度条 / 时间 / 播放控制，RESIZE_MODE_FIT 居中等比缩放
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                        // 画面居中等比缩放（替代原 VideoView 的贴顶布局）
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setControllerShowTimeoutMs(3000)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 顶部栏（标题 + 关闭），点击画面切换显隐
        AnimatedVisibility(
            visible = showTopBar,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "视频预览",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                IconButton(onClick = {
                    player?.stop()
                    onFinish()
                }) {
                    Icon(Icons.Default.Close, "关闭", tint = Color.White)
                }
            }
        }

        errorMessage?.let { error ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Text(error, color = Color(0xFFFF6B6B), fontSize = 14.sp)
            }
        }
    }
}
