package com.devperf.monitor.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.devperf.monitor.analysis.FocusedHealth
import com.devperf.monitor.core.CoreMetricId
import com.devperf.monitor.core.Metric
import com.devperf.monitor.core.MetricId
import com.devperf.monitor.core.MetricState
import com.devperf.monitor.data.collectors.DeviceSnapshot
import com.devperf.monitor.ui.kit.AnimatedNumber
import com.devperf.monitor.ui.kit.GlassCard
import com.devperf.monitor.ui.kit.ProgressRing
import com.devperf.monitor.ui.kit.metricColor
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.LocalTokenColors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 悬浮窗数据：核心指标 + 健康分所需的辅助量 + 1.0.26 扩展的实时明细。
 *
 * 1.0.26 之前只带三项（内存 / 温度 / 刷新率），用户要求「采集各种信息，CPU
 * 信息、GPU 信息、各种温度、电池电压电流、实时 FPS，加到悬浮窗」。这些量
 * [DeviceSnapshot] 里本来就有（采集器早已就绪），只是悬浮窗没取——这里直接
 * 透出，不新增采集路径。
 *
 * 三态规则不变：采不到的项不进卡片行（不放「—」占位），NUMBERS 态只挑
 * 四个核心大数字。FPS 走全局帧率（需 Shizuku/root，未授权时三态为
 * NeedsPermission，卡片行留引导、NUMBERS 留引导小字）。
 */
data class OverlayData(
    val memoryRatio: Metric<Float>,
    val deviceTemp: Metric<Float>,
    val refreshHz: Metric<Float>,
    val fps: Metric<Float>,
    // 1.0.26 扩展明细
    val cpuUsage: Metric<Float>,
    val gpuLoad: Metric<Float>,
    val cpuTemp: Metric<Float>,
    val gpuTemp: Metric<Float>,
    val batteryTemp: Metric<Float>,
    val batteryVoltage: Metric<Float>,
    val batteryCurrent: Metric<Float>,
    val batteryLevel: Metric<Float>,
) {
    companion object {
        fun from(
            fps: Metric<Float>,
            snapshot: DeviceSnapshot,
        ): OverlayData = OverlayData(
            memoryRatio = snapshot.memory.usageRatio,
            deviceTemp = snapshot.thermal.deviceTempC,
            refreshHz = snapshot.display.refreshRateHz,
            fps = fps,
            cpuUsage = snapshot.cpu.usagePercent,
            gpuLoad = snapshot.gpu.loadPercent,
            cpuTemp = snapshot.cpu.temperatureC,
            gpuTemp = snapshot.gpu.temperatureC,
            batteryTemp = snapshot.battery.temperatureC,
            batteryVoltage = snapshot.battery.voltageV,
            batteryCurrent = snapshot.battery.currentMa,
            batteryLevel = snapshot.battery.levelPercent,
        )
    }
}

/** 悬浮窗里的一格：指标 + 当前值；[value] 为 null 时显示 [hint] 引导（不消失）。 */
private data class GridCell(
    val id: MetricId,
    val value: Float?,
    val hint: String? = null,
)

/** 指标读不到时的引导文案：告诉用户去哪开启，而不是莫名其妙少一行。 */
private fun metricHint(state: MetricState<Float>): String = when (state) {
    is MetricState.NeedsPermission -> "需 ${state.permissionLabel}"
    is MetricState.Unavailable -> "暂无数据"
    else -> "暂无数据"
}

/**
 * 卡片态指标行顺序（1.0.26 扩展）。
 *
 * 排布原则：使用率（CPU/GPU/内存）→ 温度（CPU/GPU/电池/机身）→ 电池电气
 * （电压/电流）→ 显示（FPS/刷新率）。采不到的项由调用方过滤，行数自适应。
 */
private fun OverlayData.cardCells(): List<GridCell> = buildList {
    cpuUsage.value?.let { add(GridCell(MetricId.CPU_USAGE, it)) }
    gpuLoad.value?.let { add(GridCell(MetricId.GPU_LOAD, it)) }
    cpuTemp.value?.let { add(GridCell(MetricId.CPU_TEMP, it)) }
    gpuTemp.value?.let { add(GridCell(MetricId.GPU_TEMP, it)) }
    batteryTemp.value?.let { add(GridCell(MetricId.BATTERY_TEMP, it)) }
    deviceTemp.value?.let { add(GridCell(MetricId.DEVICE_TEMP, it)) }
    batteryVoltage.value?.let { add(GridCell(MetricId.BATTERY_VOLTAGE, it)) }
    batteryCurrent.value?.let { add(GridCell(MetricId.BATTERY_CURRENT, it)) }
    memoryRatio.value?.let { add(GridCell(MetricId.MEMORY_USAGE_RATIO, it)) }
    batteryLevel.value?.let { add(GridCell(MetricId.BATTERY_LEVEL, it)) }
    // FPS：未授权 Shizuku/root 时留引导行（缺权限才能解释清楚，不静默消失）
    val fpsValue = fps.value
    if (fpsValue != null) {
        add(GridCell(MetricId.FPS, fpsValue))
    } else {
        add(GridCell(MetricId.FPS, null, metricHint(fps.state)))
    }
    // 刷新率：不需要任何授权，读不到时留引导行而不是静默消失
    val refreshValue = refreshHz.value
    if (refreshValue != null) {
        add(GridCell(MetricId.REFRESH_RATE, refreshValue))
    } else {
        add(GridCell(MetricId.REFRESH_RATE, null, metricHint(refreshHz.state)))
    }
}

/**
 * 纯数字态的四个核心大数字（1.0.26 起含 FPS）。
 *
 * 1.0.17 用户要求「悬浮窗显示屏幕刷新率取代实时帧率」，1.0.26 又要求
 * 「实时帧率 FPS 加到悬浮窗」——两者并不互斥：卡片态全量展示，纯数字态
 * 取四个最常看的：CPU 使用率 / 设备温度 / FPS / 刷新率。
 */
private fun OverlayData.numbersCells(): List<GridCell> = buildList {
    cpuUsage.value?.let { add(GridCell(MetricId.CPU_USAGE, it)) }
    deviceTemp.value?.let { add(GridCell(MetricId.DEVICE_TEMP, it)) }
    // FPS 缺 Shizuku 授权时给引导小字（与卡片态一致，不静默消失）
    val fpsValue = fps.value
    if (fpsValue != null) {
        add(GridCell(MetricId.FPS, fpsValue))
    } else {
        add(GridCell(MetricId.FPS, null, metricHint(fps.state)))
    }
    val refreshValue = refreshHz.value
    if (refreshValue != null) {
        add(GridCell(MetricId.REFRESH_RATE, refreshValue))
    } else {
        add(GridCell(MetricId.REFRESH_RATE, null, metricHint(refreshHz.state)))
    }
}

/**
 * 悬浮窗内容：三态（卡片 / 纯数字 / 收起），单击循环切换。
 *
 * - CARD：默认形态。左侧综合评判小环 + 右侧指标竖排（1.0.26 起为全量明细：
 *   CPU/GPU 使用率、CPU/GPU/电池/机身温度、电池电压/电流/电量、内存、
 *   实时 FPS、屏幕刷新率，采不到的自动跳过）。一退出应用就稳定显示这张
 *   卡片，不自动变形（用户原话：「等它显示那张卡片，不要动它」）；
 * - NUMBERS：四个核心数字横排（CPU / 温度 / FPS / 刷新率）；
 * - COLLAPSED：收成一条竖线，点一下恢复完整卡片。
 *
 * 形态切换的形变动效在内容层完成（[AnimatedContent] + Expressive 弹簧），
 * 窗口尺寸本身只瞬切（见 OverlayController.applyLayout）。
 *
 * 采不到的指标不占位——悬浮窗寸土寸金，宁可少一格也不放「—」。
 */
@Composable
fun OverlayContent(
    state: OverlayController.UiState,
    data: OverlayData,
    health: FocusedHealth.Result,
    intervalMs: Long,
    callbacks: OverlayGestureCallbacks,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTokenColors.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(OverlayShadowPaddingDp.dp)
            .overlayGestures(callbacks),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = state.mode,
            transitionSpec = {
                // 形态切换：淡入 + 轻微放大（Expressive 默认空间弹簧 0.8/380）
                val springSpec = spring<Float>(
                    dampingRatio = 0.8f,
                    stiffness = 380f,
                )
                (
                    fadeIn(animationSpec = springSpec) +
                        scaleIn(initialScale = 0.92f, animationSpec = springSpec)
                    ) togetherWith fadeOut(animationSpec = tween(durationMillis = 120))
            },
            label = "overlayMode",
        ) { mode ->
            when (mode) {
                OverlayController.Mode.COLLAPSED -> CollapsedStrip(state)

                OverlayController.Mode.NUMBERS -> NumbersStrip(data, intervalMs)

                OverlayController.Mode.CARD -> CardContent(
                    state = state,
                    data = data,
                    health = health,
                    intervalMs = intervalMs,
                )
            }
        }
    }
}

/** 卡片态：完整内容（小环 + 指标竖排）。告警时容器整体转危险色。 */
@Composable
private fun CardContent(
    state: OverlayController.UiState,
    data: OverlayData,
    health: FocusedHealth.Result,
    intervalMs: Long,
) {
    val colors = LocalTokenColors.current
    // 悬浮窗玻璃化（1.0.26）：backdrop 由 OverlayService 在窗口内提供
    // （录制窗口内的品牌壁纸背景层，结构同 AppNav），GlassCard 的 glass
    // 分支自动生效；backdrop 为 null 时（plain flavor）仍回落主题表面色。
    // 高温告警时整张卡片转危险色低透明度，数字保持各自的指标色。
    val container = if (state.alert) {
        colors.danger.copy(alpha = 0.22f)
    } else {
        colors.surface
    }
    GlassCard(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(DesignTokens.Radius.CARD.dp),
        containerColor = container,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HealthBadge(health)
            Spacer(modifier = Modifier.width(10.dp))
            MetricGrid(data, intervalMs, Modifier.weight(1f))
        }
    }
}

/** 综合评判小环：分数 + 一圈进度，颜色按档位变。 */
@Composable
private fun HealthBadge(health: FocusedHealth.Result) {
    val colors = LocalTokenColors.current
    val total = health.total
    val tint = when {
        total == null -> colors.textSecondary
        total >= 75f -> colors.success
        total >= 50f -> colors.accent
        else -> colors.danger
    }
    ProgressRing(
        progress = (total ?: 0f) / 100f,
        // 三个维度全采不到就不给分：环留空，不写占位数字
        centerText = total?.let { "${it.toInt()}" } ?: "",
        caption = "",
        color = tint,
        trackColor = tint.copy(alpha = 0.20f),
        modifier = Modifier.size(54.dp),
        strokeWidth = 5.dp,
    )
}

/**
 * 卡片态指标区：竖排多行（1.0.26 起为全量明细，见 [cardCells]）。
 *
 * 行数随「采得到多少」自适应：行少时 SpaceEvenly 自动稀疏，行多时压缩到
 * 每行 18dp（标签 labelSmall + 数值 bodyLarge），10 行也能落在卡片内。
 * 只放采得到的，采不到的直接不进列表（悬浮窗寸土寸金，不放占位符）——
 * 例外：刷新率不需要授权却读不到时要留一行引导，否则用户看不出是没做还是没读到。
 */
@Composable
private fun MetricGrid(
    data: OverlayData,
    intervalMs: Long,
    modifier: Modifier = Modifier,
) {
    val cells = data.cardCells()

    if (cells.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "读不到指标",
                style = MaterialTheme.typography.labelSmall,
                color = LocalTokenColors.current.textSecondary,
            )
        }
        return
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        cells.forEach { cell -> MetricRow(cell, intervalMs) }
    }
}

/** 一行指标：标签靠左、数值靠右对齐；无值时显示灰色引导小字。 */
@Composable
private fun MetricRow(
    cell: GridCell,
    intervalMs: Long,
) {
    val colors = LocalTokenColors.current
    val accent = metricColor(cell.id.colorKey)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = cell.id.label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
            // 标签不抢数值的位置，超出就截断（悬浮窗宽度有限）
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (cell.value != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                AnimatedNumber(
                    value = cell.value,
                    intervalMs = intervalMs,
                    decimals = cell.id.decimals,
                    color = accent,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = " ${cell.id.unit}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                )
            }
        } else {
            // 引导行：灰色小字，告诉用户缺什么（如「需 Shizuku 授权」）
            Text(
                text = cell.hint ?: "暂无数据",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

/**
 * 纯数字态：只要核心数字，不要卡片（1.0.26 起四格横排）。
 *
 * 无容器、无阴影、无小环——四个数字直接横排浮在屏幕上，每个数字保持
 * 自己的指标色；告警时统一转危险色（没有卡片可染，染数字）。
 * FPS 缺 Shizuku 授权时显示引导小字（与卡片态一致，不静默消失）。
 */
@Composable
private fun NumbersStrip(
    data: OverlayData,
    intervalMs: Long,
) {
    val colors = LocalTokenColors.current
    val cells = data.numbersCells()

    if (cells.isEmpty()) {
        // 纯数字态没有容器，缺数据时给一行小字兜底（避免空白窗口看不出所以然）
        Text(
            text = "读不到指标",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
        )
        return
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        cells.forEach { cell ->
            if (cell.value != null) {
                val numberColor = metricColor(cell.id.colorKey)
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    AnimatedNumber(
                        value = cell.value,
                        intervalMs = intervalMs,
                        decimals = cell.id.decimals,
                        color = numberColor,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = cell.id.unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                    )
                }
            } else {
                // 引导行（如「需 Shizuku 授权」）：占一等宽格，保持布局稳定
                Text(
                    text = cell.hint ?: "暂无数据",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 收起态：贴边后的窄条 + 提示点，点击恢复完整卡片。 */
@Composable
private fun CollapsedStrip(state: OverlayController.UiState) {
    val colors = LocalTokenColors.current
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 5.dp, height = 34.dp)
                .drawBehind {
                    drawRoundRect(
                        color = if (state.alert) colors.danger else colors.accent,
                        cornerRadius = CornerRadius(size.width / 2f),
                    )
                },
        )
        Box(
            modifier = Modifier
                .padding(top = 52.dp)
                .size(4.dp)
                .drawBehind { drawCircle(color = colors.accent.copy(alpha = 0.8f)) },
        )
    }
}

// ---------------------------------------------------------------------------
// 悬浮窗背景层：品牌壁纸 + 罩层（1.0.26 悬浮窗玻璃化的采样源）
//
// 悬浮窗是独立窗口，跨窗口捕获背后应用画面没有意义（库文档明确 backdrop
// 只录本窗口内容），所以玻璃采样的是「窗口内画的品牌壁纸」：与 AppNav 同一
// 张浅蓝绿壁纸 + 竖向罩层，视觉上悬浮窗就是一块浮在屏幕上的品牌玻璃板。
// 结构与 AppNav 的背景层同构——录制节点只挂这个纯背景 Box，玻璃内容
// （GlassCard）在录制子树之外，杜绝 1.0.13 的自指采样崩溃。
// 不放公司 logo 角标：悬浮窗只有 272dp 宽，角标会占掉三分之一。
// ---------------------------------------------------------------------------
internal fun DrawScope.drawOverlayWallpaper(wallpaper: ImageBitmap) {
    val w = size.width
    val h = size.height

    // 壁纸满铺（center-crop，与 AppNav 同一算法）
    val scale = max(w / wallpaper.width, h / wallpaper.height)
    val dw = wallpaper.width * scale
    val dh = wallpaper.height * scale
    drawImage(
        image = wallpaper,
        dstOffset = IntOffset(((w - dw) / 2f).roundToInt(), ((h - dh) / 2f).roundToInt()),
        dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
    )

    // 竖向罩层：悬浮窗是深色文字 + 浅色壁纸，同 AppNav 的压暗逻辑，
    // 浓度取 AppNav 调亮后的同档（0.30/0.16/0.34），中部最轻
    drawRect(
        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
            0.00f to Color.Black.copy(alpha = 0.30f),
            0.50f to Color.Black.copy(alpha = 0.16f),
            1.00f to Color.Black.copy(alpha = 0.34f),
        ),
    )
}
