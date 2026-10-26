package com.devperf.monitor.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import com.devperf.monitor.ui.kit.GlassButtonVariant
import com.devperf.monitor.ui.kit.GlassTextField
import com.devperf.monitor.ui.kit.rememberHaptics
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.LocalTokenColors

/**
 * 意见反馈独立页面（1.0.27 从设置页内联卡片抽出，1.0.28 改为邮箱通道）。
 *
 * 用户拍板不再依赖 GitHub token：点「提交反馈」直接拉起手机上的邮件 App，
 * 收件人/主题/正文已全部预填好（正文含反馈内容、联系方式、设备信息），
 * 用户点「发送」即到官方邮箱——发件人是用户自己的邮箱，开发者可直接回复。
 * 手机上没有邮件 App 时降级：把反馈内容复制到剪贴板，用户自行粘贴发送。
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
    val contentTrim = content.trim()

    // 进入页面重置反馈状态，避免上一次提交结果残留
    LaunchedEffect(Unit) { viewModel.resetFeedbackState() }

    // 邮件就绪：拉起邮件 App；机器上没有邮件 App 时降级复制到剪贴板
    LaunchedEffect(feedbackState) {
        val fs = feedbackState
        if (fs is FeedbackUiState.EmailReady) {
            val opened = openMailApp(context, fs.mailtoUrl)
            if (!opened) {
                copyToClipboard(context, viewModel.buildFeedbackMailBody(contentTrim, contact.trim()))
                Toast.makeText(context, "没找到邮件 App，反馈内容已复制，去邮箱粘贴发送即可", Toast.LENGTH_LONG)
                    .show()
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
                "点提交会自动打开你手机上的邮件 App，收件人、正文都已填好（自动带上机型、" +
                "系统版本和 App 版本），点发送即可。",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        Text(
            text = "官方反馈邮箱：${viewModel.feedbackEmail}",
            style = MaterialTheme.typography.labelMedium,
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
            text = "提交反馈",
            onClick = {
                haptics.select()
                viewModel.submitFeedback(contentTrim, contact.trim())
            },
            enabled = contentTrim.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )

        // 兜底：没装邮件 App / 想换个方式发——复制正文与收件邮箱
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S12.dp),
        ) {
            GlassButton(
                text = "复制反馈内容",
                onClick = {
                    haptics.tick()
                    copyToClipboard(
                        context,
                        viewModel.buildFeedbackMailBody(contentTrim, contact.trim()),
                    )
                    Toast.makeText(context, "反馈内容已复制", Toast.LENGTH_SHORT).show()
                },
                variant = GlassButtonVariant.SECONDARY,
                enabled = contentTrim.isNotBlank(),
                modifier = Modifier.weight(1f),
                compact = true,
            )
            GlassButton(
                text = "复制邮箱地址",
                onClick = {
                    haptics.tick()
                    copyToClipboard(context, viewModel.feedbackEmail)
                    Toast.makeText(context, "邮箱地址已复制", Toast.LENGTH_SHORT).show()
                },
                variant = GlassButtonVariant.SECONDARY,
                modifier = Modifier.weight(1f),
                compact = true,
            )
        }

        // 状态行
        val fs = feedbackState
        when (fs) {
            is FeedbackUiState.EmailReady ->
                Text(
                    text = "已打开邮件 App，收件人和正文都已填好，点「发送」即可送达。",
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

/**
 * 拉起邮件 App 打开 [mailtoUrl]。返回是否成功（机器上没有任何邮件 App 时
 * 返回 false，调用方降级复制到剪贴板）。
 */
private fun openMailApp(context: Context, mailtoUrl: String): Boolean = runCatching {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(mailtoUrl))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
}.getOrDefault(false)

/** 复制 [text] 到剪贴板。 */
private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("DevicePerfMonitor 反馈", text))
    }
}
