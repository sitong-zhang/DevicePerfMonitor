package com.devperf.monitor.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.devperf.monitor.data.OverlayStyle
import com.devperf.monitor.overlay.OverlayCandidate
import com.devperf.monitor.overlay.OverlayData
import com.devperf.monitor.overlay.resolveOverlayCells
import com.devperf.monitor.ui.MonitorViewModel
import com.devperf.monitor.ui.kit.AnimatedNumber
import com.devperf.monitor.ui.kit.GlassCard
import com.devperf.monitor.ui.kit.GlassTabs
import com.devperf.monitor.ui.kit.metricColor
import com.devperf.monitor.ui.kit.pressScale
import com.devperf.monitor.ui.kit.rememberHaptics
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.LocalTokenColors
import kotlin.math.roundToInt

/** 已选行的固定高度：拖拽排序按它换算目标位置。 */
private val ROW_HEIGHT = 54.dp

/**
 * 悬浮窗定制器（1.0.44）：**最大自由**定制悬浮窗显示内容。
 *
 * - 候选池 = 核心指标 + 这台设备真实读得到的**全部深度数值行**
 *   （CPU / GPU / 电压 / 电流 / 功耗 / 电池 / 网络 / 存储 / 帧率…），
 *   只要 App 采集得到就能放上悬浮窗，不再是写死的四种；
 * - 已选区支持**长按拖拽排序**，顺序即悬浮窗上的显示顺序；
 * - 顶部**实时预览**：改一个字就能看到悬浮窗长什么样；
 * - 样式（卡片 / 纯数字 / 极简条）一并在这里切。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OverlayCustomizerScreen(
    viewModel: MonitorViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTokenColors.current
    val settings by viewModel.settings.collectAsState()
    val snapshot by viewModel.snapshot.collectAsState()
    val deep by viewModel.deep.collectAsState()
    val haptics = rememberHaptics()
    val rowHeightPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
    val reorderState = remember { ReorderState() }

    val data = OverlayData.from(
        fps = snapshot.display.fps,
        snapshot = snapshot,
        deep = deep,
    )
    val candidates = data.candidates
    val selectedKeys = settings.overlayItems
    val selectedCells = resolveOverlayCells(candidates, selectedKeys)

    // 未选中的候选项按分组列出（已选的不在池里重复出现）
    val selectedSet = selectedKeys.toSet()
    val pool = candidates.filter { it.key !in selectedSet }
    val groups = pool.groupBy { it.group }

    fun commit(keys: List<String>) = viewModel.setOverlayItems(keys)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 72.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            HeaderRow(
                title = "悬浮窗定制",
                subtitle = "已选 ${selectedKeys.size} 项 · 共 ${candidates.size} 项可选",
                onBack = onBack,
                hapticsTick = { haptics.tick() },
            )
        }

        // ---------- 实时预览 ----------
        item {
            SectionTitle("实时预览")
            OverlayPreview(
                cells = selectedCells,
                style = settings.overlayStyle,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---------- 样式 ----------
        item {
            SectionTitle("显示样式")
            GlassTabs(
                options = OverlayStyle.entries.map { it.label },
                selectedIndex = OverlayStyle.entries.indexOf(settings.overlayStyle)
                    .coerceAtLeast(0),
                onSelect = { index ->
                    haptics.select()
                    viewModel.setOverlayStyle(OverlayStyle.entries[index])
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---------- 已选（拖拽排序） ----------
        item {
            SectionTitle("已选（长按拖拽排序）")
        }
        if (selectedCells.isEmpty()) {
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(DesignTokens.Radius.CARD.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 22.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "还没有选任何数据，从下面「可添加」里点一下加进来",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        } else {
            ReorderableSelectedRows(
                cells = selectedCells,
                rowHeightPx = rowHeightPx,
                state = reorderState,
                onReorder = { keys -> commit(keys) },
                onRemove = { key -> haptics.tick(); commit(selectedCells.map { it.key } - key) },
                onDragStartHaptic = { haptics.tick() },
            )
        }

        // ---------- 候选池 ----------
        item {
            SectionTitle("可添加（${pool.size}）")
            Text(
                text = "列出这台设备当前能读到的全部数值指标；读不到的项不会出现，" +
                    "避免加进去只看到一排「—」。",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )
        }
        groups.forEach { (group, rows) ->
            item {
                Text(
                    text = group,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.accent,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
            }
            items(rows, key = { it.key }) { candidate ->
                AddableRow(
                    candidate = candidate,
                    onAdd = {
                        haptics.select()
                        commit(selectedCells.map { it.key } + candidate.key)
                    },
                )
            }
        }
    }
}

/**
 * 拖拽排序的运行时状态。
 *
 * 单独成类是因为 LazyColumn 的 content 是 LazyListScope（**非**可组合上下文），
 * 不能在里面调用 `remember` —— 状态必须在页面级可组合作用域里创建再传进来。
 */
private class ReorderState {
    var draggingIndex by mutableIntStateOf(-1)
    var targetIndex by mutableIntStateOf(-1)
    var dragOffset by mutableFloatStateOf(0f)

    fun reset() {
        draggingIndex = -1
        targetIndex = -1
        dragOffset = 0f
    }
}

/**
 * 已选列表：长按行体拖拽排序。
 *
 * 用固定行高把「拖了多少像素」换算成「落到第几位」，拖动中邻行实时让位
 * （Material 的 reorder 手感），松手才写回设置。不引入三方 reorder 库，
 * 避免和 Compose 1.11 的 LazyColumn 版本打架。
 */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.ReorderableSelectedRows(
    cells: List<OverlayCandidate>,
    rowHeightPx: Float,
    state: ReorderState,
    onReorder: (List<String>) -> Unit,
    onRemove: (String) -> Unit,
    onDragStartHaptic: () -> Unit,
) {
    itemsIndexed(cells, key = { _, it -> it.key }) { index, cell ->
        val isDragging = index == state.draggingIndex
        val shift = if (state.draggingIndex < 0 ||
            state.targetIndex < 0 ||
            state.draggingIndex == state.targetIndex ||
            rowHeightPx <= 0f
        ) {
            0f
        } else if (
            state.draggingIndex < state.targetIndex &&
            index in (state.draggingIndex + 1)..state.targetIndex
        ) {
            -rowHeightPx
        } else if (
            state.draggingIndex > state.targetIndex &&
            index in state.targetIndex..(state.draggingIndex - 1)
        ) {
            rowHeightPx
        } else {
            0f
        }

        SelectedRow(
            cell = cell,
            dragging = isDragging,
            onRemove = { onRemove(cell.key) },
            modifier = Modifier
                .animateItem()
                .height(ROW_HEIGHT)
                .zIndex(if (isDragging) 2f else 0f)
                .graphicsLayer {
                    translationY = if (isDragging) state.dragOffset else shift
                    if (isDragging) {
                        scaleX = 1.03f
                        scaleY = 1.03f
                        alpha = 0.96f
                    }
                }
                .pointerInput(index) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            state.draggingIndex = index
                            state.targetIndex = index
                            state.dragOffset = 0f
                            onDragStartHaptic()
                        },
                        onDrag = { _, amount ->
                            state.dragOffset += amount.y
                            val to = index + (state.dragOffset / rowHeightPx).roundToInt()
                            state.targetIndex = to.coerceIn(0, cells.lastIndex)
                        },
                        onDragEnd = {
                            val from = state.draggingIndex
                            val to = state.targetIndex
                            if (from != to && from in cells.indices && to in cells.indices) {
                                val keys = cells.map { it.key }.toMutableList()
                                keys.add(to, keys.removeAt(from))
                                onReorder(keys)
                            }
                            state.reset()
                        },
                        onDragCancel = { state.reset() },
                    )
                },
        )
    }
}

/** 已选行：拖拽手柄 + 标签 + 当前值 + 移除。 */
@Composable
private fun SelectedRow(
    cell: OverlayCandidate,
    dragging: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTokenColors.current
    val accent = metricColor(cell.colorKey)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(
                color = if (dragging) {
                    colors.accent.copy(alpha = 0.16f)
                } else {
                    colors.surface
                },
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 拖拽手柄（六点）
        Column(
            modifier = Modifier.width(18.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            repeat(3) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(2) {
                        Box(
                            modifier = Modifier
                                .size(3.dp)
                                .background(
                                    color = colors.textSecondary.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(2.dp),
                                ),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = cell.label,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = cell.group,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                maxLines = 1,
            )
        }
        if (cell.value != null) {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(end = 6.dp),
            ) {
                AnimatedNumber(
                    value = cell.value,
                    intervalMs = 1000L,
                    decimals = cell.decimals,
                    color = accent,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = " ${cell.unit}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                )
            }
        } else {
            Text(
                text = cell.hint ?: "暂无数据",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    color = colors.danger.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(14.dp),
                )
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "移除",
                tint = colors.danger,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 候选行：点一下加进悬浮窗。 */
@Composable
private fun AddableRow(
    candidate: OverlayCandidate,
    onAdd: () -> Unit,
) {
    val colors = LocalTokenColors.current
    val accent = metricColor(candidate.colorKey)
    val pressSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .pressScale(pressSource)
            .background(
                color = colors.surface.copy(alpha = 0.6f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onAdd)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.label,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (candidate.unit.isNotBlank()) {
                Text(
                    text = "单位 ${candidate.unit}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                )
            }
        }
        if (candidate.value != null) {
            Text(
                text = "${"%.${candidate.decimals}f".format(candidate.value)}${candidate.unit}",
                style = MaterialTheme.typography.titleSmall,
                color = accent,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(
                    color = colors.accent.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(13.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "+",
                style = MaterialTheme.typography.titleMedium,
                color = colors.accent,
            )
        }
    }
}

/** 顶部实时预览：按选中顺序与样式重绘一张迷你悬浮窗。 */
@Composable
private fun OverlayPreview(
    cells: List<OverlayCandidate>,
    style: OverlayStyle,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTokenColors.current
    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(DesignTokens.Radius.LARGE.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = when (style) {
                    OverlayStyle.CARD -> "卡片样式"
                    OverlayStyle.NUMBERS -> "纯数字样式"
                    OverlayStyle.COMPACT -> "极简条样式"
                },
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(10.dp))
            if (cells.isEmpty()) {
                Text(
                    text = "（悬浮窗暂无内容）",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            } else if (style == OverlayStyle.NUMBERS || style == OverlayStyle.COMPACT) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        if (style == OverlayStyle.COMPACT) 8.dp else 14.dp,
                    ),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    cells.take(6).forEach { cell ->
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = cell.value?.let {
                                    "%.${cell.decimals}f".format(it)
                                } ?: "—",
                                style = if (style == OverlayStyle.COMPACT) {
                                    MaterialTheme.typography.labelLarge
                                } else {
                                    MaterialTheme.typography.titleMedium
                                },
                                color = metricColor(cell.colorKey),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = cell.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    cells.take(6).forEach { cell ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = cell.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary,
                                modifier = Modifier.weight(1f, fill = false),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = cell.value?.let {
                                    "%.${cell.decimals}f".format(it) +
                                        if (cell.unit.isNotBlank()) " ${cell.unit}" else ""
                                } ?: (cell.hint ?: "—"),
                                style = MaterialTheme.typography.titleMedium,
                                color = metricColor(cell.colorKey),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (cells.size > 6) {
                        Text(
                            text = "…还有 ${cells.size - 6} 项",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    val colors = LocalTokenColors.current
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = colors.textPrimary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun HeaderRow(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    hapticsTick: () -> Unit,
) {
    val colors = LocalTokenColors.current
    val doneSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.textPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
            )
        }
        Text(
            text = "完成",
            style = MaterialTheme.typography.labelLarge,
            color = colors.accent,
            modifier = Modifier
                .pressScale(doneSource)
                .background(
                    color = colors.accent.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(DesignTokens.Radius.PILL.dp),
                )
                .clickable {
                    hapticsTick()
                    onBack()
                }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
