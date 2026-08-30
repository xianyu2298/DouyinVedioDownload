package com.xianyu.vediodownload.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import android.widget.Toast
import coil.compose.AsyncImage
import com.xianyu.vediodownload.R
import com.xianyu.vediodownload.douyin.AwemeProfile
import com.xianyu.vediodownload.douyin.MediaType
import com.xianyu.vediodownload.ui.designsystem.component.AuthorHeaderCard
import com.xianyu.vediodownload.ui.designsystem.component.GlassCard
import com.xianyu.vediodownload.ui.designsystem.component.GlassTopAppBar
import com.xianyu.vediodownload.ui.designsystem.component.NeumorphicButton
import com.xianyu.vediodownload.ui.designsystem.component.NeumorphicInputField
import com.xianyu.vediodownload.ui.designsystem.theme.AppSpacing
import com.xianyu.vediodownload.ui.designsystem.theme.LocalGlassTokens
import com.xianyu.vediodownload.ui.player.ImagePreviewActivity
import com.xianyu.vediodownload.ui.player.VideoPlayerActivity
import com.xianyu.vediodownload.util.CookieManager

/**
 * 作者下载屏幕 —— 在底部 tabbar 中作为第四个 tab。
 *
 * 顶层只做路由分发：
 *  1) `uiState.author == null` → [AuthorInputContent]（输入框 + 解析按钮）
 *  2) `uiState.author != null` → [AuthorDetailContent]（玻璃 TopAppBar + Header + 3 按钮 + 作品网格）
 *
 * 跨页面副作用（通知权限申请、LifecycleObserver、风控确认弹窗）挂在顶层。
 */
@OptIn(
    ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)
@Composable
fun AuthorScreen(
    viewModel: AuthorViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val cookieManager = remember { CookieManager(context.applicationContext) }

    // 通知权限（Android 13+）：批量下载开始时请求
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

    // 返回键优先级：选择模式 → 退出选择；详情页 → 退回输入页；输入页 → 系统默认（切 tab）
    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.exitSelectionMode()
    }
    BackHandler(enabled = uiState.author != null && !uiState.isSelectionMode) {
        viewModel.exitAuthorDetail()
    }

    // 风控确认弹窗（详情页操作触发，但 UI 渲染留在顶层避免被覆盖）
    val confirmTarget = uiState.confirmTarget
    if (confirmTarget != null) {
        AuthorBatchConfirmDialog(
            target = confirmTarget,
            totalWorks = uiState.works.size,
            selectedCount = uiState.selectedIndices.size,
            livePairCount = uiState.livePairCount,
            autoComposeLive = uiState.autoComposeLive,
            onToggleAutoCompose = { viewModel.setAutoComposeLive(it) },
            onConfirm = { viewModel.confirmDownload() },
            onDismiss = { viewModel.dismissConfirm() },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (uiState.author == null) {
            AuthorInputContent(
                viewModel = viewModel,
                cookieManager = cookieManager,
            )
        } else {
            AuthorDetailContent(
                viewModel = viewModel,
                onParseNew = { viewModel.exitAuthorDetail() },
            )
        }

        // 合成阶段底部小卡片（不抢原弹窗，不被 tabbar 遮挡）
        val compose = uiState.compose
        if (compose != null && compose.active) {
            ComposeFloatingCard(
                current = compose.current,
                total = compose.total,
                failed = compose.failed,
                onDismiss = { viewModel.dismissComposeCard() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .imePadding()
                    .padding(bottom = 88.dp) // 避开底部 tabbar（约 56dp）+ 一点呼吸空间
                    .padding(horizontal = 16.dp),
            )
        }
    }

    // 批量下载进度弹窗（顶层挂，确保切 tab 时也能弹）
    val session = uiState.batch
    if (session != null && session.active && !session.dialogDismissed) {
        BatchDownloadProgressDialog(
            session = session,
            onBackground = { viewModel.dismissBatchDialog() },
        )
    }
}

// ====================== 输入页：标题 + 输入框 + 解析按钮 ======================

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AuthorInputContent(
    viewModel: AuthorViewModel,
    cookieManager: CookieManager,
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val tokens = LocalGlassTokens.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = AppSpacing.safeHorizontal)
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = stringResource(R.string.tab_author),
            style = MaterialTheme.typography.headlineMedium,
            color = tokens.textPrimary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        NeumorphicInputField(
            value = uiState.url,
            onValueChange = { viewModel.updateUrl(it) },
            placeholder = "粘贴作者主页链接或任意抖音作品分享链接",
            modifier = Modifier.fillMaxWidth(),
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
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(20.dp))

        // 加载中
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
                        color = tokens.textSecondary,
                    )
                }
            }
        }

        // 错误
        uiState.error?.let { error ->
            Spacer(modifier = Modifier.height(12.dp))
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(text = error, fontSize = 14.sp, color = Color(0xFFEF4444))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ====================== 详情页：玻璃 TopAppBar + Header + 按钮 + 作品网格 ======================

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AuthorDetailContent(
    viewModel: AuthorViewModel,
    onParseNew: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val tokens = LocalGlassTokens.current
    val context = LocalContext.current

    val author = uiState.author ?: return

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            // 玻璃 TopAppBar（nickname + 返回 + 解析新作者）
            GlassTopAppBar(
                title = stringResource(R.string.tab_author),
                onBack = { viewModel.exitAuthorDetail() },
                onParseNew = onParseNew,
                modifier = Modifier.padding(horizontal = AppSpacing.safeHorizontal, vertical = 8.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppSpacing.safeHorizontal)
                    .padding(bottom = TabBarReservedBottomHeight + 16.dp) // 底部留出 tabbar + FAB 空间
            ) {
            Spacer(modifier = Modifier.height(8.dp))

            // 作者主页信息
            AuthorHeaderCard(
                nickname = author.nickname,
                avatarUrl = author.avatarUrl,
                totalWorks = author.totalWorks,
                douyinId = author.douyinId,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 两个风控按钮（选择模式下隐藏，由悬浮 FAB 接管操作）
            if (!uiState.isSelectionMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    NeumorphicButton(
                        text = "下载${author.totalWorks}个作品",
                        onClick = { viewModel.requestDownloadAll() },
                        modifier = Modifier.weight(1f),
                        isPrimary = false,
                        isDanger = true,
                    )
                    NeumorphicButton(
                        text = "仅下载视频",
                        onClick = { viewModel.requestDownloadVideoOnly() },
                        modifier = Modifier.weight(1f),
                        isPrimary = false,
                        isDanger = true,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 作品列表标题
            Text(
                text = "作品列表",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textSecondary,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // 作品网格（流式 3 列）
            AuthorWorksGrid(
                works = uiState.works,
                selectedIndices = uiState.selectedIndices,
                isSelectionMode = uiState.isSelectionMode,
                onToggle = { viewModel.toggleWorkSelection(it) },
                onPreview = { idx -> previewWork(context, uiState.works[idx]) },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(24.dp))
            }
        }

        // 悬浮下载 FAB：右下角圆形按钮，选中任一作品即出现
        if (uiState.selectedIndices.isNotEmpty()) {
            FloatingDownloadFab(
                count = uiState.selectedIndices.size,
                onClick = { viewModel.requestDownloadSelected() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .imePadding()
                    // 抬高 FAB，避开底部玻璃 tabbar
                    .padding(end = 20.dp, bottom = TabBarReservedBottomHeight),
            )
        }
    }
}

// ====================== 风控确认弹窗（针对作者批量下载定制文案） ======================

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AuthorBatchConfirmDialog(
    target: String,
    totalWorks: Int,
    selectedCount: Int,
    livePairCount: Int,
    autoComposeLive: Boolean,
    onToggleAutoCompose: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalGlassTokens.current

    val (title, body) = when (target) {
        "all" -> "提示" to "作者共有 $totalWorks 个作品，批量下载可能因抖音风控导致下载不全或失败，建议选择下载少量作品，是否继续下载全部？"
        "video" -> "提示" to "批量下载视频可能因抖音风控导致下载不全或失败，建议选择下载少量作品，是否继续仅下载视频？"
        "selected" -> "提示" to "批量下载 $selectedCount 个作品可能因抖音风控导致部分失败，是否继续？"
        else -> "提示" to "是否继续下载？"
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = Color(0xF2FFFFFF),
        tonalElevation = 0.dp,
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
        title = {
            Text(
                text = title,
                color = tokens.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                Text(
                    text = body,
                    color = tokens.textSecondary,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                )
                // 只在当前目标批次里有 Live 作品时才显示自动合成勾选框
                if (livePairCount > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleAutoCompose(!autoComposeLive) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = autoComposeLive,
                            onCheckedChange = { onToggleAutoCompose(it) },
                            colors = androidx.compose.material3.CheckboxDefaults.colors(
                                checkedColor = androidx.compose.ui.graphics.Color(0xFF1976D2),
                            ),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "下载完成后自动合成 Live 图（封面+短片，H.264 直通约 1 秒/张）",
                            color = tokens.textSecondary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        },
        dismissButton = {
            NeumorphicButton(
                text = "确定",
                onClick = onConfirm,
                isDanger = true,
            )
        },
        confirmButton = {
            NeumorphicButton(
                text = "取消",
                onClick = onDismiss,
                isPrimary = true,
            )
        },
    )
}

// ====================== 合成阶段底部小卡片（独立于下载弹窗，不抢眼） ======================

@Composable
private fun ComposeFloatingCard(
    current: Int,
    total: Int,
    failed: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalGlassTokens.current
    androidx.compose.material3.Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onDismiss() },
        shape = RoundedCornerShape(20.dp),
        color = Color(0xF2FFFFFF),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 进度小环
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.5.dp,
                color = androidx.compose.ui.graphics.Color(0xFF1976D2),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "正在合成 Live 图（$current / $total）",
                    color = tokens.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (failed > 0) {
                    Text(
                        text = "已失败 $failed 张",
                        color = androidx.compose.ui.graphics.Color(0xFFD32F2F),
                        fontSize = 12.sp,
                    )
                } else {
                    Text(
                        text = "点击查看",
                        color = tokens.textSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "关闭",
                tint = tokens.textSecondary,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onDismiss() },
            )
        }
    }
}

// ====================== 作品网格（三列） ======================

/**
 * 复用 LazyVerticalGrid 但外层已在 verticalScroll 中，所以改用 Column + Row 流式布局，
 * 每行三个 AuthorWorkItem，超出部分自动换行。这样不会与外层 verticalScroll 冲突。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AuthorWorksGrid(
    works: List<AwemeProfile>,
    selectedIndices: Set<Int>,
    isSelectionMode: Boolean,
    onToggle: (Int) -> Unit,
    onPreview: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        val rows = works.chunked(3)
        rows.forEachIndexed { rowIdx, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEachIndexed { colIdx, profile ->
                    val globalIndex = rowIdx * 3 + colIdx
                    AuthorWorkItem(
                        profile = profile,
                        isSelected = globalIndex in selectedIndices,
                        isSelectionMode = isSelectionMode,
                        onClick = { onToggle(globalIndex) },
                        onLongClick = { onPreview(globalIndex) },
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(0.75f)
                    )
                }
                // 末尾不足三个时填充空白占位
                repeat(3 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            if (rowIdx < rows.lastIndex) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AuthorWorkItem(
    profile: AwemeProfile,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current

    val cover = profile.thumbnailUrl()
    val isVideo = profile.mediaType() == MediaType.VIDEO
    val hasLive = profile.imageMediaTypes().any { it == MediaType.VIDEO }
    val showLiveTag = !isVideo && hasLive

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) Color(0xFF3B82F6) else Color.Gray.copy(alpha = 0.25f),
                shape = RoundedCornerShape(12.dp)
            )
            .combinedClickable(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
    ) {
        if (cover.isNotBlank()) {
            AsyncImage(
                model = cover,
                contentDescription = "作品封面",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
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
                Text(text = if (isVideo) "🎬" else "🖼️", fontSize = 24.sp)
            }
        }

        // 视频 / Live / 图片 类型角标
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .background(
                    color = when {
                        isVideo -> Color(0xCC000000)
                        showLiveTag -> Color(0xCCEF4444)
                        else -> Color(0xCC3B82F6)
                    },
                    shape = RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        isVideo -> Icons.Default.Videocam
                        showLiveTag -> Icons.Default.PhotoCamera
                        else -> Icons.Default.Image
                    },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(11.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = when {
                        isVideo -> "视频"
                        showLiveTag -> "Live"
                        else -> "图文"
                    },
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // 右上角选中标记
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) Color(0xFF3B82F6) else Color.White.copy(alpha = 0.75f)
                    )
                    .border(2.dp, if (isSelected) Color(0xFF3B82F6) else Color.Gray, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Text(
                        text = "○",
                        fontSize = 12.sp,
                        color = Color.White,
                    )
                }
            }
        }

        // 底部渐变 + 时长 / 张数（视频显示播放图标）
        if (isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        } else if (!isVideo) {
            val count = profile.thumbnailUrls().size.coerceAtLeast(1)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "${count}图",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

// ====================== 悬浮下载 FAB（右下角圆形） ======================

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun FloatingDownloadFab(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BadgedBox(
        badge = {
            // 选中数量徽章。count=0 时不显示，但此处只在 >0 时调用所以总是显示。
            Badge(
                containerColor = Color(0xFFEF4444),
                contentColor = Color.White,
            ) {
                Text(
                    text = if (count > 99) "99+" else count.toString(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        modifier = modifier,
    ) {
        FloatingActionButton(
            onClick = onClick,
            containerColor = Color(0xFF3B82F6),
            contentColor = Color.White,
            shape = CircleShape,
        ) {
            Icon(
                Icons.Default.Download,
                contentDescription = "下载所选 $count 个作品",
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

// ====================== 长按预览入口 ======================

private fun previewWork(context: android.content.Context, profile: AwemeProfile) {
    when (profile.mediaType()) {
        MediaType.VIDEO -> {
            val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                putExtra("video_url", profile.downloadUrls().firstOrNull() ?: "")
                putExtra("cover_url", profile.thumbnailUrl())
            }
            context.startActivity(intent)
        }
        MediaType.IMAGE -> {
            val urls = profile.thumbnailUrls().joinToString("|||")
            val intent = Intent(context, ImagePreviewActivity::class.java).apply {
                putExtra("image_urls", urls)
                putExtra("current_index", 0)
            }
            context.startActivity(intent)
        }
        MediaType.LIVE_VIDEO -> {
            // Live 视频通常伴随静态封面；直接走视频预览即可
            val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                putExtra("video_url", profile.downloadUrls().firstOrNull() ?: "")
                putExtra("cover_url", profile.thumbnailUrl())
            }
            context.startActivity(intent)
        }
        else -> {
            // 兜底：未知类型尝试按视频处理
            val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                putExtra("video_url", profile.downloadUrls().firstOrNull() ?: "")
                putExtra("cover_url", profile.thumbnailUrl())
            }
            context.startActivity(intent)
        }
    }
}
