package com.devperf.monitor.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.devperf.monitor.MonitorApp
import com.devperf.monitor.account.AccountManager
import com.devperf.monitor.ui.MonitorViewModel
import com.devperf.monitor.ui.kit.EnterStagger
import com.devperf.monitor.ui.kit.GlassButton
import com.devperf.monitor.ui.kit.GlassButtonVariant
import com.devperf.monitor.ui.kit.GlassInfoRow
import com.devperf.monitor.ui.kit.GlassPanel
import com.devperf.monitor.ui.kit.GlassSectionHeader
import com.devperf.monitor.ui.kit.rememberHaptics
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.LocalTokenColors

/**
 * 账号界面（独立页面，底部「账号」tab 进入）。
 *
 * - 正常态：展示昵称 / 账号 / 邮箱 / 手机号、当前状态（正常 / 受限 / 禁用）、
 *   限制说明（受限时）、意见反馈入口、退出登录。
 * - 锁定态（[locked]=true，账号被管理员禁用）：只保留「提交反馈」与「退出登录」，
 *   其余信息折叠，顶部醒目提示「账号已被管理员禁用」——实现「禁用后只能反馈」。
 */
@Composable
fun AccountScreen(
    viewModel: MonitorViewModel,
    locked: Boolean = false,
    onFeedback: () -> Unit,
    onLogout: () -> Unit,
) {
    val colors = LocalTokenColors.current
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val accountManager = (context.applicationContext as MonitorApp).accountManager
    val account by accountManager.session.collectAsState()
    val remote by viewModel.remoteState.collectAsState()
    val status = remote?.status ?: "normal"
    val note = (remote?.restrictionNote ?: "").trim()

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
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S8.dp),
        ) {
            Text(
                text = if (locked) "账号已禁用" else "我的账号",
                style = MaterialTheme.typography.titleLarge,
                color = colors.textPrimary,
            )

            if (locked) {
                // 禁用锁定视图：仅反馈 + 退出登录
                GlassPanel {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S12.dp),
                    ) {
                        Text(
                            text = "账号已被管理员禁用",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.danger,
                        )
                        if (note.isNotBlank()) {
                            Text(
                                text = note,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary,
                            )
                        }
                        Text(
                            text = "当前仅可提交反馈，其余功能已暂停。如有疑问可通过反馈联系管理员。",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary,
                        )
                    }
                }
                GlassButton(
                    text = "提交反馈",
                    onClick = { haptics.tick(); onFeedback() },
                    variant = GlassButtonVariant.PRIMARY,
                )
                GlassButton(
                    text = "退出登录",
                    onClick = { haptics.tick(); accountManager.logout() },
                    variant = GlassButtonVariant.DANGER,
                )
                return@Column
            }

            // 正常态：账号信息
            GlassSectionHeader(text = "账号信息")
            GlassPanel {
                Column(
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S12.dp),
                ) {
                    if (account != null) {
                        GlassInfoRow(
                            label = "昵称",
                            value = account?.nickname?.ifBlank { "—" } ?: "—",
                        )
                        GlassInfoRow(label = "账号", value = account?.account ?: "—")
                        GlassInfoRow(label = "邮箱", value = account?.email ?: "—")
                        GlassInfoRow(label = "手机号", value = account?.phone ?: "—")
                    } else {
                        Text(
                            text = "未登录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                        )
                    }
                }
            }

            // 状态
            GlassSectionHeader(text = "账号状态")
            GlassPanel {
                Column(
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S12.dp),
                ) {
                    val statusLabel = when (status) {
                        "disabled" -> "已禁用"
                        "restricted" -> "受限"
                        else -> "正常"
                    }
                    val statusColor = when (status) {
                        "disabled" -> colors.danger
                        "restricted" -> colors.warning
                        else -> colors.success
                    }
                    GlassInfoRow(
                        label = "当前状态",
                        value = statusLabel,
                        valueColor = statusColor,
                    )
                    if (status == "restricted" && note.isNotBlank()) {
                        Text(
                            text = note,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary,
                        )
                    }
                }
            }

            // 反馈 + 退出
            GlassButton(
                text = "意见反馈",
                onClick = { haptics.tick(); onFeedback() },
                variant = GlassButtonVariant.SECONDARY,
            )
            GlassButton(
                text = "退出登录",
                onClick = { haptics.tick(); accountManager.logout() },
                variant = GlassButtonVariant.DANGER,
            )
        }
    }
}
