@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.xianyu.vediodownload.ui.screens

import android.media.MediaMetadataRetriever
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.xianyu.vediodownload.R
import com.xianyu.vediodownload.ui.designsystem.component.GlassCard
import com.xianyu.vediodownload.ui.designsystem.component.NeumorphicButton
import com.xianyu.vediodownload.ui.designsystem.theme.AppSpacing
import com.xianyu.vediodownload.ui.designsystem.theme.LocalGlassTokens
import com.xianyu.vediodownload.ui.designsystem.theme.glassConvex
import com.xianyu.vediodownload.util.CacheManager
import com.xianyu.vediodownload.util.toReadableSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.Locale

/**
 * 历史记录页：直接扫描本地下载目录，渲染列表，支持长按/全选删除。
 *
 * 物理目录结构（统一根目录为 Pictures/DouyinDownloader/）：
 *   - Movies/                 视频下载保存处
 *   - Pictures/               图片下载 / Live静态封面保存处
 *   - LivePicturesMovies/     Live动态短片保存处
 *
 * - Android 10+ (Q)：通过 MediaStore 按 RELATIVE_PATH LIKE 查询 + 删除（ContentResolver.delete 会同时清理 MediaStore 与物理文件）。
 * - Android 9-：直接走 Environment.getExternalStoragePublicDirectory(Pictures)/DouyinDownloader/ 下的子目录。
 *
 * 视频/图片/Live 三类各自扫各自的子目录，绝不混淆：
 *   - 视频下载记录  → Movies/
 *   - 图片下载记录  → Pictures/
 *   - Live 下载记录 → Pictures/(静态封面) + LivePicturesMovies/(动态短片)，按文件名（去后缀）配对
 */

enum class HistoryCategory(
    val rootSubdir: String,
    val mediaStoreRelativePath: String,
    val fileSystemChild: String,
) {
    VIDEO("Movies", "Movies", "Movies"),
    IMAGE("Pictures", "Pictures", "Pictures"),
    LIVE_IMAGE("Pictures", "Pictures", "Pictures"),
    LIVE_VIDEO("LivePicturesMovies", "LivePicturesMovies", "LivePicturesMovies"),
}

data class HistoryItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateAddedMillis: Long,
    val sizeBytes: Long,
    val category: HistoryCategory,
)

data class LivePhotoPair(
    val baseName: String,
    val image: HistoryItem?,
    val video: HistoryItem?,
) {
    val cover: HistoryItem? get() = image ?: video
}

private const val ALBUM_NAME = "DouyinDownloader"

private fun HistoryItem.creationTimeMillis(): Long = dateAddedMillis * 1000L

// ---------- 主入口：历史记录主页 ----------

@Composable
fun HistoryHomeScreen(
    onBack: () -> Unit,
    onOpenCategory: (HistoryCategory) -> Unit,
    onOpenLivePhotos: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showClearConfirm by remember { mutableStateOf(false) }

    if (showClearConfirm) {
        HistoryConfirmDialog(
            title = stringResource(R.string.history_delete_confirm_clear_all_title),
            message = stringResource(R.string.history_delete_confirm_clear_all_msg),
            onConfirm = {
                showClearConfirm = false
                scope.launch {
                    val freedBytes = withContext(Dispatchers.IO) {
                        CacheManager.clearAllDownloads(context)
                    }
                    if (freedBytes > 0L) {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(
                                R.string.history_deleted_toast,
                                freedBytes.toReadableSize(),
                            ),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            onDismiss = { showClearConfirm = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.safeHorizontal)
            .padding(bottom = 100.dp)
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        HistoryTopBar(title = stringResource(R.string.history_home_title), onBack = onBack)

        Spacer(modifier = Modifier.height(4.dp))

        HistoryOptionRow(
            icon = Icons.Filled.PlayCircle,
            title = stringResource(R.string.history_option_video),
            onClick = { onOpenCategory(HistoryCategory.VIDEO) },
        )

        Spacer(modifier = Modifier.height(12.dp))

        HistoryOptionRow(
            icon = Icons.Filled.PhotoLibrary,
            title = stringResource(R.string.history_option_image),
            onClick = { onOpenCategory(HistoryCategory.IMAGE) },
        )

        Spacer(modifier = Modifier.height(12.dp))

        HistoryOptionRow(
            icon = Icons.Filled.PhotoCamera,
            title = stringResource(R.string.history_option_live),
            onClick = onOpenLivePhotos,
        )

        Spacer(modifier = Modifier.height(24.dp))

        NeumorphicButton(
            text = stringResource(R.string.history_option_clear_all),
            onClick = { showClearConfirm = true },
            modifier = Modifier.fillMaxWidth(),
            isPrimary = false,
            isDanger = true,
        )
    }
}

@Composable
private fun HistoryTopBar(title: String, onBack: () -> Unit) {
    val tokens = LocalGlassTokens.current
    val backInteractionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .clickable(
                    interactionSource = backInteractionSource,
                    indication = androidx.compose.material.ripple.rememberRipple(
                        bounded = false,
                        radius = 24.dp,
                        color = Color(0xFF3B82F6).copy(alpha = 0.2f),
                    ),
                    onClick = onBack,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.ArrowBack,
                contentDescription = "返回",
                tint = tokens.textPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = tokens.textPrimary,
        )
    }
}

@Composable
private fun HistoryOptionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    val tokens = LocalGlassTokens.current
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassConvex(cornerRadius = AppSpacing.cardCornerRadius)
            .clip(RoundedCornerShape(AppSpacing.cardCornerRadius))
            .clickable(
                interactionSource = interactionSource,
                indication = rememberRipple(color = Color(0xFF3B82F6).copy(alpha = 0.1f)),
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = Color(0xFF3B82F6).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFF60A5FA),
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = tokens.textPrimary,
        )
    }
}

// ---------- 视频/图片列表 ----------

@Composable
fun HistoryListScreen(
    title: String,
    category: HistoryCategory,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val tokens = LocalGlassTokens.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val items = remember { mutableStateListOf<HistoryItem>() }
    var multiSelect by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }

    LaunchedEffect(category) {
        loading = true
        error = null
        items.clear()
        selected.clear()
        multiSelect = false
        try {
            val loaded = withContext(Dispatchers.IO) { scanCategory(context, category) }
            items.addAll(loaded)
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
        } finally {
            loading = false
        }
    }

    val allSelected by derivedStateOf { items.isNotEmpty() && items.all { it.id in selected } }
    var pendingDelete by remember { mutableStateOf(false) }

    if (pendingDelete) {
        HistoryConfirmDialog(
            title = stringResource(R.string.history_delete_confirm_title),
            message = stringResource(R.string.history_delete_confirm_msg, selected.size),
            onConfirm = {
                pendingDelete = false
                val toDelete = items.filter { it.id in selected }
                scope.launch {
                    val deletedCount = withContext(Dispatchers.IO) {
                        deleteItems(context, toDelete)
                    }
                    items.removeAll { it.id in selected }
                    selected.clear()
                    multiSelect = false
                    if (deletedCount > 0) {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.history_deleted_toast, deletedCount),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            onDismiss = { pendingDelete = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AppSpacing.safeHorizontal)
            .padding(bottom = 100.dp)
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        HistoryTopBar(title = title, onBack = onBack)

        AnimatedVisibility(visible = multiSelect) {
            MultiSelectBar(
                allSelected = allSelected,
                selectedCount = selected.size,
                onToggleSelectAll = {
                    if (allSelected) {
                        selected.clear()
                    } else {
                        // 仅补齐尚未选中的项，避免手动选了部分后再次全选导致重复追加。
                        items.forEach { item ->
                            if (item.id !in selected) selected.add(item.id)
                        }
                    }
                },
                onDelete = { pendingDelete = true },
                onExit = {
                    multiSelect = false
                    selected.clear()
                },
                tokens = tokens,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        when {
            loading -> HistoryLoading()
            error != null -> GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.history_loading_failed, error ?: ""),
                    color = Color(0xFFEF4444),
                    fontSize = 14.sp,
                )
            }
            items.isEmpty() -> GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.history_empty),
                    color = tokens.textSecondary,
                    fontSize = 14.sp,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(items = items, key = { it.id }) { item ->
                    HistoryListRow(
                        item = item,
                        isMultiSelect = multiSelect,
                        isSelected = selected.contains(item.id),
                        onClick = {
                            if (multiSelect) {
                                toggleSelection(selected, item.id)
                            } else {
                                openExternal(context, item.uri, item.displayName)
                            }
                        },
                        onLongClick = {
                            if (!multiSelect) {
                                multiSelect = true
                                selected.add(item.id)
                            } else {
                                toggleSelection(selected, item.id)
                            }
                        },
                    )
                }
            }
        }
    }
}

// ---------- Live 动态短片列表 ----------

@Composable
fun HistoryLivePhotoListScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val tokens = LocalGlassTokens.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val pairs = remember { mutableStateListOf<LivePhotoPair>() }
    var multiSelect by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        loading = true
        error = null
        pairs.clear()
        selected.clear()
        multiSelect = false
        try {
            val loaded = withContext(Dispatchers.IO) { scanLivePhotoPairs(context) }
            pairs.addAll(loaded)
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
        } finally {
            loading = false
        }
    }

    val allSelected by derivedStateOf { pairs.isNotEmpty() && pairs.all { it.baseName in selected } }
    var pendingDelete by remember { mutableStateOf(false) }

    if (pendingDelete) {
        HistoryConfirmDialog(
            title = stringResource(R.string.history_delete_confirm_title),
            message = stringResource(R.string.history_delete_confirm_msg, selected.size * 2),
            onConfirm = {
                pendingDelete = false
                val toDelete = pairs.filter { it.baseName in selected }
                scope.launch {
                    val deletedCount = withContext(Dispatchers.IO) {
                        toDelete.sumOf { p ->
                            deleteItems(context, listOfNotNull(p.image, p.video))
                        }
                    }
                    pairs.removeAll { it.baseName in selected }
                    selected.clear()
                    multiSelect = false
                    if (deletedCount > 0) {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.history_deleted_toast, deletedCount),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            onDismiss = { pendingDelete = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AppSpacing.safeHorizontal)
            .padding(bottom = 100.dp)
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        HistoryTopBar(
            title = stringResource(R.string.history_list_title_live),
            onBack = onBack,
        )

        AnimatedVisibility(visible = multiSelect) {
            MultiSelectBar(
                allSelected = allSelected,
                selectedCount = selected.size,
                onToggleSelectAll = {
                    if (allSelected) {
                        selected.clear()
                    } else {
                        // 仅补齐尚未选中的项，避免手动选了部分后再次全选导致重复追加。
                        pairs.forEach { pair ->
                            if (pair.baseName !in selected) selected.add(pair.baseName)
                        }
                    }
                },
                onDelete = { pendingDelete = true },
                onExit = {
                    multiSelect = false
                    selected.clear()
                },
                tokens = tokens,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        when {
            loading -> HistoryLoading()
            error != null -> GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.history_loading_failed, error ?: ""),
                    color = Color(0xFFEF4444),
                    fontSize = 14.sp,
                )
            }
            pairs.isEmpty() -> GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.history_empty),
                    color = tokens.textSecondary,
                    fontSize = 14.sp,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(items = pairs, key = { it.baseName }) { pair ->
                    val video = pair.video
                    LivePhotoRow(
                        pair = pair,
                        isMultiSelect = multiSelect,
                        isSelected = selected.contains(pair.baseName),
                        onClick = {
                            if (multiSelect) {
                                toggleSelection(selected, pair.baseName)
                            } else if (video != null) {
                                openExternal(context, video.uri, video.displayName)
                            }
                        },
                        onLongClick = {
                            if (!multiSelect) {
                                multiSelect = true
                                selected.add(pair.baseName)
                            } else {
                                toggleSelection(selected, pair.baseName)
                            }
                        },
                    )
                }
            }
        }
    }
}

// ---------- 共享 UI 部件 ----------

@Composable
private fun MultiSelectBar(
    allSelected: Boolean,
    selectedCount: Int,
    onToggleSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onExit: () -> Unit,
    tokens: com.xianyu.vediodownload.ui.designsystem.theme.GlassTokens,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onToggleSelectAll) {
            Text(
                text = if (allSelected) stringResource(R.string.history_deselect_all)
                else stringResource(R.string.history_select_all),
                color = Color(0xFF60A5FA),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onDelete, enabled = selectedCount > 0) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                tint = if (selectedCount == 0) tokens.textTertiary else Color(0xFFEF4444),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.history_delete_selected, selectedCount),
                color = if (selectedCount > 0) Color(0xFFEF4444) else tokens.textTertiary,
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        TextButton(onClick = onExit) {
            Text(text = stringResource(R.string.history_action_exit_multi))
        }
    }
}

@Composable
private fun HistoryLoading() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            color = Color(0xFF60A5FA),
            strokeWidth = 2.dp,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.history_loading),
            fontSize = 14.sp,
            color = LocalGlassTokens.current.textSecondary,
        )
    }
}

@Composable
private fun HistoryConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalGlassTokens.current
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = Color(0xF2FFFFFF),
        tonalElevation = 0.dp,
        properties = DialogProperties(
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
            Text(
                text = message,
                color = tokens.textSecondary,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
        },
        // 按钮位置：左侧确认（危险）、右侧取消（普通）。
        confirmButton = {
            NeumorphicButton(
                text = stringResource(R.string.history_action_cancel),
                onClick = onDismiss,
                isPrimary = true,
            )
        },
        dismissButton = {
            NeumorphicButton(
                text = stringResource(R.string.history_action_confirm),
                onClick = onConfirm,
                isDanger = true,
            )
        },
    )
}

@Composable
private fun HistoryListRow(
    item: HistoryItem,
    isMultiSelect: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val tokens = LocalGlassTokens.current
    val interactionSource = remember { MutableInteractionSource() }
    val dateStr = remember(item.dateAddedMillis) {
        formatDateTime(item.creationTimeMillis())
    }
    val sizeStr = remember(item.sizeBytes) { formatSize(item.sizeBytes) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassConvex(cornerRadius = AppSpacing.cardCornerRadius)
            .clip(RoundedCornerShape(AppSpacing.cardCornerRadius))
            .background(
                if (isSelected) Color(0xFF3B82F6).copy(alpha = 0.15f) else Color.Transparent,
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = rememberRipple(color = Color(0xFF3B82F6).copy(alpha = 0.1f)),
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isMultiSelect) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() },
                colors = CheckboxDefaults.colors(
                    checkedColor = Color(0xFF60A5FA),
                    uncheckedColor = tokens.textTertiary,
                ),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x30000000)),
            contentAlignment = Alignment.Center,
        ) {
            if (item.category == HistoryCategory.VIDEO) {
                VideoThumbnail(
                    uri = item.uri,
                    modifier = Modifier.fillMaxSize(),
                )
                Icon(
                    imageVector = Icons.Filled.PlayCircle,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(28.dp),
                )
            } else {
                AsyncImage(
                    model = item.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayName,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = dateStr,
                fontSize = 12.sp,
                color = tokens.textSecondary,
            )
            Text(
                text = sizeStr,
                fontSize = 12.sp,
                color = tokens.textTertiary,
            )
        }
    }
}

@Composable
private fun LivePhotoRow(
    pair: LivePhotoPair,
    isMultiSelect: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val tokens = LocalGlassTokens.current
    val interactionSource = remember { MutableInteractionSource() }
    val cover = pair.cover ?: pair.video ?: return
    val dateStr = remember(cover.dateAddedMillis) {
        formatDateTime(cover.creationTimeMillis())
    }
    val sizeStr = remember(pair.video?.sizeBytes ?: 0L) {
        formatSize((pair.video?.sizeBytes ?: 0L) + (pair.image?.sizeBytes ?: 0L))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassConvex(cornerRadius = AppSpacing.cardCornerRadius)
            .clip(RoundedCornerShape(AppSpacing.cardCornerRadius))
            .background(
                if (isSelected) Color(0xFF3B82F6).copy(alpha = 0.15f) else Color.Transparent,
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = rememberRipple(color = Color(0xFF3B82F6).copy(alpha = 0.1f)),
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isMultiSelect) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() },
                colors = CheckboxDefaults.colors(
                    checkedColor = Color(0xFF60A5FA),
                    uncheckedColor = tokens.textTertiary,
                ),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x30000000)),
            contentAlignment = Alignment.Center,
        ) {
            val img = pair.image
            if (img != null) {
                AsyncImage(
                    model = img.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                pair.video?.let { v ->
                    VideoThumbnail(
                        uri = v.uri,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .background(
                        color = Color(0xFFFE2C55),
                        shape = RoundedCornerShape(6.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PhotoCamera,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = cover.displayName,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = dateStr,
                fontSize = 12.sp,
                color = tokens.textSecondary,
            )
            Text(
                text = "$sizeStr · ${stringResource(R.string.history_live_subtype)}",
                fontSize = 12.sp,
                color = tokens.textTertiary,
            )
        }
    }
}

// ---------- 扫描 / 删除逻辑 ----------

/**
 * 统一根目录：Pictures/DouyinDownloader/
 * 按用户需求三类各自扫各自的子目录，绝不混淆。
 */
private fun rootBaseDir(): File = File(
    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
    ALBUM_NAME,
)

private fun rootMediaStorePrefix(): String =
    "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME/"

/** 按类别扫对应子目录。 */
private fun scanCategory(context: Context, category: HistoryCategory): List<HistoryItem> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        scanViaMediaStore(context, category)
    } else {
        scanViaFileSystem(category)
    }
}

/**
 * Live 下载记录：只扫 Movies/DouyinDownloader/LivePicturesMovies/ 下的动态短片。
 * 封面（如果有下载且同名）作为可选项填充，不强制配对。
 */
private fun scanLivePhotoPairs(context: Context): List<LivePhotoPair> {
    val videos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        scanViaMediaStore(context, HistoryCategory.LIVE_VIDEO)
    } else {
        scanViaFileSystem(HistoryCategory.LIVE_VIDEO)
    }.sortedByDescending { it.dateAddedMillis }

    val imagesByBase = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        scanViaMediaStore(context, HistoryCategory.LIVE_IMAGE)
    } else {
        scanViaFileSystem(HistoryCategory.LIVE_IMAGE)
    }.associateBy { stripExt(it.displayName) }

    return videos.map { video ->
        LivePhotoPair(
            baseName = stripExt(video.displayName),
            image = imagesByBase[stripExt(video.displayName)],
            video = video,
        )
    }
}

private fun extOf(name: String): String =
    name.substringAfterLast('.', "").lowercase(Locale.ROOT)

private fun stripExt(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot <= 0) name else name.substring(0, dot)
}

private fun scanViaMediaStore(context: Context, category: HistoryCategory): List<HistoryItem> {
    val collection = when (category) {
        HistoryCategory.VIDEO, HistoryCategory.LIVE_VIDEO ->
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        HistoryCategory.IMAGE, HistoryCategory.LIVE_IMAGE ->
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }

    val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.RELATIVE_PATH,
    )

    val relativePrefix = when (category) {
        HistoryCategory.VIDEO ->
            "${Environment.DIRECTORY_MOVIES}/DouyinDownloader/%"
        HistoryCategory.LIVE_VIDEO ->
            "${Environment.DIRECTORY_MOVIES}/DouyinDownloader/${category.mediaStoreRelativePath}/%"
        else ->
            "${rootMediaStorePrefix()}${category.mediaStoreRelativePath}/%"
    }

    // VIDEO 列表严格限制在 "Movies/DouyinDownloader/ 直接子层" 上，把
    // Movies/DouyinDownloader/LivePicturesMovies/ 这种 Live 动态短片子目录排除。
    // 不能用 "%/%" 配多级：Movies/DouyinDownloader/ 自身带尾斜杠就会被它一刀切掉。
    // 这里改成"RELATIVE_PATH 等于 Movies/DouyinDownloader/ 或以 Movies/DouyinDownloader/xxx.mp4 形式结尾"，
    // 即排除包含 LivePicturesMovies/ 的路径。
    val videoNotLike = "${Environment.DIRECTORY_MOVIES}/DouyinDownloader/${HistoryCategory.LIVE_VIDEO.mediaStoreRelativePath}/%"

    val out = mutableListOf<HistoryItem>()
    context.contentResolver.query(
        collection,
        projection,
        if (category == HistoryCategory.VIDEO) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE ?"
        } else {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        },
        if (category == HistoryCategory.VIDEO) arrayOf(relativePrefix, videoNotLike)
        else arrayOf(relativePrefix),
        "${MediaStore.MediaColumns.DATE_ADDED} DESC",
    )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val name = cursor.getString(nameCol) ?: continue
            val date = cursor.getLong(dateCol)
            val size = cursor.getLong(sizeCol)
            val uri = ContentUris.withAppendedId(collection, id)
            out.add(HistoryItem(id, uri, name, date, size, category))
        }
    }
    return out
}

private fun scanViaFileSystem(category: HistoryCategory): List<HistoryItem> {
    // 视频类在 Movies/DouyinDownloader/ 下，图片类在 Pictures/DouyinDownloader/ 下
    val dir: File = if (category == HistoryCategory.VIDEO) {
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "DouyinDownloader",
        )
    } else if (category == HistoryCategory.LIVE_VIDEO) {
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "DouyinDownloader/LivePicturesMovies",
        )
    } else {
        File(rootBaseDir(), category.fileSystemChild)
    }
    if (!dir.exists()) return emptyList()
    val files = dir.listFiles() ?: return emptyList()
    return files
        .filter { it.isFile }
        .sortedByDescending { it.lastModified() }
        .mapIndexed { index, file ->
            HistoryItem(
                id = file.lastModified() * 1000L + index,
                uri = Uri.fromFile(file),
                displayName = file.name,
                dateAddedMillis = file.lastModified() / 1000L,
                sizeBytes = file.length(),
                category = category,
            )
        }
}

private fun deleteItems(context: Context, items: List<HistoryItem>): Int {
    if (items.isEmpty()) return 0
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        items.count { item ->
            try {
                context.contentResolver.delete(item.uri, null, null) > 0
            } catch (_: Throwable) {
                false
            }
        }
    } else {
        var n = 0
        for (item in items) {
            try {
                val file = item.uri.path?.let { File(it) }
                if (file != null && file.exists() && file.delete()) n++
            } catch (_: Throwable) {
                // ignore
            }
        }
        n
    }
}

private fun openExternal(context: Context, uri: Uri, displayName: String? = null) {
    val mime = guessMimeType(displayName ?: uri.toString())
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, null).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(chooser)
    } catch (_: Throwable) {
        // 外部应用缺失时不强提示
    }
}

private fun guessMimeType(nameOrUri: String): String {
    val ext = extOf(nameOrUri.substringBefore('?'))
    return when (ext) {
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        else -> "*/*"
    }
}

private fun toggleSelection(list: SnapshotStateList<Long>, id: Long) {
    if (list.contains(id)) list.remove(id) else list.add(id)
}

private fun toggleSelection(list: SnapshotStateList<String>, id: String) {
    if (list.contains(id)) list.remove(id) else list.add(id)
}

private fun formatDateTime(millis: Long): String {
    if (millis <= 0L) return "—"
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    return String.format(
        Locale.getDefault(),
        "%d年%d月%d日 %d:%02d",
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH) + 1,
        cal.get(Calendar.DAY_OF_MONTH),
        cal.get(Calendar.HOUR_OF_DAY),
        cal.get(Calendar.MINUTE),
    )
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.getDefault(), "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.getDefault(), "%.1f MB", mb)
        kb >= 1.0 -> String.format(Locale.getDefault(), "%.1f KB", kb)
        else -> "$bytes B"
    }
}

// ---------- 视频缩略图 ----------

/** 抽帧缓存：uri.toString -> Bitmap（最多 64 张，进程级）。避免列表滚动时反复解码。 */
private val videoThumbnailCache = object : androidx.collection.LruCache<String, android.graphics.Bitmap>(64) {}

@Composable
private fun VideoThumbnail(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val key = uri.toString()
    val cached = remember(key) { videoThumbnailCache.get(key) }

    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = cached, key1 = key) {
        if (value != null) return@produceState
        val bmp = withContext(Dispatchers.IO) { extractVideoFrame(context, uri) }
        if (bmp != null) videoThumbnailCache.put(key, bmp)
        value = bmp
    }

    val display = bitmap
    if (display != null) {
        androidx.compose.foundation.Image(
            bitmap = display.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }
}

private fun extractVideoFrame(context: Context, uri: Uri): android.graphics.Bitmap? {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(context, uri)
        r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (_: Throwable) {
        null
    } finally {
        try { r.release() } catch (_: Throwable) { }
    }
}