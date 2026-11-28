package com.xianyu.vediodownload.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import com.xianyu.vediodownload.R
import android.widget.Toast
import com.xianyu.vediodownload.model.Platform
import com.xianyu.vediodownload.util.CookieManager
import com.xianyu.vediodownload.ui.designsystem.component.*
import com.xianyu.vediodownload.ui.designsystem.theme.*
import com.xianyu.vediodownload.ui.player.VideoPlayerActivity

@Composable
fun VideoScreen(
    viewModel: DownloadViewModel = viewModel(),
    appMode: Platform = Platform.DOUYIN
) {
    val scrollState = rememberScrollState()
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val tokens = LocalGlassTokens.current
    val cookieManager = remember { CookieManager(context.applicationContext) }

    // 下载中弹窗出现时请求 POST_NOTIFICATIONS 权限（Android 13+）
    val lifecycleOwner = LocalLifecycleOwner.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 无论结果如何都不影响功能；用户拒绝只是看不到通知 */ }

    LaunchedEffect(uiState.batch?.active) {
        if (uiState.batch?.active == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onForegroundReturned()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = AppSpacing.safeHorizontal)
            .padding(bottom = 100.dp)
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = if (appMode == Platform.KUAISHOU) "快手视频" else stringResource(R.string.tab_video),
            style = MaterialTheme.typography.headlineMedium,
            color = tokens.textPrimary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        NeumorphicInputField(
            value = uiState.url,
            onValueChange = { viewModel.updateUrl(it) },
            placeholder = if (appMode == Platform.KUAISHOU) {
                stringResource(R.string.hint_input_url_kuaishou)
            } else {
                stringResource(R.string.hint_input_url)
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

            NeumorphicButton(
            text = stringResource(R.string.btn_parse),
            onClick = {
                // 只有抖音解析依赖登录 Cookie；快手走 H5 分享页，无需登录态
                if (appMode == Platform.DOUYIN && !cookieManager.isSet()) {
                    Toast.makeText(context, "请先在设置页设置 Cookie", Toast.LENGTH_LONG).show()
                    return@NeumorphicButton
                }
                focusManager.clearFocus()
                viewModel.parse()
            },
            enabled = !uiState.isLoading && uiState.url.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        if (uiState.isLoading) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color(0xFF3B82F6),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = uiState.loadingMessage,
                        fontSize = 14.sp,
                        color = tokens.textSecondary
                    )
                }
            }
        }

        uiState.error?.let { error ->
            Spacer(modifier = Modifier.height(12.dp))
            ErrorMessageCard(message = error, diagnostics = uiState.diagnostics)
        }

        uiState.preview?.let { p ->
            if (uiState.isVideo) {
                Spacer(modifier = Modifier.height(12.dp))
                VideoPreviewCard(
                    coverUrl = p.coverUrl,
                    authorName = p.authorName,
                    authorAvatarUrl = p.authorAvatarUrl,
                    description = p.description,
                    videoUrl = p.videoUrl,
                    qualityLabel = p.qualityLabel,
                    musicLabel = p.musicLabel,
                    onPreviewClick = {
                        val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                            putExtra("video_url", p.videoUrl)
                            putExtra("cover_url", p.coverUrl)
                            putExtra(
                                "referer",
                                if (appMode == Platform.KUAISHOU) {
                                    "https://v.m.chenzhongtech.com/"
                                } else {
                                    "https://www.douyin.com/"
                                }
                            )
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(20.dp))

                NeumorphicButton(
                    text = stringResource(R.string.btn_download_video),
                    onClick = { viewModel.downloadVideo() },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 音频来源由用户选择：同一作品可能同时存在曲库 BGM 与原声
                NeumorphicButton(
                    text = stringResource(R.string.btn_download_audio),
                    onClick = { viewModel.requestAudioDownload() },
                    modifier = Modifier.fillMaxWidth(),
                    isPrimary = false,
                )
            }
        }

        // 旧的 GlassCard 进度条已移除，改用弹窗（见文件底部）。
    }

    uiState.audioDialog?.let { dialog ->
        AudioSourceDialog(
            state = dialog,
            onSelect = { viewModel.downloadAudio(it) },
            onDismiss = { viewModel.dismissAudioDialog() },
        )
    }

    // 批量下载进度弹窗：活动且未被用户手动关闭时显示
    val session = uiState.batch
    if (session != null && session.active && !session.dialogDismissed) {
        BatchDownloadProgressDialog(
            session = session,
            onBackground = { viewModel.dismissBatchDialog() },
        )
    }
}
