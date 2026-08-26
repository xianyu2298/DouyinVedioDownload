package com.xianyu.vediodownload.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.xianyu.vediodownload.ui.designsystem.component.*
import com.xianyu.vediodownload.ui.designsystem.theme.*

@Composable
fun LivePhotoComposeScreen(
    viewModel: LivePhotoComposeViewModel = viewModel(
        factory = LivePhotoComposeViewModelFactory(
            (LocalContext.current.applicationContext as android.app.Application)
        )
    )
) {
    LivePhotoComposeContent(
        viewModel = viewModel,
        showBackButton = false,
        onBack = {}
    )
}

@Composable
fun LivePhotoComposeScreenContent(
    onBack: () -> Unit
) {
    val viewModel: LivePhotoComposeViewModel = viewModel(
        factory = LivePhotoComposeViewModelFactory(
            (LocalContext.current.applicationContext as android.app.Application)
        )
    )
    LivePhotoComposeContent(
        viewModel = viewModel,
        showBackButton = true,
        onBack = onBack
    )
}

@Composable
private fun LivePhotoComposeContent(
    viewModel: LivePhotoComposeViewModel,
    showBackButton: Boolean,
    onBack: () -> Unit
) {
    val scrollState = rememberScrollState()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val tokens = LocalGlassTokens.current

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        viewModel.setVideoUri(uri)
    }

    val coverPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        viewModel.setCoverUri(uri)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 忽略结果 */ }

    val hasStoragePermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            true
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    LaunchedEffect(Unit) {
        if (!hasStoragePermission && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = AppSpacing.safeHorizontal)
            .padding(bottom = 100.dp)
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        if (showBackButton) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        tint = tokens.textPrimary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "返回设置",
                    fontSize = 14.sp,
                    color = tokens.textSecondary
                )
            }
        }

        Text(
            text = "Live 图合成",
            style = MaterialTheme.typography.headlineMedium,
            color = tokens.textPrimary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "将视频和图片合成为 iPhone/Android 可识别的 Live Photo",
            fontSize = 14.sp,
            color = tokens.textSecondary,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // 视频选择区域
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.VideoLibrary,
                        contentDescription = null,
                        tint = Color(0xFF3B82F6),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "视频文件",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.textPrimary
                    )
                    Text(
                        text = " *",
                        fontSize = 16.sp,
                        color = Color(0xFFEF4444)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (uiState.videoUri != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(tokens.textSecondary.copy(alpha = 0.1f))
                            .border(2.dp, Color(0xFF3B82F6), RoundedCornerShape(12.dp))
                            .clickable { videoPickerLauncher.launch("video/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF3B82F6),
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "已选择视频",
                                fontSize = 14.sp,
                                color = tokens.textPrimary
                            )
                            Text(
                                text = "点击重新选择",
                                fontSize = 12.sp,
                                color = tokens.textSecondary
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(tokens.textSecondary.copy(alpha = 0.1f))
                            .border(2.dp, tokens.textTertiary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .clickable { videoPickerLauncher.launch("video/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                tint = tokens.textTertiary,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "点击选择视频",
                                fontSize = 14.sp,
                                color = tokens.textTertiary
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 封面选择区域
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = null,
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "封面图片",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.textPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "(可选)",
                        fontSize = 12.sp,
                        color = tokens.textTertiary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "不选则自动从视频第1秒提取帧",
                    fontSize = 12.sp,
                    color = tokens.textTertiary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (uiState.coverUri != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(uiState.coverUri)
                                .crossfade(true)
                                .build(),
                            contentDescription = "已选封面",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .weight(1f)
                                .height(100.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(2.dp, Color(0xFF10B981), RoundedCornerShape(12.dp))
                                .clickable { coverPickerLauncher.launch("image/*") }
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(100.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(tokens.textSecondary.copy(alpha = 0.1f))
                                .border(2.dp, tokens.textTertiary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .clickable { coverPickerLauncher.launch("image/*") },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    tint = tokens.textTertiary,
                                    modifier = Modifier.size(32.dp)
                                )
                                Text(
                                    text = "选择图片",
                                    fontSize = 12.sp,
                                    color = tokens.textTertiary
                                )
                            }
                        }
                    }

                    if (uiState.coverUri != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFEF4444).copy(alpha = 0.1f))
                                .clickable { viewModel.setCoverUri(null) },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = "清除封面",
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "清除",
                                    fontSize = 10.sp,
                                    color = Color(0xFFEF4444)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 质量设置
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = null,
                        tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "输出质量",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.textPrimary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                ComposeQuality.entries.forEach { quality ->
                    val isSelected = uiState.quality == quality
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) Color(0xFF3B82F6).copy(alpha = 0.1f)
                                else Color.Transparent
                            )
                            .border(
                                1.dp,
                                if (isSelected) Color(0xFF3B82F6) else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { viewModel.setQuality(quality) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { viewModel.setQuality(quality) },
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF3B82F6))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = quality.displayName,
                            fontSize = 14.sp,
                            color = tokens.textPrimary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 进度显示
        if (uiState.isComposing) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = uiState.progressMessage,
                        fontSize = 14.sp,
                        color = tokens.textSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = uiState.progress / 100f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = Color(0xFF3B82F6),
                        trackColor = Color(0xFF3B82F6).copy(alpha = 0.2f),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${uiState.progress}%",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.textPrimary
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 错误提示
        uiState.error?.let { error ->
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = error,
                        fontSize = 14.sp,
                        color = Color(0xFFEF4444),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.clearError() }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = tokens.textTertiary
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 成功提示
        if (uiState.successPath != null) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Live 图合成成功！",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF10B981)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "已保存到相册 > LivePhotos",
                        fontSize = 13.sp,
                        color = tokens.textSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    NeumorphicButton(
                        text = "继续合成",
                        onClick = {
                            viewModel.clearSuccess()
                            viewModel.setVideoUri(null)
                            viewModel.setCoverUri(null)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 合成按钮
        NeumorphicButton(
            text = if (uiState.isComposing) "合成中..." else "开始合成 Live 图",
            onClick = { viewModel.startCompose() },
            enabled = uiState.videoUri != null && !uiState.isComposing,
            modifier = Modifier.fillMaxWidth(),
            isPrimary = true
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 使用说明
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = tokens.textTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "使用说明",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.textPrimary
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "1. 选择一个视频文件（必选）\n" +
                            "2. 可选：选择一张图片作为封面，不选则自动从视频第1秒提取\n" +
                            "3. 选择输出质量（推荐使用均衡模式）\n" +
                            "4. 点击「开始合成」等待完成\n" +
                            "5. 合成后的 Live 图保存在相册 > LivePhotos",
                    fontSize = 13.sp,
                    color = tokens.textSecondary,
                    lineHeight = 22.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
