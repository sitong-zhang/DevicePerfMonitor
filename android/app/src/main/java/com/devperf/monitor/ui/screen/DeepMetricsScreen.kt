package com.devperf.monitor.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devperf.monitor.core.DeepCategory
import com.devperf.monitor.ui.kit.GlassPanel
import com.devperf.monitor.core.DeepOrigin
import com.devperf.monitor.core.DeepRow
import com.devperf.monitor.core.DeepSection
import com.devperf.monitor.ui.MonitorViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 深度监测页：按类别展示 CPU 调度器 / PMU / 中断 / 调频 / GPU / 显示 / 帧率 /
 * 内存 / 功耗电源轨 / 存储 / 网络细分 / 音频 / 温度分区 / 进程扩展 / DDR 全部项。
 *
 * 取值三态在 UI 上必须一眼可辨：
 * - 真实读取：正常显示数值；
 * - 推算值：数值后打「推算」角标，并把推算公式写在下方小字；
 * - 不可读：显示「—」+ 真实原因（权限 / 节点未开放），绝不填假值。
 */
@Composable
fun DeepMetricsScreen(viewModel: MonitorViewModel) {
    val snapshot by viewModel.deep.collectAsState()
    var selected: DeepCategory? by remember { mutableStateOf(null) }
    // 管理端禁用「深度监测」：整页停采集并给出说明，不留空数据
    val deepDisabled = viewModel.isFeatureDisabled("deep_monitor")

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "深度监测",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (deepDisabled) {
            Text(
                text = "深度监测功能已被管理员关闭，如有疑问请联系官方。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
            )
            return@Column
        }
        val stamp = remember(snapshot.timestampMs) {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(snapshot.timestampMs))
        }
        Text(
            text = if (snapshot.sections.isEmpty()) "首次采样中…" else "更新于 $stamp · 每 2 秒刷新",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        // 类别筛选
        val categories = remember(snapshot) { snapshot.sections.map { it.category } }
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CategoryChip(label = "全部", selected = selected == null) { selected = null }
            categories.forEach { c ->
                CategoryChip(label = c.label, selected = selected == c) {
                    selected = if (selected == c) null else c
                }
            }
        }

        val shown = remember(snapshot, selected) {
            snapshot.sections.filter { selected == null || it.category == selected }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shown, key = { it.category.name }) { section ->
                SectionCard(section)
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    )
}

@Composable
private fun SectionCard(section: DeepSection) {
    // 与总览/设置页同一液态玻璃卡片：统一的圆角、模糊与高光形态
    GlassPanel {
        Text(
            text = section.category.label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        section.rows.forEachIndexed { i, row ->
            RowRow(row)
            if (i != section.rows.lastIndex) {
                androidx.compose.material3.HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
            }
        }
    }
}

@Composable
private fun RowRow(row: DeepRow) {
    val valueText = when {
        row.value == null -> "—"
        row.unit.isBlank() -> row.value
        else -> "${row.value} ${row.unit}"
    }
    // 长值（如线程运行时长列表）放不下右侧：单独占一行整宽展示，
    // 避免把左侧标题挤成一列竖字（真机反馈的排版 bug）
    val longValue = valueText.length > 18

    Column(modifier = Modifier.padding(vertical = 5.dp)) {
        if (longValue) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                OriginBadge(row.origin)
            }
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (row.value == null) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (row.value == null) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                )
                OriginBadge(row.origin)
            }
        }
        row.note?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 取值来源角标：真实读取不标；推算打琥珀「推算」；不可读为灰色「—」。 */
@Composable
private fun OriginBadge(origin: DeepOrigin) {
    when (origin) {
        DeepOrigin.ESTIMATED -> Text(
            text = "推算",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(start = 6.dp),
        )
        DeepOrigin.UNAVAILABLE -> Text(
            text = "不可读",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
        else -> {}
    }
}
