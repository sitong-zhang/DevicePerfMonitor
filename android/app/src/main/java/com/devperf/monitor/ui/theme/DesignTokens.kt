// ===========================================================================
// 本文件由 app/build.gradle.kts 的 generateDesignTokens 任务自动生成
// （实现：buildSrc/src/main/kotlin/GenerateDesignTokensTask.kt）
// 真源：/design-tokens.json（双端共用）。请勿手工修改本文件。
// ===========================================================================

package com.devperf.monitor.ui.theme

import androidx.compose.ui.graphics.Color

/** 语义色板：深色 = Catppuccin Mocha，浅色 = Catppuccin Latte。 */
internal data class TokenColors(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val accentSecondary: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
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
        background = Color(0xFF1E1E2E),
        surface = Color(0xFF313244),
        surfaceElevated = Color(0xFF45475A),
        textPrimary = Color(0xFFCDD6F4),
        textSecondary = Color(0xFFA6ADC8),
        accent = Color(0xFFCBA6F7),
        accentSecondary = Color(0xFF94E2D5),
        success = Color(0xFFA6E3A1),
        warning = Color(0xFFF9E2AF),
        danger = Color(0xFFF38BA8),
    )

    val LightColors = TokenColors(
        background = Color(0xFFEFF1F5),
        surface = Color(0xFFE6E9EF),
        surfaceElevated = Color(0xFFDCE0E8),
        textPrimary = Color(0xFF4C4F69),
        textSecondary = Color(0xFF6C6F85),
        accent = Color(0xFF8839EF),
        accentSecondary = Color(0xFF179299),
        success = Color(0xFF40A02B),
        warning = Color(0xFFDF8E1D),
        danger = Color(0xFFD20F39),
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

    object Motion {
        val ENTER = MotionCurveSpec(0.05f, 0.7f, 0.1f, 1.0f, 320)
        val EXIT = MotionCurveSpec(0.3f, 0.0f, 0.8f, 0.15f, 240)
        val EMPHASIZED = MotionCurveSpec(0.2f, 0.0f, 0.0f, 1.0f, 300)
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