// 可变字体 variationSettings 在部分 Compose 版本仍标记为实验 API，显式 OptIn
// Material 3 Expressive 说明：compose-bom 2026.06.01 锁定的 material3 1.4.0 稳定版里，
// Expressive 主题入口（MaterialExpressiveTheme / MotionScheme）仍是 internal，1.5.0-beta 才公开。
// 本项目跟随 stable（1.4.0），改用公开的 4 参 MaterialTheme 承载 Expressive 视觉：
// 形状/配色/字体全部由 design-tokens.json 生成；组件级弹簧动效（按压、形态切换）在
// ui/kit/Motion.kt 用与 androidx ExpressiveMotionTokens 同参数的 spring 实现。
@file:OptIn(ExperimentalTextApi::class)

package com.devperf.monitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontWeight.Companion.Bold
import androidx.compose.ui.text.font.FontWeight.Companion.Medium
import androidx.compose.ui.text.font.FontWeight.Companion.Normal
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devperf.monitor.R
import com.devperf.monitor.data.ThemeMode

/**
 * 主题：Material 3 Expressive 视觉体系（1.0.8 起，取代此前的液态玻璃方案）。
 *
 * 设计原则（对应需求「禁止系统默认控件与默认蓝」）：
 * 1. 视觉底座换成 Google 官方开源设计语言 Material 3 Expressive
 *    （androidx Material3 1.4+，随 compose-bom 升级获得）：弹簧物理动效
 *    （MotionScheme.expressive()）、形状体系、新组件集；
 * 2. 仍然禁用 Material3 动态取色（dynamicColor = false），配色完全来自
 *    design-tokens.json（Catppuccin Mocha / Latte），由 generateDesignTokens
 *    任务生成 [DesignTokens]——品牌色不跟壁纸漂移；
 * 3. 语义色（[LocalTokenColors]）与 MD3 ColorScheme 双轨并存：MD3 组件读
 *    ColorScheme，自绘可视化（环形/波形/柱状）与悬浮窗读语义色，同一套取值；
 * 4. 字体：Inter（界面）+ JetBrains Mono（数值），均随工程打包（OFL 许可见 licenses/）。
 *    Inter 为可变字体，通过 FontVariation 取 400/500/700 字重；
 *    API < 26 时 variationSettings 被系统忽略，字形仍可正常渲染（降级为默认字重）。
 */

// ---------------------------------------------------------------------------
// 字体族
// ---------------------------------------------------------------------------

val InterFamily: FontFamily = FontFamily(
    Font(
        R.font.inter_variable,
        weight = Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.inter_variable,
        weight = Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.inter_variable,
        weight = Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

val JetBrainsMonoFamily: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, weight = Normal),
    Font(R.font.jetbrains_mono_medium, weight = Medium),
    Font(R.font.jetbrains_mono_bold, weight = Bold),
)

// ---------------------------------------------------------------------------
// 语义色透出
// ---------------------------------------------------------------------------

/** 当前主题的语义色（design-tokens.json 生成的 Dark/Light 之一）。 */
internal val LocalTokenColors = staticCompositionLocalOf { DesignTokens.DarkColors }

/** 当前是否深色主题（用于自绘高光/阴影强度微调）。 */
internal val LocalIsDarkTheme = staticCompositionLocalOf { true }

/** 动效曲线（design-tokens.json 三条曲线；M3 Expressive 组件的弹簧动效走 motionScheme）。 */
@Immutable
data class AppMotion(
    val enter: androidx.compose.animation.core.CubicBezierEasing,
    val exit: androidx.compose.animation.core.CubicBezierEasing,
    val emphasized: androidx.compose.animation.core.CubicBezierEasing,
    val enterDurationMs: Int,
    val exitDurationMs: Int,
    val emphasizedDurationMs: Int,
)

internal fun motionFromTokens(): AppMotion = AppMotion(
    enter = androidx.compose.animation.core.CubicBezierEasing(
        DesignTokens.Motion.ENTER.a, DesignTokens.Motion.ENTER.b,
        DesignTokens.Motion.ENTER.c, DesignTokens.Motion.ENTER.d,
    ),
    exit = androidx.compose.animation.core.CubicBezierEasing(
        DesignTokens.Motion.EXIT.a, DesignTokens.Motion.EXIT.b,
        DesignTokens.Motion.EXIT.c, DesignTokens.Motion.EXIT.d,
    ),
    emphasized = androidx.compose.animation.core.CubicBezierEasing(
        DesignTokens.Motion.EMPHASIZED.a, DesignTokens.Motion.EMPHASIZED.b,
        DesignTokens.Motion.EMPHASIZED.c, DesignTokens.Motion.EMPHASIZED.d,
    ),
    enterDurationMs = DesignTokens.Motion.ENTER.durationMs,
    exitDurationMs = DesignTokens.Motion.EXIT.durationMs,
    emphasizedDurationMs = DesignTokens.Motion.EMPHASIZED.durationMs,
)

val LocalAppMotion = staticCompositionLocalOf { motionFromTokens() }

// ---------------------------------------------------------------------------
// 排版
// ---------------------------------------------------------------------------

private fun typography(mono: FontFamily, sans: FontFamily) = Typography(
    // 大数字（健康分、悬浮窗数值）：等宽 + Bold，保证刷新时不晃动
    displayLarge = TextStyle(
        fontFamily = mono, fontWeight = Bold, fontSize = 52.sp, letterSpacing = (-1).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = mono, fontWeight = Bold, fontSize = 36.sp, letterSpacing = (-0.5).sp,
    ),
    // 卡片数值
    headlineMedium = TextStyle(
        fontFamily = mono, fontWeight = Bold, fontSize = 28.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = mono, fontWeight = Medium, fontSize = 22.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = sans, fontWeight = Medium, fontSize = 20.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = sans, fontWeight = Medium, fontSize = 16.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = sans, fontWeight = Normal, fontSize = 15.sp, lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = sans, fontWeight = Normal, fontSize = 13.sp, lineHeight = 19.sp,
    ),
    // 标签/辅助小字
    labelLarge = TextStyle(
        fontFamily = sans, fontWeight = Medium, fontSize = 13.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = sans, fontWeight = Medium, fontSize = 11.sp,
    ),
    // 图表坐标轴 / 时间戳：等宽小字
    labelSmall = TextStyle(
        fontFamily = mono, fontWeight = Normal, fontSize = 10.sp,
    ),
)

// ---------------------------------------------------------------------------
// 主题入口
// ---------------------------------------------------------------------------

/**
 * 应用主题（Material 3 Expressive）。
 *
 * @param themeMode 深色 / 浅色 / 跟随系统
 */
@Composable
fun DevicePerfTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.AUTO -> isSystemInDarkTheme()
    }
    val tokens = if (dark) DesignTokens.DarkColors else DesignTokens.LightColors

    // 1.0.9：把 design-tokens 新加的层次色与描边色全部接进 ColorScheme。
    // 以前只映射了 10 个语义色，surfaceContainer*/tertiary*/outline* 全部回落到
    // MD3 默认色——组件拿到的根本不是我们的色板，这也是界面"看着不对"的原因之一。
    val colorScheme = if (dark) {
        darkColorScheme(
            primary = tokens.accent,
            onPrimary = tokens.background,
            primaryContainer = tokens.surfaceElevated,
            onPrimaryContainer = tokens.textPrimary,
            secondary = tokens.accentSecondary,
            onSecondary = tokens.background,
            tertiary = tokens.tertiary,
            onTertiary = tokens.onTertiary,
            tertiaryContainer = tokens.tertiaryContainer,
            onTertiaryContainer = tokens.onTertiaryContainer,
            background = tokens.background,
            onBackground = tokens.textPrimary,
            surface = tokens.surface,
            onSurface = tokens.textPrimary,
            surfaceVariant = tokens.surfaceContainer,
            onSurfaceVariant = tokens.textSecondary,
            surfaceTint = tokens.accent,
            surfaceDim = tokens.surfaceDim,
            surfaceBright = tokens.surfaceBright,
            surfaceContainerLowest = tokens.surfaceDim,
            surfaceContainerLow = tokens.surfaceLow,
            surfaceContainer = tokens.surfaceContainer,
            surfaceContainerHigh = tokens.surfaceHigh,
            surfaceContainerHighest = tokens.surfaceHighest,
            error = tokens.danger,
            onError = tokens.background,
            outline = tokens.outline,
            outlineVariant = tokens.outlineVariant,
            scrim = tokens.scrim,
            inverseSurface = tokens.inverseSurface,
            inverseOnSurface = tokens.textPrimary,
        )
    } else {
        lightColorScheme(
            primary = tokens.accent,
            onPrimary = tokens.surface,
            primaryContainer = tokens.surfaceElevated,
            onPrimaryContainer = tokens.textPrimary,
            secondary = tokens.accentSecondary,
            onSecondary = tokens.surface,
            tertiary = tokens.tertiary,
            onTertiary = tokens.onTertiary,
            tertiaryContainer = tokens.tertiaryContainer,
            onTertiaryContainer = tokens.onTertiaryContainer,
            background = tokens.background,
            onBackground = tokens.textPrimary,
            surface = tokens.surface,
            onSurface = tokens.textPrimary,
            surfaceVariant = tokens.surfaceContainer,
            onSurfaceVariant = tokens.textSecondary,
            surfaceTint = tokens.accent,
            surfaceDim = tokens.surfaceDim,
            surfaceBright = tokens.surfaceBright,
            surfaceContainerLowest = tokens.surfaceBright,
            surfaceContainerLow = tokens.surfaceLow,
            surfaceContainer = tokens.surfaceContainer,
            surfaceContainerHigh = tokens.surfaceHigh,
            surfaceContainerHighest = tokens.surfaceHighest,
            error = tokens.danger,
            onError = tokens.surface,
            outline = tokens.outline,
            outlineVariant = tokens.outlineVariant,
            scrim = tokens.scrim,
            inverseSurface = tokens.inverseSurface,
            inverseOnSurface = tokens.textPrimary,
        )
    }

    // 形状体系对齐 design-tokens radius（card 24 / small 16 / large 28 / pill 胶囊）
    val shapes = Shapes(
        extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(
            (DesignTokens.Radius.SMALL / 2f).dp,
        ),
        small = androidx.compose.foundation.shape.RoundedCornerShape(DesignTokens.Radius.SMALL.dp),
        medium = androidx.compose.foundation.shape.RoundedCornerShape(DesignTokens.Radius.CARD.dp),
        large = androidx.compose.foundation.shape.RoundedCornerShape(DesignTokens.Radius.LARGE.dp),
        extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(DesignTokens.Radius.LARGE.dp),
    )

    CompositionLocalProvider(
        LocalTokenColors provides tokens,
        LocalIsDarkTheme provides dark,
        LocalAppMotion provides motionFromTokens(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = shapes,
            typography = typography(JetBrainsMonoFamily, InterFamily),
            content = content,
        )
    }
}
