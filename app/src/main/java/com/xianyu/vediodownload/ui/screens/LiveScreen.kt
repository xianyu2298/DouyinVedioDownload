package com.xianyu.vediodownload.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.xianyu.vediodownload.R
import android.widget.Toast
import com.xianyu.vediodownload.util.CookieManager
import com.xianyu.vediodownload.ui.designsystem.component.*
import com.xianyu.vediodownload.ui.designsystem.theme.*
import com.xianyu.vediodownload.ui.player.LivePhotoPreviewActivity
import com.xianyu.vediodownload.ui.player.VideoPlayerActivity

@Composable
fun LiveScreen(
    viewModel: DownloadViewModel = viewModel()
) {
    val scrollState = rememberScrollState()
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val tokens = LocalGlassTokens.current

    val imageUrls = uiState.preview?.imageUrls?.split("|||")?.filter { it.isNotEmpty() } ?: emptyList()
    val liveVideoUrls = uiState.preview?.liveVideoUrls?.split("|||")?.filter { it.isNotEmpty() } ?: emptyList()
    val cookieManager = remember { CookieManager(context.applicationContext) }

    // null = 不弹窗, "cover" = 全部封面, "video" = 下载Live动态短片
    var batchConfirmTarget by remember { mutableStateOf<String?>(null) }

    if (batchConfirmTarget != null) {
        BatchDownloadConfirmDialog(
            onConfirm = {
                when (batchConfirmTarget) {
                    "cover" -> viewModel.downloadLivePhoto()
                    "video" -> viewModel.downloadLiveVideo()
                }
                batchConfirmTarget = null
            },
            onDismiss = { batchConfirmTarget = null },
        )
    }

    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.exitSelectionMode()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝与否不影响功能 */ }
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

        // 选择模式顶部栏
        if (uiState.isSelectionMode) {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "已选择 ${uiState.selectedLiveIndices.size} 项",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.textPrimary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { viewModel.selectAllLive(imageUrls.size) }) {
                            Icon(
                                Icons.Default.SelectAll,
                                contentDescription = "全选",
                                tint = tokens.textPrimary
                            )
                        }
                        IconButton(onClick = { viewModel.exitSelectionMode() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "取消",
                                tint = tokens.textPrimary
                            )
                        }
                    }
                }
            }
        } else {
            Text(
                text = stringResource(R.string.tab_live),
                style = MaterialTheme.typography.headlineMedium,
                color = tokens.textPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }

        if (!uiState.isSelectionMode) {
            NeumorphicInputField(
                value = uiState.url,
                onValueChange = { viewModel.updateUrl(it) },
                placeholder = stringResource(R.string.hint_input_url),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            NeumorphicButton(
                text = stringResource(R.string.btn_parse),
                onClick = {
                    if (!cookieManager.isSet()) {
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
        }

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
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = error,
                    fontSize = 14.sp,
                    color = Color(0xFFEF4444)
                )
            }
        }

        uiState.preview?.let { preview ->
            if (uiState.isLivePhoto) {
                val liveCount = liveVideoUrls.size

                if (uiState.isSelectionMode) {
                    // 选择模式：显示网格
                    LiveSelectionGrid(
                        imageUrls = imageUrls,
                        selectedIndices = uiState.selectedLiveIndices,
                        onToggle = { viewModel.toggleLiveSelection(it) },
                        onPreview = { index ->
                            val intent = Intent(context, LivePhotoPreviewActivity::class.java).apply {
                                putExtra("image_urls", preview.imageUrls)
                                putExtra("video_urls", preview.liveVideoUrls)
                                putExtra("cover_urls", preview.liveCoverUrls)
                                putExtra("current_index", index)
                            }
                            context.startActivity(intent)
                        }
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        NeumorphicButton(
                            text = "下载所选封面 (${uiState.selectedLiveIndices.size})",
                            onClick = { viewModel.downloadSelectedLivePhotos() },
                            enabled = uiState.selectedLiveIndices.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        )
                        NeumorphicButton(
                            text = "下载所选视频 (${uiState.selectedLiveIndices.size})",
                            onClick = { viewModel.downloadSelectedLiveVideos() },
                            enabled = uiState.selectedLiveIndices.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                            isPrimary = false
                        )
                    }
                } else {
                    // 预览模式
                    Spacer(modifier = Modifier.height(12.dp))

                    LivePhotoPreviewCard(
                        authorName = preview.authorName,
                        authorAvatarUrl = preview.authorAvatarUrl,
                        description = preview.description,
                        liveCount = liveCount,
                        imageUrls = preview.imageUrls,
                        onPreviewClick = { index ->
                            val intent = Intent(context, LivePhotoPreviewActivity::class.java).apply {
                                putExtra("image_urls", preview.imageUrls)
                                putExtra("video_urls", preview.liveVideoUrls)
                                putExtra("cover_urls", preview.liveCoverUrls)
                                putExtra("current_index", index)
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    if (liveCount > 1) {
                        Text(
                            text = "共 $liveCount 张 Live 图",
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.textSecondary,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        NeumorphicButton(
                            text = "选择",
                            onClick = { viewModel.toggleSelectionMode() },
                            modifier = Modifier.weight(1f),
                            isPrimary = true,
                        )
                        NeumorphicButton(
                            text = "下载全部封面",
                            onClick = { batchConfirmTarget = "cover" },
                            modifier = Modifier.weight(1f),
                            isPrimary = false,
                            isDanger = true,
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    NeumorphicButton(
                        text = stringResource(R.string.btn_download_live_video),
                        onClick = { batchConfirmTarget = "video" },
                        modifier = Modifier.fillMaxWidth(),
                        isPrimary = false,
                        isDanger = true,
                    )
                }
            }
        }

        // 旧的 GlassCard 进度条已移除，改用函数末尾的批处理弹窗。
    }

    val session = uiState.batch
    if (session != null && session.active && !session.dialogDismissed) {
        BatchDownloadProgressDialog(
            session = session,
            onBackground = { viewModel.dismissBatchDialog() },
        )
    }
}

@Composable
private fun LiveSelectionGrid(
    imageUrls: List<String>,
    selectedIndices: Set<Int>,
    onToggle: (Int) -> Unit,
    onPreview: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        imageUrls.forEachIndexed { index, url ->
            val isSelected = index in selectedIndices
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) Color(0xFF3B82F6) else Color.Gray.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable { onToggle(index) }
            ) {
                AsyncImage(
                    model = url,
                    contentDescription = "Live 图 ${index + 1}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                // 播放预览图标
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f))
                        .clickable { onPreview(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "预览",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // 选中标记
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) Color(0xFF3B82F6) else Color.White.copy(alpha = 0.7f))
                        .border(2.dp, if (isSelected) Color(0xFF3B82F6) else Color.Gray, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Text(
                            text = "${index + 1}",
                            fontSize = 12.sp,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
