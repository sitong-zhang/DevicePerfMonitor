package com.devperf.monitor.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState

/**
 * 公告弹窗（1.0.22 新增）：从 [MonitorViewModel.announcementState] 取状态。
 *
 * 不可关闭（返回键 / 点外部都不消失）；正文可滚，**必须滚到最底**「我已知晓」才会
 * 亮起，确保用户真的把公告读完。点确认后写已读 id，不再弹，直到发新公告（新 id）。
 */
@Composable
fun AnnouncementDialog(viewModel: MonitorViewModel) {
    val state by viewModel.announcementState.collectAsState()
    val announcement = (state as? AnnouncementUiState.Show)?.announcement ?: return
    AnnouncementDialogContent(
        title = announcement.title,
        content = announcement.content,
        onConfirm = { viewModel.markAnnouncementRead(announcement.id) },
    )
}

@Composable
private fun AnnouncementDialogContent(
    title: String,
    content: String,
    onConfirm: () -> Unit,
) {
    val scrollState = rememberScrollState()
    // 滚到底才允许确认：内容没超出框（maxValue==0）时直接可关；超出则必须滚到底部。
    val reachedBottom by remember {
        derivedStateOf {
            scrollState.maxValue == 0 || scrollState.value >= scrollState.maxValue - 24
        }
    }
    AlertDialog(
        onDismissRequest = { /* 不可关闭：必须读完点确认 */ },
        title = { Text(title.ifBlank { "公告" }) },
        text = {
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = reachedBottom) {
                Text(if (reachedBottom) "我已知晓" else "请先滑到最底部")
            }
        },
    )
}
