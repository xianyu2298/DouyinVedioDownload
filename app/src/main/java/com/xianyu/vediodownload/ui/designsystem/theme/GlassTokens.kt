package com.xianyu.vediodownload.ui.designsystem.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class MeshSpot(val xFraction: Float, val yFraction: Float, val color: Color, val radiusFraction: Float)

data class GlassTokens(
    val meshBase: Color,
    val meshSpots: List<MeshSpot>,
    val tintConvex: Color,
    val tintConcave: Color,
    val borderHi: Color,
    val borderLo: Color,
    val innerHighlight: Color,
    val outerShadow: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
)

val LightGlassTokens = GlassTokens(
    meshBase = Color(0xFFF8F5FF),
    meshSpots = listOf(
        MeshSpot(0.12f, 0.18f, Color(0x50FF6B6B), 0.55f),
        MeshSpot(0.78f, 0.10f, Color(0x456B9FFF), 0.50f),
        MeshSpot(0.20f, 0.82f, Color(0x40FFB36B), 0.52f),
        MeshSpot(0.88f, 0.78f, Color(0x456BFF9F), 0.50f),
        MeshSpot(0.45f, 0.55f, Color(0x30B3A0FF), 0.65f),
    ),
    tintConvex = Color(0x40FFFFFF),
    tintConcave = Color(0x15000000),
    borderHi = Color(0xFFFFFFFF),
    borderLo = Color(0x20000000),
    innerHighlight = Color(0x99FFFFFF),
    outerShadow = Color(0x1A000000),
    textPrimary = Color(0xFF1A1A2E),
    textSecondary = Color(0xFF5A6270),
    textTertiary = Color(0xFF9CA3AF),
)

val LocalGlassTokens = staticCompositionLocalOf { LightGlassTokens }
enum class AppSkin { NEUMORPHISM, GLASS }
val LocalAppSkin = staticCompositionLocalOf { AppSkin.NEUMORPHISM }
