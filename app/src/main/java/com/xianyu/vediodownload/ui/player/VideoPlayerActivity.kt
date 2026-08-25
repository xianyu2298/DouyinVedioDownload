package com.xianyu.vediodownload.ui.player

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class VideoPlayerActivity : AppCompatActivity() {

    private var videoUrl: String = ""
    private var coverUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        videoUrl = intent.getStringExtra("video_url") ?: ""
        coverUrl = intent.getStringExtra("cover_url") ?: ""

        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
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
                coverUrl = coverUrl,
                onFinish = { finish() }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
private fun VideoPlayerContent(
    videoUrl: String,
    coverUrl: String,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(true) }
    var isDownloading by remember { mutableStateOf(true) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var currentPosition by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var localFilePath by remember { mutableStateOf<String?>(null) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }

    LaunchedEffect(videoUrl) {
        if (videoUrl.isEmpty()) {
            isDownloading = false
            isLoading = false
            errorMessage = "无法获取视频链接"
            return@LaunchedEffect
        }

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = File(System.getProperty("java.io.tmpdir"))
                val videoFile = File(cacheDir, "preview_video_${System.currentTimeMillis()}.mp4")

                val client = OkHttpClient()
                val request = Request.Builder()
                    .url(videoUrl)
                    .addHeader("Referer", "https://www.douyin.com/")
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        errorMessage = "下载失败: ${response.code}"
                        isDownloading = false
                        isLoading = false
                    }
                    return@launch
                }

                val body = response.body ?: throw Exception("Empty response body")
                val totalBytes = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(videoFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead

                            if (totalBytes > 0) {
                                val progress = ((totalRead * 100) / totalBytes).toInt()
                                withContext(Dispatchers.Main) {
                                    downloadProgress = progress
                                }
                            }
                        }
                    }
                }

                localFilePath = videoFile.absolutePath

                withContext(Dispatchers.Main) {
                    isDownloading = false
                    isLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage = "下载失败: ${e.message}"
                    isDownloading = false
                    isLoading = false
                }
            }
        }
    }

    LaunchedEffect(videoViewRef) {
        while (videoViewRef != null) {
            videoViewRef?.let {
                currentPosition = it.currentPosition
            }
            delay(500)
        }
    }

    BackHandler {
        videoViewRef?.stopPlayback()
        onFinish()
    }

    fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%02d:%02d".format(min, sec)
    }

    fun togglePlay() {
        videoViewRef?.let { vv ->
            if (vv.isPlaying) {
                vv.pause()
                isPlaying = false
            } else {
                vv.start()
                isPlaying = true
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                showControls = !showControls
            }
    ) {
        if (localFilePath != null && !isDownloading && !isLoading) {
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        val vv = VideoView(ctx).apply {
                            setVideoURI(android.net.Uri.parse(localFilePath))
                            setOnPreparedListener { mp ->
                                duration = mp.duration
                                mp.isLooping = false
                                mp.start()
                                isLoading = false
                                isPlaying = true
                            }
                            setOnCompletionListener {
                                isPlaying = false
                            }
                            setOnErrorListener { _, what, _ ->
                                Toast.makeText(ctx, "播放错误: $what", Toast.LENGTH_SHORT).show()
                                isPlaying = false
                                true
                            }
                        }
                        addView(vv, FrameLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        ))
                        videoViewRef = vv
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (isDownloading) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (downloadProgress > 0) "正在缓冲: $downloadProgress%" else "正在加载...",
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
        }

        if (isLoading && !isDownloading) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        errorMessage?.let { error ->
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = error, color = Color(0xFFFF6B6B), fontSize = 14.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onFinish,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
                ) {
                    Text("关闭")
                }
            }
        }

        AnimatedVisibility(
            visible = showControls && localFilePath != null && !isDownloading,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)
                            )
                        )
                        .padding(16.dp)
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("视频预览", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    IconButton(onClick = {
                        videoViewRef?.stopPlayback()
                        onFinish()
                    }) {
                        Icon(Icons.Default.Close, "关闭", tint = Color.White)
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable { togglePlay() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                            )
                        )
                        .padding(16.dp)
                        .align(Alignment.BottomCenter)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatTime(currentPosition), color = Color.White, fontSize = 12.sp)
                        Text(formatTime(duration), color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
