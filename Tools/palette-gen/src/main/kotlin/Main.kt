import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeExpressive
import java.io.File
import kotlin.system.exitProcess

/**
 * 生成 Material 3 Expressive 配色，输出 design-tokens.json 的 palette 段。
 *
 * 为什么要这个工具：手写的 10 个语义色撑不起层次感（卡片、底栏、描边全都挤在
 * 同一个 surface 上），所以改用 Google 官方算法从种子色生成一整套带 tonal 层次、
 * 且对比度达标的色板。
 *
 * 为什么用 material-color-utilities 而不是 material-kolor：后者 5.0.1 依赖
 * material3 1.12.0-alpha03（alpha 版），会逼着升 alpha 依赖；而本仓库锁的是
 * compose-bom 2026.06.01（compose 1.11.4 + material3 1.4.0 稳定版）。
 * material-color-utilities 是同一套算法的纯 Kotlin 实现，只依赖 kotlin-stdlib。
 *
 * 用法：
 *   cd android && ./gradlew -p ../Tools/palette-gen run              # 只打印
 *   cd android && ./gradlew -p ../Tools/palette-gen run --args="--write"  # 写回 JSON
 *
 * 生成的色值必须签入仓库：design-tokens.json 是双端唯一真源，
 * 运行时和 CI 都不需要这个工具（也不该依赖它）。
 */

/** 种子色：Catppuccin Mauve #CBA6F7——与现有暗底同色系，换色板不换品牌调性。 */
private val SEED = 0xFFCBA6F7.toInt()

/**
 * success / warning 沿用 Catppuccin。
 *
 * Material 3 只有 error，没有 success/warning 语义角色；这两个色在现有界面里
 * 已验证过可读性，且种子色本就是 Mauve，与新色板同色系不打架。
 * danger 改用 M3 的 error（它随种子色走，且官方保证与表面的对比度）。
 */
private val LEGACY = mapOf(
    "dark" to mapOf("success" to "A6E3A1", "warning" to "F9E2AF"),
    "light" to mapOf("success" to "40A02B", "warning" to "DF8E1D"),
)

private const val TOKENS_PATH = "../../design-tokens.json"

/**
 * CIELAB L* → 相对亮度 Y（0~100）。
 *
 * 与 material-color-utilities 的 `ColorUtils.yFromLstar` 同一实现。
 * 注意 WCAG 的对比度**不能**直接拿 L* 算——L* 是感知均匀的非线性量纲，
 * 必须先换回线性亮度 Y。直接拿 tone 相除会把 tone 50/98 算成 1.87（错），
 * 实际是 4.27（对），差别足以让校验完全失真。
 */
private fun yFromLstar(lstar: Double): Double {
    val e = 216.0 / 24389.0
    val kappa = 24389.0 / 27.0
    val ft = (lstar + 16.0) / 116.0
    val ft3 = ft * ft * ft
    return 100.0 * if (ft3 > e) ft3 else (116.0 * ft - 16.0) / kappa
}

/** WCAG 对比度：由两个 HCT tone（即 L*）算出，等价于 `Contrast.ratioOfTones`。 */
private fun contrastRatio(toneA: Double, toneB: Double): Double {
    val hi = yFromLstar(maxOf(toneA, toneB))
    val lo = yFromLstar(minOf(toneA, toneB))
    return (hi + 5.0) / (lo + 5.0)
}

fun main(args: Array<String>) {
    val write = args.contains("--write")
    val hct = Hct.fromInt(SEED)
    val mdc = MaterialDynamicColors()

    val failures = mutableListOf<String>()
    val report = StringBuilder()

    for (isDark in listOf(true, false)) {
        val themeKey = if (isDark) "dark" else "light"
        val scheme = SchemeExpressive(
            hct, isDark, 0.0, ColorSpec.SpecVersion.SPEC_2025, DynamicScheme.Platform.PHONE,
        )
        fun argb(dc: DynamicColor) = dc.getArgb(scheme)
        fun hex(dc: DynamicColor) = "%06X".format(argb(dc) and 0xFFFFFF)
        fun tone(dc: DynamicColor) = Hct.fromInt(argb(dc)).tone

        // ---- 层次阶梯 ----
        // M3 默认相邻 surface 只差 2~3 tone（深色彩 4/6/9/12/15），叠在深色背景上
        // 几乎看不出区别——这正是旧版"平、没有层次"的观感来源。这里沿 surface
        // 自己的色相/彩度，把明度手工拉开到 ~6 tone 一档，卡片、底栏、弹层才有
        // 明确的层级关系；色相和彩度不变，所以不会跟主色打架。
        val baseHct = Hct.fromInt(argb(mdc.surface()))
        fun at(t: Double) = "%06X".format(
            Hct.from(baseHct.hue, baseHct.chroma, t).toInt() and 0xFFFFFF,
        )
        // 阶梯上限受"最坏层对比度"约束：层抬得越高（深色越亮 / 浅色越暗），
        // 次要文字 onSurfaceVariant 压在上面的对比度就越低。下面这组是
        // 兼顾层次差（≥5 tone）与 4.5:1 之后的值，改大要先跑校验。
        val ladder = if (isDark) {
            mapOf(
                "surfaceDim" to 3.0, "background" to 6.0, "surfaceLow" to 11.0,
                "surfaceContainer" to 16.0, "surfaceHigh" to 21.0, "surfaceHighest" to 26.0,
                "surfaceBright" to 32.0,
            )
        } else {
            // 浅色下 elevation 越高越暗（M3 的设计），所以 surfaceDim 是最暗的一层
            mapOf(
                "surfaceBright" to 99.0, "background" to 98.0, "surfaceLow" to 94.0,
                "surfaceContainer" to 90.0, "surfaceHigh" to 87.0, "surfaceHighest" to 84.0,
                "surfaceDim" to 80.0,
            )
        }

        // 顺序：先旧的 10 个 key（保证与历史版本 diff 清晰、引用不失效），再新增层次色
        val roles = linkedMapOf<String, String>()
        roles["background"] = at(ladder.getValue("background"))
        roles["surface"] = at(ladder.getValue("surfaceContainer"))
        roles["surfaceElevated"] = at(ladder.getValue("surfaceHigh"))
        roles["textPrimary"] = hex(mdc.onSurface())
        // 次要文字不用 M3 的 onSurfaceVariable：SPEC_2025 给它的 tone 偏高（浅色 ~40），
        // 压在最暗那层表面上只有 3.75，读不清。这里自己定 tone，保证任意层上都 ≥4.5，
        // 同时和 textPrimary 拉开 13+ tone 的层次差。
        val textSecondaryTone = if (isDark) 82.0 else 34.0
        roles["textSecondary"] = at(textSecondaryTone)
        roles["accent"] = hex(mdc.primary())
        // 次要强调色**不用** M3 派生的 tertiary：Expressive 从紫色种子派出来的
        // tertiary 落在黄绿上（浅色 #396B1F），和 success 绿（#40A02B）几乎分不开，
        // 界面上「强调」和「正常/成功」会混。这里固定成青色相（hue 182）：
        // 与紫色主色冷暖互补，又和 success 的绿拉开了色相距离。
        fun tealAt(t: Double) = "%06X".format(Hct.from(182.0, 26.0, t).toInt() and 0xFFFFFF)
        roles["accentSecondary"] = tealAt(if (isDark) 80.0 else 40.0)
        roles["success"] = LEGACY.getValue(themeKey).getValue("success")
        roles["warning"] = LEGACY.getValue(themeKey).getValue("warning")
        roles["danger"] = hex(mdc.error())
        // --- 1.0.9 新增：层次与描边（撑起卡片/底栏/分隔线的视觉层次）---
        roles["surfaceLow"] = at(ladder.getValue("surfaceLow"))
        roles["surfaceContainer"] = at(ladder.getValue("surfaceContainer"))
        roles["surfaceHigh"] = at(ladder.getValue("surfaceHigh"))
        roles["surfaceHighest"] = at(ladder.getValue("surfaceHighest"))
        roles["surfaceDim"] = at(ladder.getValue("surfaceDim"))
        roles["surfaceBright"] = at(ladder.getValue("surfaceBright"))
        // tertiary 系列同样走青色（理由同上），保证 M3 组件取 tertiary 时不会撞 success
        roles["tertiary"] = tealAt(if (isDark) 80.0 else 40.0)
        roles["onTertiary"] = tealAt(if (isDark) 20.0 else 98.0)
        roles["tertiaryContainer"] = tealAt(if (isDark) 30.0 else 90.0)
        roles["onTertiaryContainer"] = tealAt(if (isDark) 92.0 else 20.0)
        // 描边也自定义 tone：M3 默认的 outline（深 60 / 浅 50）压在最亮的那层
        // surface 上只有 ~2.9，达不到 3:1；这里加深到贴住 3:1 以上。
        roles["outline"] = at(if (isDark) 62.0 else 42.0)
        roles["outlineVariant"] = at(if (isDark) 38.0 else 74.0)
        roles["scrim"] = hex(mdc.scrim())
        roles["inverseSurface"] = hex(mdc.inverseSurface())

        // ---- 对比度校验 ----
        // 全部按**最坏情况**取底：文字可能落在最亮的那层 surface 上（深色 30 / 浅色 82），
        // 只校验 background 会漏掉卡片叠卡片时的可读性问题。
        // 深色下最坏的是最亮那层，浅色下最坏的是最暗那层（文字是深色）
        val worstSurface = if (isDark) {
            ladder.getValue("surfaceHighest")
        } else {
            ladder.getValue("surfaceDim")
        }
        val outlineTone = if (isDark) 62.0 else 42.0
        val checks = listOf(
            "textPrimary/最差表面层" to Pair(tone(mdc.onSurface()), worstSurface),
            "textSecondary/最差表面层" to Pair(textSecondaryTone, worstSurface),
            "onTertiaryContainer/tertiaryContainer" to Pair(
                if (isDark) 92.0 else 20.0, if (isDark) 30.0 else 90.0,
            ),
            "outline/最差表面层" to Pair(outlineTone, worstSurface),
        )
        val minRatio = mapOf(
            "textPrimary/最差表面层" to 4.5,
            "textSecondary/最差表面层" to 4.5,
            "onTertiaryContainer/tertiaryContainer" to 4.5,
            "outline/最差表面层" to 3.0,
        )
        report.appendLine("── $themeKey（${if (isDark) "深色" else "浅色"}）──")
        for ((name, tones) in checks) {
            val ratio = contrastRatio(tones.first, tones.second)
            val min = minRatio.getValue(name)
            val ok = ratio >= min
            report.appendLine(
                "  ${if (ok) "OK  " else "FAIL"} $name = %.2f（要求 ≥ %.1f）".format(ratio, min),
            )
            if (!ok) failures.add("$themeKey $name = %.2f < %.1f".format(ratio, min))
        }
        val ladderText = ladder.entries.sortedBy { it.value }
            .joinToString { "${it.key}=%.0f".format(it.value) }
        report.appendLine("  层次 tone 阶梯：$ladderText")
        report.appendLine()

        if (write) writePalette(themeKey, roles)
    }

    println(report)
    if (failures.isEmpty()) {
        println("对比度校验全部通过")
    } else {
        System.err.println("对比度校验失败，未写入：\n" + failures.joinToString("\n"))
        exitProcess(1)
    }
    if (write) println("已写回 $TOKENS_PATH")
}

/**
 * 把生成的色值写回 design-tokens.json 的 `palette.<theme>.semantic`。
 *
 * 只替换 semantic 块里的色值（保留 name/url/license 等元信息与文件其余内容），
 * 按行匹配 key，不认识的 key 原样保留。
 */
private fun writePalette(themeKey: String, roles: Map<String, String>) {
    val file = File(TOKENS_PATH)
    val text = file.readText()
    val themeIndex = text.indexOf("\"$themeKey\"")
    require(themeIndex >= 0) { "design-tokens.json 里找不到 $themeKey 段" }
    val semanticStart = text.indexOf("\"semantic\"", themeIndex)
    require(semanticStart >= 0) { "$themeKey 段里找不到 semantic" }
    val blockStart = text.indexOf('{', semanticStart)
    val blockEnd = text.indexOf('}', blockStart)
    val block = text.substring(blockStart + 1, blockEnd)

    var newBlock = block
    for ((key, value) in roles) {
        val regex = Regex("\"$key\"\\s*:\\s*\"#[0-9A-Fa-f]{6}\"")
        newBlock = if (regex.containsMatchIn(newBlock)) {
            regex.replace(newBlock, "\"$key\": \"#$value\"")
        } else {
            // 新增 key：追加到块末尾（8 空格缩进，与文件现有风格一致）
            newBlock.trimEnd().let { if (it.endsWith(",")) it else "$it," } +
                "\n        \"$key\": \"#$value\"\n      "
        }
    }
    file.writeText(text.substring(0, blockStart + 1) + newBlock + text.substring(blockEnd))
}
