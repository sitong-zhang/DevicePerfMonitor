// ===========================================================================
// 本文件由 app/build.gradle.kts 的 generateDesignTokens 任务自动生成
// （实现：buildSrc/src/main/kotlin/GenerateDesignTokensTask.kt）
// 真源：/design-tokens.json（双端共用）。请勿手工修改本文件。
// ===========================================================================

package com.devperf.monitor.ui.theme

import androidx.compose.ui.graphics.Color

/** 语义色板：由 design-tokens.json 的 palette.*.semantic 生成（字段随真源走）。 */
internal data class TokenColors(
    val accent: Color,
    val accentSecondary: Color,
    val background: Color,
    val danger: Color,
    val inverseSurface: Color,
    val onTertiary: Color,
    val onTertiaryContainer: Color,
    val outline: Color,
    val outlineVariant: Color,
    val scrim: Color,
    val success: Color,
    val surface: Color,
    val surfaceBright: Color,
    val surfaceContainer: Color,
    val surfaceDim: Color,
    val surfaceElevated: Color,
    val surfaceHigh: Color,
    val surfaceHighest: Color,
    val surfaceLow: Color,
    val tertiary: Color,
    val tertiaryContainer: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val warning: Color,
)

/** 动效曲线：cubic-bezier 四参数 + 时长（ms）。 */
internal data class MotionCurveSpec(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val durationMs: Int,
)

/** 健康分温度分段点：上限℃（null = +∞）、段起始分、段结束分（相等表示定值段）。 */
internal data class TemperatureBreakpoint(
    val maxTempC: Float?,
    val fromScore: Float,
    val toScore: Float,
)

/** 健康分评级档位（按 minScore 降序排列，与 HealthScorer.Grade 枚举序一致）。 */
internal data class GradeSpec(
    val minScore: Float,
    val label: String,
)

internal object DesignTokens {

    val DarkColors = TokenColors(
        accent = Color(0xFFDEC0FF),
        accentSecondary = Color(0xFF9DD1C5),
        background = Color(0xFF17101F),
        danger = Color(0xFFFD6F85),
        inverseSurface = Color(0xFFFFF7FF),
        onTertiary = Color(0xFF003730),
        onTertiaryContainer = Color(0xFFBEF3E6),
        outline = Color(0xFF9D92A6),
        outlineVariant = Color(0xFF5F5668),
        scrim = Color(0xFF000000),
        success = Color(0xFFA6E3A1),
        surface = Color(0xFF2C2535),
        surfaceBright = Color(0xFF51485A),
        surfaceContainer = Color(0xFF2C2535),
        surfaceDim = Color(0xFF0F0817),
        surfaceElevated = Color(0xFF372F40),
        surfaceHigh = Color(0xFF372F40),
        surfaceHighest = Color(0xFF433A4B),
        surfaceLow = Color(0xFF221A2A),
        tertiary = Color(0xFF9DD1C5),
        tertiaryContainer = Color(0xFF1A4F46),
        textPrimary = Color(0xFFF0DFFF),
        textSecondary = Color(0xFFD4C7DD),
        warning = Color(0xFFF9E2AF),
    )

    val LightColors = TokenColors(
        accent = Color(0xFF724DA0),
        accentSecondary = Color(0xFF35675D),
        background = Color(0xFFFFF7FF),
        danger = Color(0xFFAC3149),
        inverseSurface = Color(0xFF130A1E),
        onTertiary = Color(0xFFE5FFF7),
        onTertiaryContainer = Color(0xFF003730),
        outline = Color(0xFF666268),
        outlineVariant = Color(0xFFBAB4BB),
        scrim = Color(0xFF000000),
        success = Color(0xFF40A02B),
        surface = Color(0xFFE8E0E8),
        surfaceBright = Color(0xFFFFFBFF),
        surfaceContainer = Color(0xFFE8E0E8),
        surfaceDim = Color(0xFFCBC4CC),
        surfaceElevated = Color(0xFFDFD8DF),
        surfaceHigh = Color(0xFFDFD8DF),
        surfaceHighest = Color(0xFFD7CFD7),
        surfaceLow = Color(0xFFF3EBF3),
        tertiary = Color(0xFF35675D),
        tertiaryContainer = Color(0xFFB8EDE0),
        textPrimary = Color(0xFF3C2A52),
        textSecondary = Color(0xFF534F55),
        warning = Color(0xFFDF8E1D),
    )

    object Radius {
        const val CARD = 24f
        const val SMALL = 16f
        const val LARGE = 28f
        const val PILL = 999f
    }

    object Spacing {
        const val S4 = 4f
        const val S8 = 8f
        const val S12 = 12f
        const val S16 = 16f
        const val S24 = 24f
        const val S32 = 32f
    }

    object Blur {
        const val GLASS = 32f
        const val SUBTLE = 24f
        const val OVERLAY_MIN = 28f
        const val OVERLAY_MAX = 36f
    }

    object Glass {
        object BACKGROUND_ALPHA {
            const val MIN = 0.18f
            const val MAX = 0.28f
        }
        const val BORDER_WIDTH = 1f
        const val BORDER_ALPHA = 0.35f
        const val HIGHLIGHT_ALPHA_TOP = 0.55f
        const val HIGHLIGHT_ALPHA_BOTTOM = 0.12f
        const val INNER_SHADOW_ALPHA = 0.10f
        const val OUTER_SHADOW_ALPHA = 0.22f
        const val OUTER_SHADOW_RADIUS = 20f
        const val OUTER_SHADOW_OFFSET_Y = 8f
        const val SATURATION_BOOST = 1.12f
        const val NOISE_ALPHA = 0.035f
    }

    /** 卡片/面板的视觉层次（内边距、间距、描边、顶部高光）。 */
    object Surface {
        const val CARD_PADDING = 16f
        const val GAP = 14f
        const val BORDER_WIDTH = 1f
        const val BORDER_ALPHA = 0.22f
        const val HIGHLIGHT_ALPHA = 0.05f
    }

    /** 三层投影：1=卡片，2=浮动底栏，3=弹层。 */
    object Elevation {
        object LEVEL1 {
            const val SHADOW_Y = 2f
            const val RADIUS = 8f
            const val ALPHA = 0.3f
        }
        object LEVEL2 {
            const val SHADOW_Y = 6f
            const val RADIUS = 18f
            const val ALPHA = 0.36f
        }
        object LEVEL3 {
            const val SHADOW_Y = 12f
            const val RADIUS = 32f
            const val ALPHA = 0.44f
        }
    }

    /** 浮动胶囊底栏几何（双端一致）。 */
    object BottomBar {
        const val HEIGHT = 62f
        const val MARGIN_HORIZONTAL = 24f
        const val MARGIN_BOTTOM = 24f
        const val INDICATOR_INSET = 4f
        const val ICON_SIZE = 24f
        const val LABEL_SIZE = 11f
        const val INDICATOR_ALPHA = 0.18f
        const val SELECTED_SCALE = 1.08f
    }

    object Motion {
        val ENTER = MotionCurveSpec(0.05f, 0.7f, 0.1f, 1.0f, 320)
        val EXIT = MotionCurveSpec(0.3f, 0.0f, 0.8f, 0.15f, 240)
        val EMPHASIZED = MotionCurveSpec(0.2f, 0.0f, 0.0f, 1.0f, 300)

        // Material 3 Expressive 空间弹簧（androidx ExpressiveMotionTokens 同参数）。
        // 组件级交互（按压、形态切换）走 spring，页面切换走上面的曲线。
        object Spring {
            const val FAST_SPATIAL_DAMPING = 0.6f
            const val FAST_SPATIAL_STIFFNESS = 800f
            const val DEFAULT_SPATIAL_DAMPING = 0.8f
            const val DEFAULT_SPATIAL_STIFFNESS = 380f
            const val SLOW_SPATIAL_DAMPING = 0.8f
            const val SLOW_SPATIAL_STIFFNESS = 200f
        }
    }

    object Gesture {
        const val DOUBLE_TAP_WINDOW_MS = 250L
        const val TOUCH_SLOP_DP = 8f
        const val EDGE_SNAP_THRESHOLD_DP = 96f
        const val TRASH_ZONE_HEIGHT_DP = 140f
        const val SCALE_MIN = 0.8f
        const val SCALE_MAX = 1.6f
    }

    object Number {
        const val MAX_DURATION_MS = 300
        const val INTERVAL_MARGIN_MS = 50
    }

    object Health {
        object Weights {
            const val TEMPERATURE = 0.4f
            const val FRAME_STABILITY = 0.3f
            const val BATTERY = 0.2f
            const val MEMORY = 0.1f
        }

        /** 温度分段（℃ → 分数），按上限升序；最后一段 maxTempC = null 表示 +∞。 */
        val TEMPERATURE_BREAKPOINTS: List<TemperatureBreakpoint> = listOf(
            TemperatureBreakpoint(maxTempC = 40f, fromScore = 100f, toScore = 100f),
            TemperatureBreakpoint(maxTempC = 50f, fromScore = 100f, toScore = 70f),
            TemperatureBreakpoint(maxTempC = 60f, fromScore = 70f, toScore = 35f),
            TemperatureBreakpoint(maxTempC = 70f, fromScore = 35f, toScore = 10f),
            TemperatureBreakpoint(maxTempC = null, fromScore = 0f, toScore = 0f),
        )

        /** 评级档位，按 minScore 降序。 */
        val GRADES: List<GradeSpec> = listOf(
            GradeSpec(minScore = 90f, label = "优秀"),
            GradeSpec(minScore = 70f, label = "良好"),
            GradeSpec(minScore = 50f, label = "一般"),
            GradeSpec(minScore = 0f, label = "警告"),
        )
    }
}