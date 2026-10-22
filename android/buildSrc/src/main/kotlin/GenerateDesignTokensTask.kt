package com.devperf.monitor.buildlogic

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * design-tokens.json -> DesignTokens.kt 代码生成任务。
 *
 * 双端视觉唯一真源：仓库根目录 design-tokens.json。
 * 该任务在每次构建前重新生成 DesignTokens.kt，确保业务代码中的色值/圆角/间距/
 * 模糊/动效/健康分常量与 JSON 完全一致；业务代码禁止硬编码这些参数。
 *
 * 实现为编译后的 Task 类（而非构建脚本闭包），因此与 Gradle 配置缓存兼容：
 * 若把生成逻辑写成构建脚本里的 doLast 闭包，配置缓存会因
 * 「cannot serialize Gradle script object references」在每次构建时丢弃缓存条目。
 */
abstract class GenerateDesignTokensTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val tokensFile: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val json = JsonSlurper().parse(tokensFile.get().asFile) as Map<*, *>
        val palette = json["palette"] as Map<*, *>
        val radius = json["radius"] as Map<*, *>
        val spacing = json["spacing"] as List<*>
        val blur = json["blur"] as Map<*, *>
        val glass = json["glass"] as Map<*, *>
        val motion = json["motion"] as Map<*, *>
        val gesture = json["gesture"] as Map<*, *>
        val number = json["number"] as Map<*, *>
        val health = json["health"] as Map<*, *>

        val colorKeys = listOf(
            "background", "surface", "surfaceElevated", "textPrimary", "textSecondary",
            "accent", "accentSecondary", "success", "warning", "danger",
        )

        fun semantic(themeKey: String) = (palette[themeKey] as Map<*, *>)["semantic"] as Map<*, *>

        fun colorArgs(themeKey: String): String = colorKeys.joinToString(",\n        ") { key ->
            val hex = (semantic(themeKey)[key] as String).removePrefix("#").uppercase()
            "$key = Color(0xFF$hex)"
        }

        fun camelToUpperSnake(name: String): String {
            val s = name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            // 仅保留 ASCII 字母/数字/下划线，其余字符（$、中文等）折叠为下划线，
            // 保证任意 JSON key 都能生成合法 Kotlin 标识符
            val filtered = buildString {
                for (ch in s) {
                    append(if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '_') ch else '_')
                }
            }.replace(Regex("_+"), "_").trim('_')
            return when {
                filtered.isEmpty() -> "UNNAMED"
                filtered[0] in '0'..'9' -> "T_$filtered"
                else -> filtered.uppercase()
            }
        }

        // JSON 中以 $ 开头的 key 为注释（$comment），不生成常量
        fun Map<*, *>.entries(): List<Map.Entry<*, *>> =
            entries.filterNot { (it.key as? String)?.startsWith("$") == true }

        fun consts(map: Map<*, *>, floatSuffix: Boolean = true): String =
            map.entries().joinToString("\n") { (k, v) ->
                val value = if (floatSuffix && v is Number) "${v}f" else "$v"
                "        const val ${camelToUpperSnake(k as String)} = $value"
            }

        fun nestedConsts(map: Map<*, *>): String =
            map.entries().joinToString("\n") { (k, v) ->
                if (v is Map<*, *>) {
                    "        object ${camelToUpperSnake(k as String)} {\n" +
                        v.entries().joinToString("\n") { (k2, v2) ->
                            val value = if (v2 is Number) "${v2}f" else "$v2"
                            "            const val ${camelToUpperSnake(k2 as String)} = $value"
                        } + "\n        }"
                } else {
                    val value = if (v is Number) "${v}f" else "$v"
                    "        const val ${camelToUpperSnake(k as String)} = $value"
                }
            }

        fun motionCurve(key: String): String {
            val m = motion[key] as Map<*, *>
            val bezier = m["cubicBezier"] as List<*>
            return "MotionCurveSpec(${bezier[0]}f, ${bezier[1]}f, ${bezier[2]}f, ${bezier[3]}f, ${m["durationMs"]})"
        }

        val scaleRange = gesture["scaleRange"] as List<*>

        // ---- health 段（评分公式参数，双端同源）----
        val healthWeights = health["weights"] as Map<*, *>
        val tempBreakpoints = health["temperatureBreakpoints"] as List<*>
        val healthGrades = health["grades"] as List<*>

        fun bpConsts(map: Map<*, *>): String =
            map.entries().joinToString("\n") { (k, v) ->
                val value = if (v is Number) "${v}f" else "$v"
                "            const val ${camelToUpperSnake(k as String)} = $value"
            }

        fun breakpointLiteral(bp: Map<*, *>): String {
            val max = bp["max"]
            val maxLiteral = if (max == null) "null" else "${max}f"
            val from = bp["from"] ?: bp["score"]
            val to = bp["to"] ?: bp["score"]
            return "TemperatureBreakpoint(maxTempC = $maxLiteral, fromScore = ${from}f, toScore = ${to}f)"
        }

        fun gradeLiteral(g: Map<*, *>): String =
            "GradeSpec(minScore = ${g["min"]}f, label = \"${g["label"]}\")"

        val source = """
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
        ${colorArgs("dark")},
    )

    val LightColors = TokenColors(
        ${colorArgs("light")},
    )

    object Radius {
${consts(radius)}
    }

    object Spacing {
${spacing.joinToString("\n") { v -> "        const val S$v = ${v}f" }}
    }

    object Blur {
${consts(blur)}
    }

    object Glass {
${nestedConsts(glass)}
    }

    object Motion {
        val ENTER = ${motionCurve("enter")}
        val EXIT = ${motionCurve("exit")}
        val EMPHASIZED = ${motionCurve("emphasized")}
    }

    object Gesture {
        const val DOUBLE_TAP_WINDOW_MS = ${gesture["doubleTapWindowMs"]}L
        const val TOUCH_SLOP_DP = ${gesture["touchSlopDp"]}f
        const val EDGE_SNAP_THRESHOLD_DP = ${gesture["edgeSnapThresholdDp"]}f
        const val TRASH_ZONE_HEIGHT_DP = ${gesture["trashZoneHeightDp"]}f
        const val SCALE_MIN = ${scaleRange[0]}f
        const val SCALE_MAX = ${scaleRange[1]}f
    }

    object Number {
        const val MAX_DURATION_MS = ${number["maxDurationMs"]}
        const val INTERVAL_MARGIN_MS = ${number["intervalMarginMs"]}
    }

    object Health {
        object Weights {
${bpConsts(healthWeights)}
        }

        /** 温度分段（℃ → 分数），按上限升序；最后一段 maxTempC = null 表示 +∞。 */
        val TEMPERATURE_BREAKPOINTS: List<TemperatureBreakpoint> = listOf(
            ${tempBreakpoints.joinToString(",\n            ") { breakpointLiteral(it as Map<*, *>) }},
        )

        /** 评级档位，按 minScore 降序。 */
        val GRADES: List<GradeSpec> = listOf(
            ${healthGrades.joinToString(",\n            ") { gradeLiteral(it as Map<*, *>) }},
        )
    }
}
""".trimIndent()

        outputFile.get().asFile.writeText(source)
        logger.lifecycle("generateDesignTokens: 已生成 ${outputFile.get().asFile.path}")
    }
}
