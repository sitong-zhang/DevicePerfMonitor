package com.devperf.monitor.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devperf.monitor.ui.FeedbackUiState
import com.devperf.monitor.ui.MonitorViewModel
import com.devperf.monitor.ui.kit.GlassButton
import com.devperf.monitor.ui.kit.GlassTextField
import com.devperf.monitor.ui.kit.rememberHaptics
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.LocalTokenColors

/**
 * 意见反馈独立页面（1.0.27 从设置页内联卡片抽出）。
 *
 * 之前反馈直接堆在设置页里，用户要边滚边填、提交结果还和设置项混在一起，
 * 观感乱。单独成页后：返回胶囊回设置，正文/联系方式两个玻璃输入框，
 * 提交后状态行（已提交 / 已开浏览器 / 失败）独立展示。
 *
 * 提交链路见 [com.devperf.monitor.data.FeedbackRepository]：带 token 直连
 * GitHub Issue（label=feedback，管理端收件箱按它拉取）；无 token 降级为
 * 拉起系统浏览器打开预填好的 GitHub new-issue 页。
 */
@Composable
fun FeedbackScreen(
    viewModel: MonitorViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTokenColors.current
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val feedbackState by viewModel.feedbackState.collectAsState()
    var content by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    val submitting = feedbackState is FeedbackUiState.Submitting

    // 进入页面重置反馈状态，避免上一次提交结果残留（例如「已提交」一直挂着）
    LaunchedEffect(Unit) { viewModel.resetFeedbackState() }

    // 降级通道（未配直连 token）：提交结果是预填好的 GitHub 网页 issue 链接，
    // 直接拉起系统浏览器让用户发出去。
    LaunchedEffect(feedbackState) {
        val fs = feedbackState
        if (fs is FeedbackUiState.BrowserFallback) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fs.url)))
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = DesignTokens.Spacing.S16.dp,
                end = DesignTokens.Spacing.S16.dp,
                top = 24.dp,
                bottom = 96.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S16.dp),
    ) {
        // 顶栏：返回胶囊 + 标题（与指标详情页「返回」胶囊同款）
        Row(verticalAlignment = Alignment.CenterVertically) {
            val backSource = remember { MutableInteractionSource() }
            Text(
                text = "返回",
                style = MaterialTheme.typography.labelLarge,
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(DesignTokens.Radius.PILL.dp))
                    .background(colors.accent.copy(alpha = 0.12f))
                    .clickable(
                        interactionSource = backSource,
                        indication = null,
                        onClick = {
                            haptics.tick()
                            onBack()
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Spacer(Modifier.size(DesignTokens.Spacing.S12.dp))
            Text(
                text = "意见反馈",
                style = MaterialTheme.typography.titleLarge,
                color = colors.textPrimary,
            )
        }

        Text(
            text = "遇到问题、想要新功能，或者任何想说的话，写在这里就能送到开发者手里。" +
                "提交时会自动带上你的机型、系统版本和 App 版本，方便定位问题。",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )

        GlassTextField(
            value = content,
            onValueChange = {
                content = it
                viewModel.resetFeedbackState()
            },
            placeholder = "说点什么……（必填）",
            minLines = 4,
        )
        GlassTextField(
            value = contact,
            onValueChange = {
                contact = it
                viewModel.resetFeedbackState()
            },
            placeholder = "联系方式（选填：邮箱 / 微信 / QQ 均可）",
        )

        GlassButton(
            text = if (submitting) "提交中……" else "提交反馈",
            onClick = {
                haptics.select()
                viewModel.submitFeedback(content.trim(), contact.trim())
            },
            enabled = content.isNotBlank() && !submitting,
            modifier = Modifier.fillMaxWidth(),
        )

        // 状态行：每种终态一句话说清楚，失败给原因
        val fs = feedbackState
        when (fs) {
            is FeedbackUiState.Submitted -> {
                // 成功后清空草稿，准备下一条
                LaunchedEffect(fs) {
                    content = ""
                    contact = ""
                }
                val url = fs.issueUrl
                Text(
                    text = if (url != null) {
                        "已提交，感谢！点此查看你提交的反馈 →"
                    } else {
                        "已提交，感谢！开发者很快会看到。"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Start,
                    modifier = if (url != null) {
                        Modifier.clickable {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        }
                    } else {
                        Modifier
                    },
                )
            }
            is FeedbackUiState.BrowserFallback ->
                Text(
                    text = "已打开 GitHub 反馈页，点「Submit new issue」即可发出。",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textPrimary,
                )
            is FeedbackUiState.Failed ->
                Text(
                    text = "提交失败：${fs.reason}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            else -> Unit
        }
    }
}
