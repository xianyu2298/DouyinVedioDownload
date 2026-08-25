@file:OptIn(ExperimentalFoundationApi::class)

package com.xianyu.vediodownload.ui.screens

import android.app.Application
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.xianyu.vediodownload.util.CookieManager
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xianyu.vediodownload.R
import com.xianyu.vediodownload.ui.designsystem.theme.*
import kotlinx.coroutines.launch
import kotlin.math.abs

data class TabItem(
    val route: String,
    val title: String,
    val icon: String
)

val tabItems = listOf(
    TabItem("video", "视频", "📹"),
    TabItem("image", "图片", "🖼️"),
    TabItem("live", "Live", "📸"),
    TabItem("settings", "设置", "⚙️")
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    onNavigateToLogin: () -> Unit
) {
    val tokens = LocalGlassTokens.current
    val application = LocalContext.current.applicationContext as Application
    val cookieManager = remember { CookieManager(application) }

    var showDisclaimer by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!cookieManager.isDisclaimerShown()) {
            delay(300) // 等弹窗动画稳定
            showDisclaimer = true
        }
    }

    if (showDisclaimer) {
        DisclaimerDialog(
            onConfirm = {
                cookieManager.setDisclaimerShown()
                showDisclaimer = false
            }
        )
    }

    // 使用 viewModel 工厂创建独立的 ViewModel 实例，每个使用不同的 key
    val videoViewModel: DownloadViewModel = viewModel(
        key = "video_view_model",
        factory = DownloadViewModelFactory(application)
    )
    val imageViewModel: DownloadViewModel = viewModel(
        key = "image_view_model",
        factory = DownloadViewModelFactory(application)
    )
    val liveViewModel: DownloadViewModel = viewModel(
        key = "live_view_model",
        factory = DownloadViewModelFactory(application)
    )

    val pagerState = rememberPagerState(initialPage = 0) { tabItems.size }
    val selectedTab = pagerState.currentPage
    val scope = rememberCoroutineScope()

    val isPagerScrollEnabled = remember(
        selectedTab,
        imageViewModel.uiState.value.isSelectionMode,
        liveViewModel.uiState.value.isSelectionMode
    ) {
        !(selectedTab == 1 && imageViewModel.uiState.value.isSelectionMode) &&
        !(selectedTab == 2 && liveViewModel.uiState.value.isSelectionMode)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 使用玻璃态网格背景
        GlassMeshBackground(tokens = tokens)

        // beyondBoundsPageCount 设为页面总数 - 1 = 所有页面常驻组合树，
        // 首次滑动不再触发任何子页面的首次组合/测量/布局，故一次卡顿分摊到启动期后切换永远丝滑。
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondBoundsPageCount = tabItems.size,
            pageSpacing = 0.dp,
            userScrollEnabled = isPagerScrollEnabled,
            key = { index ->
                when (index) {
                    0 -> "video"
                    1 -> "image"
                    2 -> "live"
                    else -> "settings"
                }
            }
        ) { page ->
            when (page) {
                0 -> VideoScreen(viewModel = videoViewModel)
                1 -> ImageScreen(viewModel = imageViewModel)
                2 -> LiveScreen(viewModel = liveViewModel)
                3 -> SettingsScreen(onNavigateToLogin = onNavigateToLogin)
            }
        }

        GlassTabBar(
            selectedIndex = selectedTab,
            pagerState = pagerState,
            onTabSelected = { index ->
                scope.launch {
                    pagerState.animateScrollToPage(index)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .imePadding()
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GlassTabBar(
    selectedIndex: Int,
    pagerState: PagerState,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalGlassTokens.current

    // 使用玻璃态效果替代简单的半透明白色
    Box(
        modifier = modifier
            .fillMaxWidth()
            // 外阴影
            .drawWithContent {
                drawContent()
                drawRoundRect(
                    color = tokens.outerShadow,
                    topLeft = Offset(0f, 3.dp.toPx()),
                    size = size,
                    cornerRadius = CornerRadius(28.dp.toPx(), 28.dp.toPx())
                )
            }
            .clip(RoundedCornerShape(28.dp))
            // 半透明填充
            .drawBehind { drawRect(tokens.tintConvex) }
            // 内容层
            .drawWithContent {
                drawContent()
                // 顶部内高光线
                drawRect(
                    color = tokens.innerHighlight,
                    size = Size(size.width, 1.5.dp.toPx())
                )
            }
            // 渐变边框
            .drawBehind {
                drawRoundRect(
                    brush = Brush.linearGradient(listOf(tokens.borderHi, tokens.borderLo)),
                    cornerRadius = CornerRadius(28.dp.toPx(), 28.dp.toPx()),
                    style = Stroke(1.dp.toPx())
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabItems.forEachIndexed { index, item ->
                GlassTabItem(
                    icon = item.icon,
                    title = item.title,
                    isSelected = index == selectedIndex,
                    pagerState = pagerState,
                    pagerIndex = index,
                    onClick = { onTabSelected(index) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GlassTabItem(
    icon: String,
    title: String,
    isSelected: Boolean,
    pagerState: PagerState,
    pagerIndex: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalGlassTokens.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) Color(0xFF3B82F6).copy(alpha = 0.12f) else Color.Transparent,
        animationSpec = tween(200),
        label = "tab_bg"
    )

    val textColor by animateColorAsState(
        targetValue = if (isSelected) Color(0xFF3B82F6) else tokens.textSecondary,
        animationSpec = tween(200),
        label = "tab_text"
    )

    // 用 Pager 实时偏移驱动 "跳一下" 的弹性效果：
    // pageDiff 越接近 0 说明离选中态越近，bounce 越接近 1 → 跳动最明显。
    val rawOffset = pagerState.currentPageOffsetFraction
    val pageDiff = (pagerState.currentPage - pagerIndex) + rawOffset
    val bounce = (1f - abs(pageDiff)).coerceIn(0f, 1f)

    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1f + 0.22f * bounce else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "tab_scale"
    )

    val translateY by animateFloatAsState(
        targetValue = if (isSelected) -10f * bounce else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "tab_translate"
    )

    Column(
        modifier = modifier
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(backgroundColor)
            .clickable(
                interactionSource = interactionSource,
                indication = rememberRipple(color = Color(0xFF3B82F6).copy(alpha = 0.1f)),
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = icon,
            fontSize = 22.sp,
            modifier = Modifier.graphicsLayer {
                scaleX = scale * if (isPressed) 0.92f else 1f
                scaleY = scale * if (isPressed) 0.92f else 1f
                translationY = translateY
            }
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor,
            modifier = Modifier.graphicsLayer {
                alpha = if (isSelected) 1f else 0.85f
            }
        )
    }
}
