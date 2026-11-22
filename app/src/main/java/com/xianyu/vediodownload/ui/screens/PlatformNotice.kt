package com.xianyu.vediodownload.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianyu.vediodownload.ui.designsystem.component.GlassCard
import com.xianyu.vediodownload.ui.designsystem.theme.AppSpacing
import com.xianyu.vediodownload.ui.designsystem.theme.LocalGlassTokens

/**
 * 「当前解析模式不支持本页功能」的占位提示。
 *
 * 用于快手模式下的 Live 页与作者页：这两页依赖抖音特有的数据结构
 * （Live 图的封面/短片配对、作者作品列表接口），快手模式下不可用。
 *
 * 保留 Tab 而不是隐藏 Tab，是为了避免 HorizontalPager 页数与索引变化带来的重建风险。
 */
@Composable
fun KuaishouUnsupportedNotice(
    title: String,
    featureName: String,
    reason: String,
) {
    val tokens = LocalGlassTokens.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.safeHorizontal)
            .padding(bottom = 100.dp)
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = tokens.textPrimary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "$featureName 暂不支持快手",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = reason,
                fontSize = 13.sp,
                color = tokens.textSecondary,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "如需使用本页功能，请在「设置 → 解析模式」切换为抖音模式。",
                fontSize = 12.sp,
                color = tokens.textTertiary,
                lineHeight = 18.sp
            )
        }
    }
}
