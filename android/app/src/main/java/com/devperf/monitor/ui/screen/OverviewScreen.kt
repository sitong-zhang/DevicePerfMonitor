package com.devperf.monitor.ui.screen

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devperf.monitor.analysis.FocusedHealth
import com.devperf.monitor.core.CoreMetricId
import com.devperf.monitor.core.MetricViewMode
import com.devperf.monitor.data.defaultMetricViewModes
import com.devperf.monitor.data.storage.HistoryPoint
import com.devperf.monitor.ui.MonitorViewModel
import com.devperf.monitor.ui.kit.AnimatedNumber
import com.devperf.monitor.ui.kit.BarSeries
import com.devperf.monitor.ui.kit.EnterStagger
import com.devperf.monitor.ui.kit.enterSpec
import com.devperf.monitor.ui.kit.GlassCard
import com.devperf.monitor.ui.kit.LevelBars
import com.devperf.monitor.ui.kit.ProgressRing
import com.devperf.monitor.ui.kit.Sparkline
import com.devperf.monitor.ui.kit.metricColor
import com.devperf.monitor.ui.kit.pressScale
import com.devperf.monitor.ui.kit.rememberHaptics
import com.devperf.monitor.ui.kit.shimmer
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.LocalTokenColors

/** 卡片上的迷你轨迹看最近 1 分钟。 */
private const val CARD_WINDOW_SEC = 60

/**
 * 主界面：综合评判 + 四个核心指标。
 *
 * 两条硬规则：
 * 1. 只显示这四个指标，其余一概不进界面；
 * 2. 采不到的指标不占位、不显示 0——没数据就当它不存在。
 */
@Composable
fun OverviewScreen(
    viewModel: MonitorViewModel,
    onOpenDetail: (CoreMetricId) -> Unit,
    onOpenDeepDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focused by viewModel.focused.collectAsState()
    val settings by viewModel.settings.collectAsState()
    // 深度指标卡（1.0.42 全量版）：这台设备能读到的所有深度指标全部进总览
    val deep by viewModel.deep.collectAsState()
    val deepCards = remember(deep) { buildDeepCards(deep) }
    val colors = LocalTokenColors.current

    val visible = focused.readings.filter { it.available }
    // 首帧还没采上来：先给骨架，别让界面先闪一下空白再跳成有内容
    val awaitingFirstSample = focused.updatedAtMs <= 0L && visible.isEmpty()

    // 1.0.24：列表背景改为透明——壁纸（公司 logo）像 Kyant0 catalog demo 那样
    // 直接可见，玻璃卡片浮在它上面。以前这里刷了一层不透明主题渐变，壁纸被完全
    // 遮住，只能透过玻璃看见，logo 等于白换。
    LazyColumn(
        modifier = modifier
            .fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            // 1.0.48：顶部留白从 72dp 收回 24dp（原为公司 logo 角标让位，角标已移除）
            top = 24.dp,
            end = 16.dp,
            // 底部必须给浮动胶囊底栏让出空间（底栏高度 + 下边距 + 一点呼吸），
            // 否则最后一张卡片会被底栏压住——1.0.9 之前没留，列表滚到底也看不见末尾。
            // 同样处理底部安全区 inset。
            bottom = DesignTokens.BottomBar.HEIGHT.dp +
                DesignTokens.BottomBar.MARGIN_BOTTOM.dp + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Surface.GAP.dp),
    ) {
        if (awaitingFirstSample) {
            items(count = 5, key = { "skeleton-$it" }) { index ->
                SkeletonCard(index = index)
            }
            return@LazyColumn
        }

        // 1.0.49：补上信息层级——Bento 网格只是排布，真正让画面「有设计感」的
        // 是分区标题带来的节奏（大 Hero → 分区小标 → 卡片群）。小标用次级色
        // 的小字号 + 字距，不抢卡片戏份（Apple HIG 的 section header 做法）。
        item(key = "cap_hero") {
            OverviewSectionCaption(text = "综合评判")
        }

        item(key = "health") {
            EnterStagger(index = 0) {
                HealthCard(result = focused.health)
            }
        }

        // 1.0.51 多形态 Bento（不再全是同款半宽卡）：
        // - 首个可见指标 → 全宽「特性卡」：大数字 + 面积波形 + 最值标注；
        // - 其余指标 → 半宽小卡，按指标类型分配专属形态：
        //   内存 → 环形 / 温度 → 电平条 / 刷新率 → 柱状 / 帧率 → 波形；
        // - 落单的最后一张占满整行。
        if (visible.isNotEmpty()) {
            item(key = "feature") {
                EnterStagger(index = 1) {
                    FeatureMetricCard(
                        reading = visible.first(),
                        history = viewModel.history(visible.first().id.source, CARD_WINDOW_SEC),
                        onClick = { onOpenDetail(visible.first().id) },
                    )
                }
            }
        }
        val rest = visible.drop(1)
        itemsIndexed(
            items = rest.chunked(2),
            key = { _, row -> row.joinToString("_") { it.id.name } },
        ) { rowIndex, row ->
            if (row.size == 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Surface.GAP.dp)) {
                    row.forEachIndexed { col, reading ->
                        Box(Modifier.weight(1f)) {
                            EnterStagger(index = rowIndex * 2 + col + 2) {
                                HalfMetricCard(
                                    reading = reading,
                                    history = viewModel.history(reading.id.source, CARD_WINDOW_SEC),
                                    onClick = { onOpenDetail(reading.id) },
                                )
                            }
                        }
                    }
                }
            } else {
                val reading = row.first()
                EnterStagger(index = rowIndex * 2 + 2) {
                    HalfMetricCard(
                        reading = reading,
                        history = viewModel.history(reading.id.source, CARD_WINDOW_SEC),
                        onClick = { onOpenDetail(reading.id) },
                    )
                }
            }
        }

        // 深度指标卡：同样两列 Bento（数值小卡），落单的占满整行。
        val deepRows = deepCards.chunked(2)
        if (deepRows.isNotEmpty()) {
            item(key = "cap_deep") {
                OverviewSectionCaption(text = "深度指标", topExtra = true)
            }
        }
        itemsIndexed(items = deepRows, key = { _, c -> "deep-${c.first().key}" }) { rowIndex, row ->
            if (row.size == 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Surface.GAP.dp)) {
                    row.forEachIndexed { col, card ->
                        Box(Modifier.weight(1f)) {
                            EnterStagger(index = rowIndex * 2 + col + 1 + visible.size) {
                                DeepCard(
                                    card = card,
                                    history = if (card.value != null) {
                                        viewModel.deepHistory(card.key, CARD_WINDOW_SEC)
                                    } else {
                                        emptyList()
                                    },
                                    compact = true,
                                    onClick = { onOpenDeepDetail(card.key) },
                                )
                            }
                        }
                    }
                }
            } else {
                val card = row.first()
                EnterStagger(index = rowIndex * 2 + 1 + visible.size) {
                    DeepCard(
                        card = card,
                        history = if (card.value != null) {
                            viewModel.deepHistory(card.key, CARD_WINDOW_SEC)
                        } else {
                            emptyList()
                        },
                        onClick = { onOpenDeepDetail(card.key) },
                    )
                }
            }
        }

        if (visible.isEmpty()) {
            item(key = "empty") {
                EmptyState()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 深度指标卡（1.0.42 全量版）
// ---------------------------------------------------------------------------

/** 深度分类 → 卡片强调色轮换（与核心卡同一组语义色），总览卡与深度详情页共用。 */
internal val DEEP_CATEGORY_COLORS =
    listOf("accent", "accentSecondary", "success", "warning", "danger")

/** 总览页深度指标卡：数据来自深度监测快照（监测页「数据来源」同一套）。 */
private data class DeepCardData(
    val key: String,
    val label: String,
    val unit: String,
    /** 数值行的当前值（画波形用）。 */
    val value: Float?,
    /** 当前值的显示字符串（数值行与文本行都用它，保真不重排格式）。 */
    val display: String,
    val colorKey: String,
    /** 推算依据 / 备注（仅推算行展示）。 */
    val note: String?,
    val estimated: Boolean,
)

/**
 * 从深度快照提取总览卡数据：这台设备**能读到的所有**深度指标全部进总览
 * （实测 + 推算都算读到的），读不到的行如实不占位。保持采集器的分组顺序。
 */
private fun buildDeepCards(snap: com.devperf.monitor.core.DeepSnapshot): List<DeepCardData> {
    val cards = ArrayList<DeepCardData>()
    for (section in snap.sections) {
        val colorKey = DEEP_CATEGORY_COLORS[section.category.ordinal % DEEP_CATEGORY_COLORS.size]
        for (row in section.rows) {
            if (row.origin == com.devperf.monitor.core.DeepOrigin.UNAVAILABLE) continue
            val raw = row.value ?: continue
            if (raw == "—") continue
            val v = raw.toFloatOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
            cards += DeepCardData(
                key = row.key,
                label = row.label,
                unit = row.unit,
                value = v,
                display = raw,
                colorKey = colorKey,
                note = row.note,
                estimated = row.isEstimated,
            )
        }
    }
    return cards
}

/**
 * 视觉与核心指标卡完全一致（WAVE 形态：左侧大数字 + 右侧 60 秒波形），
 * 可点击进详情。文本值行（governor / 充电状态等）没有波形意义：左半显示
 * 当前文本，右上角标「实时」。
 */
@Composable
private fun DeepCard(
    card: DeepCardData,
    history: List<Float>,
    onClick: () -> Unit,
    compact: Boolean = false,
) {
    val colors = LocalTokenColors.current
    val accent = metricColor(card.colorKey)
    val interactionSource = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()

    if (compact) {
        // 1.0.48 Bento 半宽紧凑卡：标签 + 大数字 + 迷你波形，纵向堆叠
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .height(124.dp)
                .pressScale(interactionSource),
            shape = RoundedCornerShape(DesignTokens.Radius.CARD.dp),
            onClick = {
                haptics.tick()
                onClick()
            },
            interactionSource = interactionSource,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = card.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            card.estimated -> "推算"
                            card.value != null -> "波形"
                            else -> "实时"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = accent.copy(alpha = 0.75f),
                    )
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = card.display,
                        style = MaterialTheme.typography.headlineSmall,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (card.unit.isNotBlank()) {
                        Text(
                            text = " ${card.unit}",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
                if (card.value != null) {
                    Sparkline(
                        points = history,
                        color = accent,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .fillMaxWidth()
                            .height(34.dp),
                    )
                } else if (card.estimated && !card.note.isNullOrBlank()) {
                    Text(
                        text = "推算：${card.note}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        return
    }

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .pressScale(interactionSource),
        shape = RoundedCornerShape(DesignTokens.Radius.CARD.dp),
        onClick = {
            haptics.tick()
            onClick()
        },
        interactionSource = interactionSource,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = card.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        card.estimated -> "推算"
                        card.value != null -> "波形"
                        else -> "实时"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = accent.copy(alpha = 0.75f),
                )
            }
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (card.value != null) {
                    Box(
                        modifier = Modifier
                            .weight(0.42f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = card.display,
                                style = MaterialTheme.typography.headlineSmall,
                                color = accent,
                                maxLines = 1,
                            )
                            Text(
                                text = " ${card.unit}",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                    }
                    Sparkline(
                        points = history,
                        color = accent,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                    )
                } else {
                    Text(
                        text = card.display,
                        style = if (card.display.length <= 8) {
                            MaterialTheme.typography.headlineSmall
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        color = accent,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = " ${card.unit}",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )
                }
            }
            if (card.estimated && !card.note.isNullOrBlank()) {
                Text(
                    text = "推算：${card.note}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 环形只有 0~100 有界的指标用得上（内存占用率）。
 * 温度 / 刷新率 / 帧率选了环形就回落到波形，避免把没有上限的值硬塞进圆环。
 */
private fun effectiveMode(id: CoreMetricId, mode: MetricViewMode): MetricViewMode =
    if (mode == MetricViewMode.RING && id != CoreMetricId.MEMORY_RATIO) {
        MetricViewMode.WAVE
    } else {
        mode
    }

// ---------------------------------------------------------------------------
// 骨架屏
// ---------------------------------------------------------------------------

@Composable
private fun SkeletonCard(index: Int) {
    val colors = LocalTokenColors.current
    val shape = RoundedCornerShape(
        (if (index == 0) DesignTokens.Radius.LARGE else DesignTokens.Radius.CARD).dp,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (index == 0) 190.dp else 132.dp)
            .background(
                color = colors.surface.copy(alpha = 0.42f),
                shape = shape,
            )
            .shimmer(colors.textPrimary),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(if (index == 0) 128.dp else 76.dp)
                    .background(
                        color = colors.textSecondary.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(DesignTokens.Radius.PILL.dp),
                    ),
            )
            Column(
                modifier = Modifier
                    .padding(start = 18.dp)
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.42f)
                        .height(14.dp)
                        .background(
                            color = colors.textSecondary.copy(alpha = 0.22f),
                            shape = RoundedCornerShape(DesignTokens.Radius.PILL.dp),
                        ),
                )
                Box(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth(0.72f)
                        .height(12.dp)
                        .background(
                            color = colors.textSecondary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(DesignTokens.Radius.PILL.dp),
                        ),
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    val colors = LocalTokenColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "这台设备暂时读不到这些指标",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "能采到的会自动出现，采不到的不会占位",
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// 综合评判
// ---------------------------------------------------------------------------

/** 分区小标题：给 Bento 卡片群分组，制造视觉节奏（Apple HIG 的 section header 做法）。 */
@Composable
private fun OverviewSectionCaption(text: String, topExtra: Boolean = false) {
    val colors = LocalTokenColors.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.6.sp),
        color = colors.textSecondary,
        modifier = Modifier.padding(
            start = 4.dp,
            top = if (topExtra) 22.dp else 2.dp,
            bottom = 2.dp,
        ),
    )
}
@Composable
private fun HealthCard(result: FocusedHealth.Result) {
    val colors = LocalTokenColors.current
    val total = result.total
    val accent = when {
        total == null -> colors.textSecondary
        total >= 75f -> colors.success
        total >= 50f -> colors.accent
        else -> colors.danger
    }

    // 固定高度：LazyColumn 的 item 同样拿到无界高度约束，heightIn(min) 会让
    // 卡片按内容收缩、fillMaxSize 退化成 wrap、内容顶到卡顶（1.0.14 修复）。
    // 1.0.51 增高到 216dp：右侧环形下方补三维度档位条（多形态，不只是环）。
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(216.dp),
        shape = RoundedCornerShape(DesignTokens.Radius.LARGE.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProgressRing(
                    progress = (total ?: 0f) / 100f,
                    // 三个维度全采不到就不给分：圆环留空、不写占位数字
                    centerText = total?.let { "${it.toInt()}" } ?: "",
                    caption = result.grade?.label ?: "采集中",
                    color = accent,
                    trackColor = accent.copy(alpha = 0.18f),
                    modifier = Modifier.size(118.dp),
                )
                Column(
                    modifier = Modifier
                        .padding(start = 18.dp)
                        .weight(1f),
                ) {
                    Text(
                        text = "综合评判",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = result.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    result.weakest?.let { weakest ->
                        Text(
                            text = "最短板：${weakest.label}",
                            style = MaterialTheme.typography.labelMedium,
                            color = accent,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            // 1.0.51：三维度分数档位条——Hero 不再只是「环 + 一句话」
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DimensionBar(
                    label = "流畅",
                    score = result.smoothnessScore,
                    color = colors.accent,
                    modifier = Modifier.weight(1f),
                )
                DimensionBar(
                    label = "温度",
                    score = result.temperatureScore,
                    color = colors.warning,
                    modifier = Modifier.weight(1f),
                )
                DimensionBar(
                    label = "内存",
                    score = result.memoryScore,
                    color = colors.success,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 维度档位条：0~100 分映射到一条圆角横条 + 分数。分数缺失时只画空轨道。 */
@Composable
private fun DimensionBar(
    label: String,
    score: Float?,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTokenColors.current
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = score?.let { "${it.toInt()}" } ?: "—",
                style = MaterialTheme.typography.labelSmall,
                color = if (score != null) color else colors.textSecondary,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
        }
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .height(5.dp)
                .background(color.copy(alpha = 0.16f), RoundedCornerShape(3.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((score ?: 0f) / 100f)
                    .fillMaxHeight()
                    .background(color, RoundedCornerShape(3.dp)),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 指标卡
// ---------------------------------------------------------------------------

/**
 * 全宽「特性卡」（1.0.51 多形态重排）：大数字 + 面积波形 + 最值标注。
 * 给「当前值最重要」的第一个可见指标，视觉权重最高。
 */
@Composable
private fun FeatureMetricCard(
    reading: MonitorViewModel.CoreReading,
    history: List<HistoryPoint>,
    onClick: () -> Unit,
) {
    val colors = LocalTokenColors.current
    val accent = metricColor(reading.id.source.colorKey)
    val values = history.map { it.value }
    val interactionSource = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(168.dp)
            .pressScale(interactionSource),
        shape = RoundedCornerShape(DesignTokens.Radius.CARD.dp),
        onClick = {
            haptics.tick()
            onClick()
        },
        interactionSource = interactionSource,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = reading.id.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "60 秒",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent.copy(alpha = 0.75f),
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                AnimatedNumber(
                    value = reading.value ?: 0f,
                    intervalMs = 0L,
                    decimals = reading.id.decimals,
                    color = accent,
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    text = " ${reading.id.unit}",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            if (values.size >= 2) {
                Sparkline(
                    points = values,
                    color = accent,
                    fillAlpha = 0.26f,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .weight(1f),
                )
                val lo = values.min()
                val hi = values.max()
                Text(
                    text = "低 $lo · 高 $hi",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * 半宽小卡（1.0.51 多形态重排）：按指标类型分配专属可视化形态——
 * 内存 → 环形仪表 / 温度 → 分段电平条 / 刷新率 → 柱状 / 帧率 → 波形线。
 * 同一屏四种形态混排，不再全是「数字 + 小波形」的复制粘贴。
 */
@Composable
private fun HalfMetricCard(
    reading: MonitorViewModel.CoreReading,
    history: List<HistoryPoint>,
    onClick: () -> Unit,
) {
    val colors = LocalTokenColors.current
    val accent = metricColor(reading.id.source.colorKey)
    val values = history.map { it.value }
    val interactionSource = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(148.dp)
            .pressScale(interactionSource),
        shape = RoundedCornerShape(DesignTokens.Radius.CARD.dp),
        onClick = {
            haptics.tick()
            onClick()
        },
        interactionSource = interactionSource,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = reading.id.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                when (reading.id) {
                    CoreMetricId.MEMORY_RATIO -> Text("环形", style = MaterialTheme.typography.labelSmall, color = accent.copy(alpha = 0.75f))
                    CoreMetricId.DEVICE_TEMP -> Text("档位", style = MaterialTheme.typography.labelSmall, color = accent.copy(alpha = 0.75f))
                    CoreMetricId.REFRESH_RATE -> Text("分布", style = MaterialTheme.typography.labelSmall, color = accent.copy(alpha = 0.75f))
                    else -> Text("波形", style = MaterialTheme.typography.labelSmall, color = accent.copy(alpha = 0.75f))
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.CenterStart,
            ) {
                when (reading.id) {
                    // 内存：有界 0~100，环形仪表最直观
                    CoreMetricId.MEMORY_RATIO -> Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressRing(
                            progress = (reading.value ?: 0f) / 100f,
                            centerText = reading.value?.let { "${it.toInt()}" } ?: "",
                            caption = "%",
                            color = accent,
                            trackColor = accent.copy(alpha = 0.18f),
                            modifier = Modifier.size(58.dp),
                            strokeWidth = 7.dp,
                        )
                        Column(Modifier.padding(start = 12.dp)) {
                            AnimatedNumber(
                                value = reading.value ?: 0f,
                                intervalMs = 0L,
                                decimals = reading.id.decimals,
                                color = accent,
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                text = "占用越高越吃力",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary,
                            )
                        }
                    }

                    else -> AnimatedNumber(
                        value = reading.value ?: 0f,
                        intervalMs = 0L,
                        decimals = reading.id.decimals,
                        color = accent,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }
            when (reading.id) {
                CoreMetricId.DEVICE_TEMP -> LevelBars(
                    points = values,
                    color = accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp),
                )

                CoreMetricId.REFRESH_RATE -> BarSeries(
                    points = values,
                    color = accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp),
                )

                else -> if (reading.id != CoreMetricId.MEMORY_RATIO && values.isNotEmpty()) {
                    Sparkline(
                        points = values,
                        color = accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(30.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ValueText(
    reading: MonitorViewModel.CoreReading,
    tickMs: Long,
    color: Color,
    style: TextStyle,
) {
    val colors = LocalTokenColors.current
    val value = reading.value
    // 采不到就不占位：这张卡压根不会进来（visible 已过滤），这里是双保险
    if (value == null) return
    Row(verticalAlignment = Alignment.Bottom) {
        AnimatedNumber(
            value = value,
            intervalMs = tickMs,
            decimals = reading.id.decimals,
            color = color,
            style = style,
        )
        Text(
            text = " ${reading.id.unit}",
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}
