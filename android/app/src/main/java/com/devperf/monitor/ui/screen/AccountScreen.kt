package com.devperf.monitor.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.devperf.monitor.MonitorApp
import com.devperf.monitor.account.AccountManager
import com.devperf.monitor.ui.MonitorViewModel
import com.devperf.monitor.ui.kit.AccountAvatar
import com.devperf.monitor.ui.kit.AccountEntryRow
import com.devperf.monitor.ui.kit.EnterStagger
import com.devperf.monitor.ui.kit.GlassButton
import com.devperf.monitor.ui.kit.GlassButtonVariant
import com.devperf.monitor.ui.kit.GlassPanel
import com.devperf.monitor.ui.kit.GlassSectionHeader
import com.devperf.monitor.ui.kit.LiquidDialog
import com.devperf.monitor.ui.theme.LocalTokenColors
import com.devperf.monitor.ui.kit.rememberHaptics
import com.devperf.monitor.ui.theme.DesignTokens

/**
 * 账号中心（账号 tab 入口）：从原来的单页信息，升级为多页枢纽（1.0.52）。
 *
 * - 顶部头像 + 昵称摘要；
 * - 四个入口：头像卡 / 安全中心 / 我的设备 / 订阅，点击进入对应子页
 *   （均与用户端账号体系深度绑定：昵称来自 [AccountManager]、安全状态来自
 *   [MonitorViewModel.remoteState]、设备来自本机、订阅来自套餐配置）；
 * - 反馈 + 退出登录（玻璃二次确认）。
 *
 * 锁定态（[locked]=true，账号被管理员禁用）：只保留「提交反馈」与「退出登录」，
 * 其余信息折叠，顶部醒目提示——实现「禁用后只能反馈」。
 */
@Composable
fun AccountScreen(
    viewModel: MonitorViewModel,
    navController: NavHostController,
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
    // 1.0.51：退出登录前先弹玻璃确认（LiquidDialog），防误触
    var showLogoutConfirm by remember { mutableStateOf(false) }

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
                    onClick = { haptics.tick(); showLogoutConfirm = true },
                    variant = GlassButtonVariant.DANGER,
                )
                return@Column
            }

            // 头像 + 昵称摘要
            GlassPanel {
                Column(
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AccountAvatar(
                        name = account?.nickname?.ifBlank { account?.account } ?: "?",
                        size = 80.dp,
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = account?.nickname?.ifBlank { account?.account } ?: "未登录",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary,
                        )
                        if (account != null) {
                            val acc = account!!
                            Text(
                                text = acc.email.ifBlank { acc.account },
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }
            }

            // 账号入口
            GlassSectionHeader(text = "账号")
            AccountEntryRow(
                icon = Icons.Filled.Person,
                title = "头像卡",
                subtitle = account?.nickname?.ifBlank { "个人资料与昵称" } ?: "个人资料",
                onClick = { haptics.tick(); navController.navigate("accountProfile") },
            )
            AccountEntryRow(
                icon = Icons.Filled.Shield,
                title = "安全中心",
                subtitle = "密码 · 登录设备 · 账号状态",
                onClick = { haptics.tick(); navController.navigate("accountSecurity") },
            )
            AccountEntryRow(
                icon = Icons.Filled.PhoneAndroid,
                title = "我的设备",
                subtitle = "当前设备与数据上报",
                onClick = { haptics.tick(); navController.navigate("accountDevices") },
            )
            AccountEntryRow(
                icon = Icons.Filled.Star,
                title = "订阅",
                subtitle = "当前套餐与权益",
                onClick = { haptics.tick(); navController.navigate("accountSubscription") },
            )

            // 反馈 + 退出
            GlassButton(
                text = "意见反馈",
                onClick = { haptics.tick(); onFeedback() },
                variant = GlassButtonVariant.SECONDARY,
            )
            GlassButton(
                text = "退出登录",
                onClick = { haptics.tick(); showLogoutConfirm = true },
                variant = GlassButtonVariant.DANGER,
            )
        }
    }

    if (showLogoutConfirm) {
        LiquidDialog(
            title = "退出登录",
            confirmText = "退出",
            dismissText = "取消",
            onConfirm = {
                showLogoutConfirm = false
                haptics.tick()
                accountManager.logout()
            },
            onDismiss = { showLogoutConfirm = false },
            dismissible = true,
        ) {
            Text(
                "退出后将停止数据采集与上传，重新登录即可恢复。",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
        }
    }
}
