package com.devperf.monitor.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.devperf.monitor.data.RefreshMode
import com.devperf.monitor.data.ThemeMode
import com.devperf.monitor.data.collectors.PrivilegedShell
import com.devperf.monitor.overlay.OverlayPermissions
import com.devperf.monitor.overlay.OverlayService
import com.devperf.monitor.ui.MonitorViewModel
import com.devperf.monitor.ui.kit.GlassButton
import com.devperf.monitor.ui.kit.GlassButtonVariant
import com.devperf.monitor.ui.kit.GlassInfoRow
import com.devperf.monitor.ui.kit.GlassPanel
import com.devperf.monitor.ui.kit.GlassSectionHeader
import com.devperf.monitor.ui.kit.GlassSlider
import com.devperf.monitor.ui.kit.GlassSwitch
import com.devperf.monitor.ui.kit.GlassTabs
import com.devperf.monitor.ui.kit.LiquidGlassScaffold
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.LocalTokenColors

/**
 * 设置页：悬浮窗 / 采集 / 告警 / 提权 / 主题 / 权限状态 / 关于。
 *
 * 权限处理规范：
 * - 悬浮窗开关打开时若无权限 → 先跳系统设置页引导（返回后 onResume 自动同步服务）；
 * - Shizuku 未安装/未授权 → 显示状态与引导（安装 Shizuku App 后重启本应用）；
 * - 每个影响功能的权限都在 UI 上明示当前状态，不做隐性失败。
 */
@Composable
fun SettingsScreen(
    viewModel: MonitorViewModel,
    onRequestOverlayPermission: () -> Unit,
) {
    val colors = LocalTokenColors.current
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val snapshot by viewModel.snapshot.collectAsState()

    // 权限状态跟着系统走：从系统设置页授权后返回，这里要立刻变成"已授权"
    val overlayGranted by viewModel.overlayGranted.collectAsState()
    val notificationsGranted = OverlayPermissions.canPostNotifications(context)
    val shellBackend = snapshotBackend(viewModel)

    LiquidGlassScaffold {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    PaddingValues(
                        start = DesignTokens.Spacing.S16.dp,
                        end = DesignTokens.Spacing.S16.dp,
                        top = DesignTokens.Spacing.S32.dp,
                        bottom = 96.dp,
                    ),
                ),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S8.dp),
        ) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleLarge,
                color = colors.textPrimary,
            )

            // ================= 悬浮窗 =================
            GlassSectionHeader(text = "全局悬浮窗")
            GlassPanel {
                Column(
                    modifier = Modifier.padding(DesignTokens.Spacing.S16.dp),
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S12.dp),
                ) {
                    // 主开关：打开时无权限先引导
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "启用悬浮窗",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        GlassSwitch(
                            checked = settings.overlayEnabled,
                            onCheckedChange = { want ->
                                if (want && !overlayGranted) {
                                    onRequestOverlayPermission()
                                } else {
                                    viewModel.setOverlayEnabled(want)
                                    if (want) OverlayService.start(context)
                                }
                            },
                        )
                    }
                    if (!overlayGranted) {
                        Text(
                            text = "未获得「显示在其他应用上层」权限，点击开关前往系统设置授权；" +
                                "授权后返回本页自动生效。",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.warning,
                        )
                    }
                    GlassInfoRow(
                        label = "权限状态",
                        value = if (overlayGranted) "已授权" else "未授权",
                        valueColor = if (overlayGranted) colors.success else colors.warning,
                    )
                    Text(
                        text = "悬浮窗无法真实模糊其他应用画面（系统不提供窗口外像素采样），" +
                            "已按真实半透明 + 自绘高光/描边/噪点降级实现，详见 README 降级说明。",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )

                    Spacer(Modifier.height(DesignTokens.Spacing.S4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "默认迷你模式",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        GlassSwitch(
                            checked = settings.overlayMiniMode,
                            onCheckedChange = { viewModel.setOverlayMiniMode(it) },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "贴边吸附",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        GlassSwitch(
                            checked = settings.snapToEdge,
                            onCheckedChange = { viewModel.setSnapToEdge(it) },
                        )
                    }
                    // 透明度滑块
                    Text(
                        text = "悬浮窗不透明度 ${(settings.overlayOpacity * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                    GlassSlider(
                        value = settings.overlayOpacity,
                        onValueChange = { viewModel.setOverlayOpacity(it) },
                        valueRange = 0.4f..1f,
                    )
                    // 缩放滑块
                    Text(
                        text = "默认缩放 ${String.format("%.2f", settings.overlayScale)}×",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                    GlassSlider(
                        value = settings.overlayScale,
                        onValueChange = { viewModel.setOverlayScale(it) },
                        valueRange = 0.8f..1.6f,
                    )
                }
            }

            // ================= 采集 =================
            GlassSectionHeader(text = "数据采集")
            GlassPanel {
                Column(
                    modifier = Modifier.padding(DesignTokens.Spacing.S16.dp),
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "后台持续采集",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        GlassSwitch(
                            checked = settings.collectInBackground,
                            onCheckedChange = { viewModel.setCollectInBackground(it) },
                        )
                    }
                    Text(
                        text = "刷新频率",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                    GlassTabs(
                        options = RefreshMode.entries.map { it.label },
                        selectedIndex = RefreshMode.entries.indexOf(settings.refreshMode),
                        onSelect = { viewModel.setRefreshMode(RefreshMode.entries[it]) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "历史数据仅保存在本机（内存环形缓冲 + 本地文件），" +
                            "本应用不申请 INTERNET 权限，无任何联网行为。",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )
                }
            }

            // ================= 告警 =================
            GlassSectionHeader(text = "告警")
            GlassPanel {
                Column(
                    modifier = Modifier.padding(DesignTokens.Spacing.S16.dp),
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "启用告警",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        GlassSwitch(
                            checked = settings.alertsEnabled,
                            onCheckedChange = { viewModel.setAlertsEnabled(it) },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "通知栏提醒",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        GlassSwitch(
                            checked = settings.alertByNotification,
                            onCheckedChange = { viewModel.setAlertByNotification(it) },
                        )
                    }
                    if (!notificationsGranted) {
                        Text(
                            text = "通知权限未授权，告警通知将静默降级（系统设置中手动开启）。",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.warning,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "悬浮窗弹窗提醒",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        GlassSwitch(
                            checked = settings.alertByOverlayPopup,
                            onCheckedChange = { viewModel.setAlertByOverlayPopup(it) },
                        )
                    }
                    Text(
                        text = "温度告警阈值 ${settings.tempAlertThresholdC.toInt()}℃",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                    GlassSlider(
                        value = settings.tempAlertThresholdC,
                        onValueChange = { viewModel.setTempAlertThreshold(it) },
                        valueRange = 35f..60f,
                        steps = 24,
                    )
                }
            }

            // ================= 提权（Shizuku / root） =================
            GlassSectionHeader(text = "特权指标")
            GlassPanel {
                Column(
                    modifier = Modifier.padding(DesignTokens.Spacing.S16.dp),
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S12.dp),
                ) {
                    Text(
                        text = "全局 FPS、各核心频率、后台应用内存需要 Shizuku（免 root）或 root 授权；" +
                            "未授权时这些指标显示「需授权/不可用」，不影响其他指标。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                    GlassInfoRow(
                        label = "当前特权通道",
                        value = when (shellBackend) {
                            PrivilegedShell.Backend.ROOT -> "root"
                            PrivilegedShell.Backend.SHIZUKU -> "Shizuku"
                            PrivilegedShell.Backend.NONE -> "无"
                        },
                        valueColor = if (shellBackend == PrivilegedShell.Backend.NONE) colors.warning else colors.success,
                    )
                    GlassButton(
                        text = if (shellBackend == PrivilegedShell.Backend.NONE) "申请 Shizuku 授权" else "刷新状态",
                        onClick = { viewModel.requestPrivilege() },
                        variant = GlassButtonVariant.SECONDARY,
                    )
                    Text(
                        text = "使用方式：安装 Shizuku App 并启动服务后，点击上方按钮授权；" +
                            "授权结果弹窗由系统/Shizuku 展示。",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )
                }
            }

            // ================= 主题 =================
            GlassSectionHeader(text = "外观")
            GlassPanel {
                Column(
                    modifier = Modifier.padding(DesignTokens.Spacing.S16.dp),
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S12.dp),
                ) {
                    Text(
                        text = "主题",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                    GlassTabs(
                        options = ThemeMode.entries.map { it.label },
                        selectedIndex = ThemeMode.entries.indexOf(settings.themeMode),
                        onSelect = { viewModel.setThemeMode(ThemeMode.entries[it]) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "配色为 Catppuccin Mocha / Latte（双端同一 design-tokens.json 真源）。",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )
                }
            }

            // ================= 关于 =================
            GlassSectionHeader(text = "关于")
            GlassPanel {
                Column(
                    modifier = Modifier.padding(DesignTokens.Spacing.S16.dp),
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S8.dp),
                ) {
                    Text(
                        text = "设备性能监控 1.0.0",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "全部数据本地采集、本地存储；不联网、不上传、不含第三方统计 SDK。" +
                            "指标三态规范：可用 / 不可用 / 需授权，任何接口读取失败均降级显示，" +
                            "绝不显示估算值冒充真实数据。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                    Text(
                        text = "开源许可：Backdrop（Kyant0，Apache-2.0）、Vico（Apache-2.0）、" +
                            "Shizuku（Apache-2.0）、字体 Inter / JetBrains Mono（OFL-1.1）。",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }
}

/** 读取当前特权通道（每次组合刷新；三态指标由采集器维护）。 */
private fun snapshotBackend(viewModel: MonitorViewModel): PrivilegedShell.Backend =
    viewModel.snapshotShellBackend()
