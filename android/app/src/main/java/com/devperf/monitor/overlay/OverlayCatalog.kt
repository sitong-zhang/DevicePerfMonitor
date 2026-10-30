package com.devperf.monitor.overlay

import com.devperf.monitor.core.CoreMetricId
import com.devperf.monitor.core.DeepSnapshot
import com.devperf.monitor.core.Metric
import com.devperf.monitor.core.MetricState
import com.devperf.monitor.ui.screen.DEEP_CATEGORY_COLORS

/**
 * 悬浮窗显示项的 key 规范：
 * - `core:<CoreMetricId 名>`：核心指标（内存占用率 / 温度 / 刷新率 / 帧率）；
 * - `deep:<深度行 key>`：深度监测里这台设备**真实读得到**的数值行。
 */
object OverlayItemKeys {
    const val CORE_PREFIX = "core:"
    const val DEEP_PREFIX = "deep:"

    fun core(id: CoreMetricId): String = "$CORE_PREFIX${id.name}"
    fun deep(rowKey: String): String = "$DEEP_PREFIX$rowKey"
}

/**
 * 悬浮窗候选池里的一项。
 *
 * 定制器页按 [group] 分组列出全部候选项，用户自由增删 + 拖拽排序；
 * 悬浮窗渲染时按选中 key 从这个池里取值。
 */
data class OverlayCandidate(
    /** 完整 key（core:xxx / deep:yyy）。 */
    val key: String,
    val label: String,
    val unit: String,
    /** 分组名（"核心指标" / "CPU" / "GPU" …），定制器页用它分节。 */
    val group: String,
    /** 当前值；null = 这台设备读不到（列表中仍列出，用户可自行决定要不要加）。 */
    val value: Float?,
    /** 读不到时的真实原因（如「需 Shizuku 授权」），不静默消失。 */
    val hint: String?,
    val decimals: Int,
    val colorKey: String,
)

/** 核心指标读不到时的引导文案：告诉用户去哪开启，而不是莫名其妙少一行。 */
fun overlayMetricHint(state: MetricState<Float>): String = when (state) {
    is MetricState.NeedsPermission -> "需 ${state.permissionLabel}"
    is MetricState.Unavailable -> "暂无数据"
    else -> "暂无数据"
}

/**
 * 构造悬浮窗候选池：核心指标 + 深度监测里全部数值行。
 *
 * 深度行只收**能转成数值**的行（文本行如 governor / 充电状态悬浮窗显示没意义，
 * 也不在候选池里占位）。读不到的行照样列出并带 [OverlayCandidate.hint]，
 * 让用户自己决定加不加——「最大自由」的前提是候选池不藏东西。
 */
fun buildOverlayCandidates(
    core: Map<CoreMetricId, Metric<Float>>,
    deep: DeepSnapshot,
): List<OverlayCandidate> = buildList {
    // 1) 核心指标（固定四项，按 CoreMetricId.ordered 顺序）
    for (id in CoreMetricId.ordered) {
        val m = core[id] ?: continue
        add(
            OverlayCandidate(
                key = OverlayItemKeys.core(id),
                label = id.label,
                unit = id.unit,
                group = "核心指标",
                value = m.value,
                hint = if (m.value == null) overlayMetricHint(m.state) else null,
                decimals = id.decimals,
                colorKey = id.source.colorKey,
            ),
        )
    }

    // 2) 深度监测：这台设备真实读得到的数值行，按分类顺序展开
    for (section in deep.sections) {
        val colorKey = DEEP_CATEGORY_COLORS[
            section.category.ordinal % DEEP_CATEGORY_COLORS.size
        ]
        for (row in section.rows) {
            // 文本行没有数值，不进候选池（悬浮窗只显示数字）
            val numeric = row.value?.toFloatOrNull() ?: continue
            add(
                OverlayCandidate(
                    key = OverlayItemKeys.deep(row.key),
                    label = row.label,
                    unit = row.unit,
                    group = section.category.label,
                    value = numeric,
                    hint = null,
                    decimals = if (kotlin.math.abs(numeric) < 10f) 1 else 0,
                    colorKey = colorKey,
                ),
            )
        }
    }
}

/**
 * 按用户选中的有序 key 列表取出要显示的格子。
 *
 * 顺序完全听用户的（拖拽排序结果），不再按固定枚举顺序排。
 * 选中但候选池里没有的 key（比如换设备后某个深度行读不到了）直接跳过，
 * 不摆空行——换设备是常事，摆一排「—」没意义。
 */
fun resolveOverlayCells(
    candidates: List<OverlayCandidate>,
    selectedKeys: List<String>,
): List<OverlayCandidate> {
    if (selectedKeys.isEmpty()) return emptyList()
    val byKey = candidates.associateBy { it.key }
    return selectedKeys.mapNotNull { byKey[it] }
}
