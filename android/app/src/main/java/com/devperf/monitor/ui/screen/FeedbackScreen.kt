package com.devperf.monitor.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.devperf.monitor.data.FeedbackRepository
import com.devperf.monitor.ui.kit.EnterStagger
import com.devperf.monitor.ui.kit.GlassButton
import com.devperf.monitor.ui.kit.GlassButtonVariant
import com.devperf.monitor.ui.kit.rememberHaptics
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.LocalTokenColors

/**
 * 意见反馈页（独立页面，账号界面 / 禁用闸门进入）。
 *
 * 纯本地拼装 mailto 链接（收件人=官方邮箱、主题=版本+机型、正文=反馈内容+联系方式+
 * 设备信息），拉起系统邮件 App，用户点发送即达——与设置页「意见反馈」同一通道。
 * 禁用账号下这是唯一可用的功能。
 */
@Composable
fun FeedbackScreen(onBack: () -> Unit) {
    val colors = LocalTokenColors.current
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val repo = remember { FeedbackRepository() }
    var content by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }

    EnterStagger(index = 0) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    PaddingValues(
                        start = DesignTokens.Spacing.S16.dp,
                        end = DesignTokens.Spacing.S16.dp,
                        top = 72.dp,
                        bottom = 96.dp,
                    ),
                ),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S12.dp),
        ) {
            Text(
                text = "意见反馈",
                style = MaterialTheme.typography.titleLarge,
                color = colors.textPrimary,
            )
            Text(
                text = "你的反馈会发送到官方邮箱，开发者看到后会尽快回复。",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
            )

            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("反馈内容（必填）") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
                singleLine = false,
            )
            OutlinedTextField(
                value = contact,
                onValueChange = { contact = it },
                label = { Text("联系方式（选填，便于回复）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            GlassButton(
                text = "发送反馈",
                onClick = {
                    haptics.tick()
                    openMail(context, repo.buildMailto(content, contact))
                    onBack()
                },
                variant = GlassButtonVariant.PRIMARY,
                enabled = content.isNotBlank(),
            )
            GlassButton(
                text = "返回",
                onClick = { haptics.tick(); onBack() },
                variant = GlassButtonVariant.SECONDARY,
            )
        }
    }
}

/** 打开邮件 App（[mailto] 为完整 mailto: 链接，收件人/主题/正文可预填）。 */
private fun openMail(context: Context, mailto: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(mailto)))
    }
}
