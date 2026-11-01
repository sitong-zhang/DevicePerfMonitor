package com.devperf.monitor.ui.screen

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devperf.monitor.analysis.FocusedHealth
import com.devperf.monitor.core.CoreMetricId
import com.devperf.monitor.core.MetricViewMode
import com.devperf.monitor.data.storage.HistoryPoint
import com.devperf.monitor.ui.MonitorViewModel
import com.devperf.monitor.ui.kit.AnimatedNumber
import com.devperf.monitor.ui.kit.BarSeries
import com.devperf.monitor.ui.kit.EnterStagger
import com.devperf.monitor.ui.kit.GlassButton
import com.devperf.monitor.ui.kit.GlassButtonVariant
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
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

/** 卡片上的迷你轨迹看最近 1 分钟。 */
private const val CARD_WINDOW_SEC = 60

/**
 * 主界面：综合评判（固定 HERO）+ 一组可自由自定义的指标卡。
 *
 * 自定义能力（长按任意卡片进入编辑态）：
 * 1. 拖动排序（任意卡片长按抓手拖动）；
 * 2. 切换尺寸：半宽(1×1) ↔ 全宽(2×1)；
 * 3. 切换显示形态：数字 / 波形 / 环形 / 柱状；
 * 4. 一键重置单卡到默认。
 * 顺序与每卡配置持久化到 SharedPreferences，重启保留。
 *
 * 两条硬规则保持不变：只显示能读到的指标；采不到的不占位、不显示 0。
 */
@Composable
fun OverviewScreen(
    viewModel: MonitorViewModel,
    onOpenDetail: (CoreMetricId) -> Unit,
    onOpenDeepDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focused by viewModel.focused.collectAsState()
    val deep by viewModel.deep.collectAsState()
    val deepCards = remember(deep) { buildDeepCards(deep) }
    val colors = LocalTokenColors.current
    val context = LocalContext.current

    val visible = focused.readings.filter { it.available }
    val awaitingFirstSample = focused.updatedAtMs <= 0L && visible.isEmpty()

    // ---- 自定义布局（顺序 + 每卡尺寸/形态），持久化 ----
    val allSlots: List<Slot> = remember(focused, deepCards) {
        val core = CoreMetricId.ordered.map { id ->
            Slot.Core(id, focused.readings.firstOrNull { it.id == id })
        }
        val deepSlots = deepCards.map { Slot.Deep(it) }
        core + deepSlots
    }
    val existingKeys = allSlots.map { it.key }

    val prefs = remember { OverviewLayoutPrefs.load(context) }
    var order by remember {
        mutableStateOf(syncOrder(prefs.order, existingKeys))
    }
    var config by remember { mutableStateOf(prefs.config) }
    var editing by remember { mutableStateOf(false) }

    fun persist() = OverviewLayoutPrefs.save(context, OverviewLayout(order, config))

    val orderedSlots = remember(order, existingKeys) {
        val orderSet = order.toSet()
        (order.filter { it in existingKeys }) + existingKeys.filter { it !in orderSet }
    }.mapNotNull { k -> allSlots.firstOrNull { it.key == k } }
    val visibleSlots = orderedSlots.filter { slotAvailable(it) }

    // onMove 闭包会被 reorderable 缓存，需用 State 持有最新 visibleSlots 避免用旧列表排序
    val visibleSlotsState = rememberUpdatedState(visibleSlots)

    val lazyGridState = rememberLazyGridState()
    val reorderableState = rememberReorderableLazyGridState(lazyGridState) { from, to ->
        // 网格第 0 项是固定的综合评判卡，可排卡片从索引 1 起；减去偏移得到 visibleSlots 下标
        val vs = visibleSlotsState.value
        val fromSlot = vs.getOrNull(from.index - 1) ?: return@rememberReorderableLazyGridState
        val toSlot = vs.getOrNull(to.index - 1) ?: return@rememberReorderableLazyGridState
        val newOrder = order.toMutableList()
        val fi = newOrder.indexOf(fromSlot.key)
        val ti = newOrder.indexOf(toSlot.key)
        if (fi < 0 || ti < 0) return@rememberReorderableLazyGridState
        newOrder.removeAt(fi)
        newOrder.add(ti, fromSlot.key)
        order = newOrder
        persist()
    }

    fun updateConfig(key: String, transform: (CardConfig) -> CardConfig) {
        val slot = allSlots.firstOrNull { it.key == key } ?: return
        val base = config[key] ?: CardConfig(CardSize.HALF, defaultModeFor(slot))
        config = config.toMutableMap().apply { put(key, transform(base)) }.toMap()
        persist()
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (awaitingFirstSample) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, top = 24.dp, end = 16.dp,
                    bottom = DesignTokens.BottomBar.HEIGHT.dp +
                        DesignTokens.BottomBar.MARGIN_BOTTOM.dp + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.Surface.GAP.dp),
                horizontalArrangement = Arrangement.spacedBy(DesignTokens.Surface.GAP.dp),
            ) {
                item(span = { GridItemSpan(2) }) { SkeletonCard(index = 0, full = true) }
                items(count = 4, key = { "sk-$it" }) { index -> SkeletonCard(index = index, full = false) }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = lazyGridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = if (editing) 56.dp else 24.dp,
                    end = 16.dp,
                    bottom = DesignTokens.BottomBar.HEIGHT.dp +
                        DesignTokens.BottomBar.MARGIN_BOTTOM.dp + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.Surface.GAP.dp),
                horizontalArrangement = Arrangement.spacedBy(DesignTokens.Surface.GAP.dp),
            ) {
                // 综合评判：固定 HERO，不参与拖动/缩放
                item(key = "health", span = { GridItemSpan(2) }) {
                    EnterStagger(index = 0) { HealthCard(result = focused.health) }
                }

                itemsIndexed(
                    items = visibleSlots,
                    key = { _, slot -> slot.key },
                    span = { index, _ -> GridItemSpan(config[visibleSlots[index].key]?.size?.span ?: 1) },
                ) { _, slot ->
                    ReorderableItem(reorderableState, key = slot.key) { _ ->
                        // draggableHandle / longPressDraggableHandle 是 ReorderableItem 作用域内的成员扩展，
                        // 必须在此处创建再往下传：编辑态长按卡片即可拖动排序。
                        val dragModifier = if (editing) {
                            Modifier.longPressDraggableHandle()
                        } else {
                            Modifier
                        }
                        val cfg = config[slot.key]
                            ?: CardConfig(CardSize.HALF, defaultModeFor(slot))
                        CustomizableMetricCard(
                            slot = slot,
                            cfg = cfg,
                            editing = editing,
                            dragModifier = dragModifier,
                            viewModel = viewModel,
                            onEnterEdit = { editing = true },
                            onToggleSize = { updateConfig(slot.key) { c -> c.copy(size = if (c.size == CardSize.FULL) CardSize.HALF else CardSize.FULL) } },
                            onCycleMode = { updateConfig(slot.key) { c -> c.copy(mode = cycleMode(c.mode)) } },
                            onReset = { config = config - slot.key; persist() },
                            onOpen = {
                                when (slot) {
                                    is Slot.Core -> slot.reading?.let { onOpenDetail(it.id) }
                                    is Slot.Deep -> onOpenDeepDetail(slot.card.key)
                                }
                            },
                        )
                    }
                }

                if (visible.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(2) }) { EmptyState() }
                }
            }
        }

        // 右上角编辑开关（浮钮，不占网格索引，避免拖动下标错位）
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 14.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            Column(horizontalAlignment = Alignment.End) {
                GlassButton(
                    text = if (editing) "完成" else "编辑",
                    onClick = { editing = !editing },
                    variant = GlassButtonVariant.SECONDARY,
                    compact = true,
                )
                if (editing) {
                    Text(
                        text = "拖动排序 · 点把手缩放 · 点形态切换",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        textAlign = TextAlign.End,
                        modifier = Modifier.padding(top = 6.dp, end = 4.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 自定义卡片模型 / 持久化
// ---------------------------------------------------------------------------

/** 总览里的一张可自定义卡片（核心指标或深度指标）。 */
private sealed interface Slot {
    val key: String
    data class Core(val id: CoreMetricId, val reading: MonitorViewModel.CoreReading?) : Slot {
        override val key get() = "core:${id.name}"
    }
    data class Deep(val card: DeepCardData) : Slot {
        override val key get() = "deep:${card.key}"
    }
}

private enum class CardSize(val span: Int) {
    HALF(1), FULL(2)
}

private data class CardConfig(val size: CardSize, val mode: MetricViewMode)

private data class OverviewLayout(
    val order: List<String>,
    val config: Map<String, CardConfig>,
)

/** 顺序对齐：保留已保存顺序，并把新出现的卡追加到末尾。 */
private fun syncOrder(saved: List<String>, existing: List<String>): List<String> {
    val savedSet = saved.toSet()
    return (saved.filter { it in existing }) + existing.filter { it !in savedSet }
}

private fun slotAvailable(slot: Slot): Boolean = when (slot) {
    is Slot.Core -> slot.reading?.available == true
    is Slot.Deep -> slot.card.display.isNotBlank() && slot.card.display != "—"
}

private fun defaultModeFor(slot: Slot): MetricViewMode = when {
    slot is Slot.Core && slot.reading != null -> when (slot.reading.id) {
        CoreMetricId.MEMORY_RATIO -> MetricViewMode.RING
        CoreMetricId.DEVICE_TEMP -> MetricViewMode.BARS
        CoreMetricId.REFRESH_RATE -> MetricViewMode.BARS
        CoreMetricId.FPS -> MetricViewMode.WAVE
        else -> MetricViewMode.NUMBER
    }
    else -> MetricViewMode.NUMBER
}

/** 形态循环：数字 → 波形 → 环形 → 柱状 → 数字。 */
private fun cycleMode(m: MetricViewMode): MetricViewMode = when (m) {
    MetricViewMode.NUMBER -> MetricViewMode.WAVE
    MetricViewMode.WAVE -> MetricViewMode.RING
    MetricViewMode.RING -> MetricViewMode.BARS
    MetricViewMode.BARS -> MetricViewMode.NUMBER
}

/** 环形只适用于 0~100 有界值，越界回落到数字。 */
private fun resolveMode(m: MetricViewMode, value: Float?): MetricViewMode =
    if (m == MetricViewMode.RING && (value == null || value < 0f || value > 100f)) {
        MetricViewMode.NUMBER
    } else {
        m
    }

/**
 * 自定义卡持久化：SharedPreferences，文本格式避免引入 JSON 库。
 * order 用 '|' 连接；config 用 "key~SIZE~MODE" 再用 '|' 连接。
 */
private object OverviewLayoutPrefs {
    private const val NAME = "overview_layout"
    private const val K_ORDER = "order"
    private const val K_CONFIG = "config"

    fun load(context: Context): OverviewLayout {
        val sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val order = sp.getString(K_ORDER, null)
            ?.split('|')?.filter { it.isNotEmpty() } ?: emptyList()
        val config = sp.getString(K_CONFIG, null)
            ?.split('|')?.filter { it.isNotEmpty() }?.mapNotNull { part ->
                val seg = part.split('~')
                if (seg.size == 3) {
                    val size = if (seg[1] == "FULL") CardSize.FULL else CardSize.HALF
                    val mode = runCatching { MetricViewMode.valueOf(seg[2]) }
                        .getOrNull() ?: MetricViewMode.NUMBER
                    seg[0] to CardConfig(size, mode)
                } else {
                    null
                }
            }?.toMap() ?: emptyMap()
        return OverviewLayout(order, config)
    }

    fun save(context: Context, layout: OverviewLayout) {
        val sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        sp.edit().apply {
            putString(K_ORDER, layout.order.joinToString("|"))
            putString(
                K_CONFIG,
                layout.config.entries.joinToString("|") { (k, c) ->
                    "$k~${c.size.name}~${c.mode.name}"
                },
            )
        }.apply()
    }
}

// ---------------------------------------------------------------------------
// 可自定义指标卡
// ---------------------------------------------------------------------------

@Composable
private fun CustomizableMetricCard(
    slot: Slot,
    cfg: CardConfig,
    editing: Boolean,
    dragModifier: Modifier = Modifier,
    viewModel: MonitorViewModel,
    onEnterEdit: () -> Unit,
    onToggleSize: () -> Unit,
    onCycleMode: () -> Unit,
    onReset: () -> Unit,
    onOpen: () -> Unit,
) {
    val colors = LocalTokenColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()

    val data = when (slot) {
        is Slot.Core -> {
            // 仅在可见槽位（已采到）渲染，reading 必非空
            val r = slot.reading!!
            val history = viewModel.history(r.id.source, CARD_WINDOW_SEC).map { it.value }
            CardData(
                label = r.id.label,
                unit = r.id.unit,
                value = r.value,
                display = r.value?.let { "%.${r.id.decimals}f".format(it) } ?: "—",
                accent = metricColor(r.id.source.colorKey),
                history = history,
                decimals = r.id.decimals,
                isText = false,
            )
        }
        is Slot.Deep -> {
            val c = slot.card
            CardData(
                label = c.label,
                unit = c.unit,
                value = c.value,
                display = c.display,
                accent = metricColor(c.colorKey),
                history = viewModel.deepHistory(c.key, CARD_WINDOW_SEC),
                decimals = 0,
                isText = c.value == null,
            )
        }
    }

    val mode = resolveMode(cfg.mode, data.value)
    val isFull = cfg.size == CardSize.FULL
    val baseHeight = if (isFull) 184.dp else 156.dp
    val height = if (editing) baseHeight + 26.dp else baseHeight

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .pressScale(interactionSource)
            .then(dragModifier),
        shape = RoundedCornerShape(DesignTokens.Radius.CARD.dp),
        onClick = { if (!editing) { haptics.tick(); onOpen() } },
        interactionSource = interactionSource,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { },
                    onLongClick = onEnterEdit,
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = data.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (editing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DragHandle(
                            modifier = Modifier.padding(end = 8.dp),
                            tint = colors.textSecondary,
                        )
                        PillButton(
                            text = if (isFull) "全宽" else "半宽",
                            onClick = onToggleSize,
                            tint = colors.textSecondary,
                        )
                        PillButton(text = mode.label, onClick = onCycleMode, tint = colors.textSecondary)
                        PillButton(text = "重置", onClick = onReset, tint = colors.danger)
                    }
                } else {
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = data.accent.copy(alpha = 0.75f),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.CenterStart,
            ) {
                when (mode) {
                    MetricViewMode.RING -> if (data.value != null && data.value in 0f..100f) {
                        ProgressRing(
                            progress = data.value / 100f,
                            centerText = data.value.toInt().toString(),
                            caption = data.unit,
                            color = data.accent,
                            trackColor = data.accent.copy(alpha = 0.18f),
                            modifier = Modifier.size(if (isFull) 96.dp else 72.dp),
                        )
                    } else {
                        NumberContent(data)
                    }
                    MetricViewMode.WAVE -> if (data.history.size >= 2) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Sparkline(
                                points = data.history,
                                color = data.accent,
                                fillAlpha = 0.26f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            )
                            MiniValue(data)
                        }
                    } else {
                        NumberContent(data)
                    }
                    MetricViewMode.BARS -> if (data.history.size >= 2) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            BarSeries(
                                points = data.history,
                                color = data.accent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            )
                            MiniValue(data)
                        }
                    } else {
                        NumberContent(data)
                    }
                    else -> NumberContent(data)
                }
            }
        }
    }
}

@Composable
private fun NumberContent(data: CardData) {
    if (data.isText) {
        Text(
            text = data.display,
            style = if (data.display.length <= 8) {
                MaterialTheme.typography.headlineSmall
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = data.accent,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        Row(verticalAlignment = Alignment.Bottom) {
            AnimatedNumber(
                value = data.value ?: 0f,
                intervalMs = 0L,
                decimals = data.decimals,
                color = data.accent,
                style = MaterialTheme.typography.headlineMedium,
            )
            if (data.unit.isNotBlank()) {
                Text(
                    text = " ${data.unit}",
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalTokenColors.current.textSecondary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun MiniValue(data: CardData) {
    if (data.isText) return
    Text(
        text = data.display + if (data.unit.isNotBlank()) " ${data.unit}" else "",
        style = MaterialTheme.typography.labelSmall,
        color = LocalTokenColors.current.textSecondary.copy(alpha = 0.8f),
    )
}

@Composable
private fun PillButton(text: String, onClick: () -> Unit, tint: Color) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(
                LocalTokenColors.current.surface.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@Composable
private fun DragHandle(modifier: Modifier, tint: Color) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) {
                    Box(
                        Modifier
                            .size(4.dp)
                            .background(tint.copy(alpha = 0.5f), CircleShape),
                    )
                }
            }
        }
    }
}

private data class CardData(
    val label: String,
    val unit: String,
    val value: Float?,
    val display: String,
    val accent: Color,
    val history: List<Float>,
    val decimals: Int,
    val isText: Boolean,
)

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
private fun SkeletonCard(index: Int, full: Boolean) {
    val colors = LocalTokenColors.current
    val shape = RoundedCornerShape(
        (if (full) DesignTokens.Radius.LARGE else DesignTokens.Radius.CARD).dp,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (full) 190.dp else 132.dp)
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
                    .size(if (full) 128.dp else 76.dp)
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
// 指标卡（历史遗留：原 Bento 多形态实现，保留以兼容监测/详情页引用）
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
