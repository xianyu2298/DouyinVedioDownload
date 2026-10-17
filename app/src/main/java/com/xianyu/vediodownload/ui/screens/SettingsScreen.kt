package com.xianyu.vediodownload.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.xianyu.vediodownload.R
import com.xianyu.vediodownload.ui.designsystem.component.*
import com.xianyu.vediodownload.ui.designsystem.theme.*
import com.xianyu.vediodownload.util.CacheManager
import com.xianyu.vediodownload.util.CookieManager
import com.xianyu.vediodownload.util.toReadableSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页内置一个简化的子路由栈：
 *  - null → 设置主页（账号设置、下载历史、免责声明…）
 *  - SubPage.HISTORY_HOME → 历史记录主页（4 个选项 + 清空全部）
 *  - SubPage.HISTORY_VIDEO / IMAGE / LIVE → 对应分类列表
 *  - SubPage.LIVE_COMPOSE → Live 图合成页面
 */
private enum class SubPage { HISTORY_HOME, HISTORY_VIDEO, HISTORY_IMAGE, HISTORY_LIVE, HISTORY_LIVE_COMPOSED, LIVE_COMPOSE }

@Composable
fun SettingsScreen(
    onNavigateToLogin: () -> Unit
) {
    // 记录从主页到子页的方向：进入子页 slideIn 右进左退，返回时反向。
    var subPage by remember { mutableStateOf<SubPage?>(null) }
    // 用一个"深度"来粗略判断前进/返回方向：null=0, HISTORY_HOME=1, 其余=2
    val depth = when (subPage) {
        null -> 0
        SubPage.HISTORY_HOME -> 1
        else -> 2
    }
    var lastDepth by remember { mutableStateOf(0) }
    val goingForward = depth >= lastDepth
    lastDepth = depth

    // 拦截系统返回键/手势：先退回设置主页，再退回桌面。
    // 一定要在所有分支里都注册，否则当 subPage 非 null 时按返回会直接退到桌面。
    BackHandler(enabled = subPage != null) {
        subPage = when (subPage) {
            null -> null
            SubPage.HISTORY_HOME -> null
            SubPage.HISTORY_VIDEO, SubPage.HISTORY_IMAGE,
            SubPage.HISTORY_LIVE, SubPage.HISTORY_LIVE_COMPOSED -> SubPage.HISTORY_HOME
            SubPage.LIVE_COMPOSE -> null
        }
    }

    AnimatedContent(
        targetState = subPage,
        transitionSpec = {
            val duration = 240
            val forwardEnter = slideInHorizontally(tween(duration)) { full -> full / 4 } +
                fadeIn(tween(duration))
            val forwardExit = slideOutHorizontally(tween(duration)) { full -> -full / 6 } +
                fadeOut(tween(duration))
            val backEnter = slideInHorizontally(tween(duration)) { full -> -full / 6 } +
                fadeIn(tween(duration))
            val backExit = slideOutHorizontally(tween(duration)) { full -> full / 4 } +
                fadeOut(tween(duration))
            if (goingForward) {
                forwardEnter togetherWith forwardExit
            } else {
                backEnter togetherWith backExit
            }
        },
        label = "SettingsSubPage",
    ) { current ->
        when (current) {
            null -> SettingsHomeContent(
                onNavigateToLogin = onNavigateToLogin,
                onOpenHistoryHome = { subPage = SubPage.HISTORY_HOME },
                onOpenLiveCompose = { subPage = SubPage.LIVE_COMPOSE },
            )
            SubPage.HISTORY_HOME -> HistoryHomeScreen(
                onBack = { subPage = null },
                onOpenCategory = { cat ->
                    subPage = when (cat) {
                        HistoryCategory.VIDEO -> SubPage.HISTORY_VIDEO
                        HistoryCategory.IMAGE -> SubPage.HISTORY_IMAGE
                        HistoryCategory.LIVE_IMAGE, HistoryCategory.LIVE_VIDEO ->
                            SubPage.HISTORY_LIVE
                        HistoryCategory.LIVE_COMPOSED_PHOTO -> SubPage.HISTORY_LIVE_COMPOSED
                    }
                },
                onOpenLivePhotos = { subPage = SubPage.HISTORY_LIVE },
                onOpenLiveComposed = { subPage = SubPage.HISTORY_LIVE_COMPOSED },
            )
            SubPage.HISTORY_VIDEO -> HistoryListScreen(
                title = stringResource(R.string.history_list_title_video),
                category = HistoryCategory.VIDEO,
                onBack = { subPage = SubPage.HISTORY_HOME },
            )
            SubPage.HISTORY_IMAGE -> HistoryListScreen(
                title = stringResource(R.string.history_list_title_image),
                category = HistoryCategory.IMAGE,
                onBack = { subPage = SubPage.HISTORY_HOME },
            )
            SubPage.HISTORY_LIVE -> HistoryLivePhotoListScreen(
                onBack = { subPage = SubPage.HISTORY_HOME },
            )
            SubPage.HISTORY_LIVE_COMPOSED -> HistoryListScreen(
                // 直接复用 HistoryListScreen：合成 Live 图是扁平列表（非配对），用通用列表 UI 即可
                title = stringResource(R.string.history_list_title_live_composed),
                category = HistoryCategory.LIVE_COMPOSED_PHOTO,
                onBack = { subPage = SubPage.HISTORY_HOME },
            )
            SubPage.LIVE_COMPOSE -> LivePhotoComposeScreenContent(
                onBack = { subPage = null },
            )
        }
    }
}

@Composable
private fun SettingsHomeContent(
    onNavigateToLogin: () -> Unit,
    onOpenHistoryHome: () -> Unit,
    onOpenLiveCompose: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val tokens = LocalGlassTokens.current
    val cookieManager = remember(context) { CookieManager(context) }
    val coroutineScope = rememberCoroutineScope()
    var showCookieDialog by remember { mutableStateOf(false) }
    var showCheckCookieDialog by remember { mutableStateOf(false) }

    // 缓存相关状态
    var appCacheBytes by remember { mutableStateOf(0L) }
    var downloadBytes by remember { mutableStateOf(0L) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var clearDownloadsChecked by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }

    // 进入设置主页 / 弹窗关闭后都刷新一次
    suspend fun refreshCacheInfo() {
        withContext(Dispatchers.IO) {
            appCacheBytes = CacheManager.computeAppCacheBytes(context)
            downloadBytes = CacheManager.computeDownloadBytes()
        }
    }
    LaunchedEffect(showClearCacheDialog, clearing) {
        if (showClearCacheDialog || clearing) {
            // 弹窗打开 / 处理中不刷新，避免跳动
        } else {
            refreshCacheInfo()
        }
    }
    LaunchedEffect(Unit) {
        refreshCacheInfo()
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
            text = stringResource(R.string.tab_settings),
            style = MaterialTheme.typography.headlineMedium,
            color = tokens.textPrimary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.settings_section_cookie),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary
            )
            Text(
                text = stringResource(R.string.settings_cookie_desc),
                fontSize = 12.sp,
                color = tokens.textSecondary,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            NeumorphicButton(
                text = stringResource(R.string.btn_auto_login),
                onClick = onNavigateToLogin,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            NeumorphicButton(
                text = stringResource(R.string.btn_set_cookie),
                onClick = { showCookieDialog = true },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            NeumorphicButton(
                text = stringResource(R.string.btn_check_cookie),
                onClick = { showCheckCookieDialog = true },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.settings_section_history),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary
            )
            Text(
                text = stringResource(R.string.settings_history_desc),
                fontSize = 12.sp,
                color = tokens.textSecondary,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            NeumorphicButton(
                text = stringResource(R.string.btn_download_history),
                onClick = onOpenHistoryHome,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Live 图合成卡片
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.settings_section_live_compose),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary
            )
            Text(
                text = stringResource(R.string.settings_live_compose_desc),
                fontSize = 12.sp,
                color = tokens.textSecondary,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            NeumorphicButton(
                text = stringResource(R.string.btn_open_live_compose),
                onClick = onOpenLiveCompose,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 清空缓存卡片
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.settings_section_cache),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary,
            )
            Text(
                text = stringResource(R.string.settings_cache_desc),
                fontSize = 12.sp,
                color = tokens.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 缓存占用 + 已下载占用（直观展示）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_cache_current_label),
                        fontSize = 11.sp,
                        color = tokens.textSecondary,
                    )
                    Text(
                        text = appCacheBytes.toReadableSize(),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.textPrimary,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        text = stringResource(R.string.settings_cache_download_size_label),
                        fontSize = 11.sp,
                        color = tokens.textSecondary,
                    )
                    Text(
                        text = downloadBytes.toReadableSize(),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.textPrimary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            NeumorphicButton(
                text = stringResource(R.string.btn_clear_cache),
                onClick = {
                    clearDownloadsChecked = false
                    showClearCacheDialog = true
                },
                modifier = Modifier.fillMaxWidth(),
                isDanger = true,
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .glassConvex(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "\u26A0\uFE0F", fontSize = 24.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.disclaimer_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFB45309)
                    )
                    Text(
                        text = stringResource(R.string.disclaimer_content),
                        fontSize = 12.sp,
                        color = tokens.textSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "1.0"
        }

        Text(
            text = "${stringResource(R.string.app_name)} v$versionName",
            fontSize = 12.sp,
            color = tokens.textTertiary,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Text(
            text = stringResource(R.string.settings_credits),
            fontSize = 11.sp,
            color = tokens.textTertiary,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 4.dp)
        )

        Text(
            text = stringResource(R.string.settings_author),
            fontSize = 11.sp,
            color = tokens.textTertiary,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 2.dp)
        )
    }

    if (showCookieDialog) {
        CookieDialog(
            cookieManager = cookieManager,
            onDismiss = { showCookieDialog = false }
        )
    }

    if (showCheckCookieDialog) {
        CookieCheckDialog(
            cookieManager = cookieManager,
            onDismiss = { showCheckCookieDialog = false }
        )
    }

    if (showClearCacheDialog) {
        ClearCacheDialog(
            appCacheBytes = appCacheBytes,
            downloadBytes = downloadBytes,
            initialCheck = clearDownloadsChecked,
            onCheckChange = { clearDownloadsChecked = it },
            onDismiss = { showClearCacheDialog = false },
            onConfirm = {
                showClearCacheDialog = false
                clearing = true
                coroutineScope.launch {
                    val cacheFreed = withContext(Dispatchers.IO) {
                        CacheManager.clearAppCache(context)
                    }
                    val downloadFreed = if (clearDownloadsChecked) {
                        withContext(Dispatchers.IO) { CacheManager.clearAllDownloads(context) }
                    } else 0L
                    // 刷新显示
                    refreshCacheInfo()
                    clearing = false
                    val msg = if (clearDownloadsChecked) {
                        context.getString(
                            R.string.cache_cleared_with_downloads_toast,
                            cacheFreed.toReadableSize(),
                            downloadFreed.toReadableSize(),
                        )
                    } else {
                        context.getString(R.string.cache_cleared_toast, cacheFreed.toReadableSize())
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            },
        )
    }
}

@Composable
private fun CookieDialog(
    cookieManager: CookieManager,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var cookieInput by remember { mutableStateOf(cookieManager.getCookie()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.cookie_dialog_title))
        },
        text = {
            Column {
                OutlinedTextField(
                    value = cookieInput,
                    onValueChange = { cookieInput = it },
                    label = { Text(text = stringResource(R.string.cookie_dialog_hint)) },
                    placeholder = { Text(text = stringResource(R.string.cookie_dialog_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp),
                    singleLine = false,
                    maxLines = 6
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.cookie_dialog_help),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                cookieManager.setCookie(cookieInput.trim())
                Toast.makeText(
                    context,
                    context.getString(R.string.cookie_status_set),
                    Toast.LENGTH_SHORT
                ).show()
                onDismiss()
            }) {
                Text(text = stringResource(R.string.cookie_dialog_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    cookieManager.clearCookie()
                    cookieInput = ""
                    Toast.makeText(
                        context,
                        context.getString(R.string.cookie_status_empty),
                        Toast.LENGTH_SHORT
                    ).show()
                }) {
                    Text(text = stringResource(R.string.cookie_dialog_clear))
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.cookie_dialog_cancel))
                }
            }
        }
    )
}

@Composable
private fun CookieCheckDialog(
    cookieManager: CookieManager,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var cookieInput by remember { mutableStateOf(cookieManager.getCookie()) }
    var checkResult by remember { mutableStateOf<CookieManager.CookieCheckResult?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.cookie_check_dialog_title))
        },
        text = {
            Column {
                OutlinedTextField(
                    value = cookieInput,
                    onValueChange = { cookieInput = it },
                    label = { Text(text = stringResource(R.string.cookie_check_hint)) },
                    placeholder = { Text(text = stringResource(R.string.cookie_check_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp),
                    singleLine = false,
                    maxLines = 6
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.cookie_check_help),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 检查结果
                checkResult?.let { result ->
                    Spacer(modifier = Modifier.height(12.dp))
                    if (result.empty) {
                        Text(
                            text = stringResource(R.string.cookie_check_result_empty),
                            fontSize = 13.sp,
                            color = Color(0xFFE53935)
                        )
                    } else if (result.complete) {
                        Text(
                            text = stringResource(R.string.cookie_check_result_ok),
                            fontSize = 13.sp,
                            color = Color(0xFF2E7D32)
                        )
                    } else {
                        Column {
                            Text(
                                text = stringResource(R.string.cookie_check_result_incomplete),
                                fontSize = 13.sp,
                                color = Color(0xFFE53935)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            if (!result.hasUifid) {
                                Text(
                                    text = "• " + stringResource(R.string.cookie_check_field_uifid),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!result.hasSession) {
                                Text(
                                    text = "• " + stringResource(R.string.cookie_check_field_sessionid),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!result.hasTtwid) {
                                Text(
                                    text = "• " + stringResource(R.string.cookie_check_field_ttwid),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                checkResult = CookieManager.checkCookie(cookieInput.trim())
            }) {
                Text(text = stringResource(R.string.cache_confirm))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    cookieInput = ""
                    checkResult = null
                }) {
                    Text(text = stringResource(R.string.cookie_dialog_clear))
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.cookie_dialog_cancel))
                }
            }
        }
    )
}

@Composable
private fun ClearCacheDialog(
    appCacheBytes: Long,
    downloadBytes: Long,
    initialCheck: Boolean,
    onCheckChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val tokens = LocalGlassTokens.current
    var checked by remember(initialCheck) { mutableStateOf(initialCheck) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        containerColor = Color(0xF2FFFFFF),
        tonalElevation = 0.dp,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
        title = {
            Text(
                text = stringResource(R.string.cache_dialog_title),
                color = tokens.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                // 提示用户当前 App 缓存大小（直观）
                Text(
                    text = stringResource(R.string.settings_cache_current_label) +
                            "：${appCacheBytes.toReadableSize()}",
                    fontSize = 13.sp,
                    color = tokens.textSecondary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_cache_download_size_label) +
                            "：${downloadBytes.toReadableSize()}",
                    fontSize = 13.sp,
                    color = tokens.textSecondary,
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 勾选项
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = checked,
                            onClick = { onCheckChange(!checked) },
                            role = Role.Checkbox,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { onCheckChange(it) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.cache_dialog_option_clear_downloads),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = tokens.textPrimary,
                        )
                        if (downloadBytes > 0L) {
                            Text(
                                text = stringResource(
                                    R.string.cache_dialog_option_clear_downloads_hint,
                                    downloadBytes.toReadableSize(),
                                ),
                                fontSize = 11.sp,
                                color = tokens.textSecondary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        },
        // 按钮位置：左侧确认（危险）、右侧取消（普通），与其它新风格弹窗保持一致。
        dismissButton = {
            NeumorphicButton(
                text = stringResource(R.string.cache_confirm),
                onClick = onConfirm,
                isDanger = true,
                modifier = Modifier.widthIn(min = 96.dp),
                minHeight = 44.dp,
                cornerRadius = 22.dp,
            )
        },
        confirmButton = {
            NeumorphicButton(
                text = stringResource(R.string.cache_cancel),
                onClick = onDismiss,
                isPrimary = true,
                modifier = Modifier.widthIn(min = 96.dp),
                minHeight = 44.dp,
                cornerRadius = 22.dp,
            )
        },
    )
}