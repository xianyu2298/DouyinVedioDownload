package com.xianyu.vediodownload.ui.designsystem.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ═══ HyperColors 色板 ═══
data class HyperColors(
    val background: Color,
    val cardBackground: Color,
    val pageGradientTop: Color,
    val pageGradientMid: Color,
    val pageGradientBottom: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val primary: Color,
    val primaryVariant: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val iconBackground: Color,
    val badgeBackground: Color,
    val badgeText: Color,
    val border: Color,
    val divider: Color,
    val liquidScreenBg: Color,
    val liquidCardBg: Color,
)

val LightHyperColors = HyperColors(
    background = Color(0xFFE8EBF0),
    cardBackground = Color(0xFFF5F8FC),
    pageGradientTop = Color(0xFFE8EBF0),
    pageGradientMid = Color(0xFFF0F3F7),
    pageGradientBottom = Color(0xFFF8FAFC),
    textPrimary = Color(0xFF1A1A2E),
    textSecondary = Color(0xFF5A6270),
    textTertiary = Color(0xFF9CA3AF),
    primary = Color(0xFF3B82F6),
    primaryVariant = Color(0xFF2563EB),
    success = Color(0xFF10B981),
    warning = Color(0xFFF59E0B),
    error = Color(0xFFEF4444),
    iconBackground = Color(0xFFE8EBF0),
    badgeBackground = Color(0xFFDBEAFE),
    badgeText = Color(0xFF2563EB),
    border = Color(0xFFD1D5DB),
    divider = Color(0xFFE5E7EB),
    liquidScreenBg = Color(0xFFE8EBF0),
    liquidCardBg = Color(0xFFF5F8FC),
)

val LocalHyperColors = staticCompositionLocalOf { LightHyperColors }

// 新拟态阴影色
val NeumLightHighlight = Color(0xFFFFFFFF)
val NeumLightShadow = Color(0xFFC8CDD6)
