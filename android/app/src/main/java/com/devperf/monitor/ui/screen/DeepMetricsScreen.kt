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

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "深度监测",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
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
    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
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
            section.rows.forEach { row ->
                RowRow(row)
            }
        }
    }
}

@Composable
private fun RowRow(row: DeepRow) {
    Column(modifier = Modifier.padding(vertical = 5.dp)) {
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
            val valueText = when {
                row.value == null -> "—"
                row.unit.isBlank() -> row.value
                else -> "${row.value} ${row.unit}"
            }
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (row.value == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (row.origin == DeepOrigin.ESTIMATED) {
                Text(
                    text = " 推算",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontSize = 10.sp,
                )
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
