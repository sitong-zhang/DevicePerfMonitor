package com.devperf.monitor.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.devperf.monitor.BuildConfig
import com.devperf.monitor.R
import com.devperf.monitor.data.MonitorSettings
import com.devperf.monitor.data.RefreshMode
import com.devperf.monitor.data.ThemeMode
import com.devperf.monitor.data.WallpaperId
import com.devperf.monitor.data.collectors.PrivilegedShell
import com.devperf.monitor.overlay.OverlayPermissions
import com.devperf.monitor.ui.MonitorViewModel
import com.devperf.monitor.ui.kit.EnterStagger
import com.devperf.monitor.ui.kit.GlassButton
import com.devperf.monitor.ui.kit.GlassButtonVariant
import com.devperf.monitor.ui.kit.GlassInfoRow
import com.devperf.monitor.ui.kit.GlassPanel
import com.devperf.monitor.ui.kit.GlassSectionHeader
import com.devperf.monitor.ui.kit.GlassSlider
import com.devperf.monitor.ui.kit.GlassSwitch
import com.devperf.monitor.ui.kit.GlassTabs
import com.devperf.monitor.ui.kit.rememberHaptics
import com.devperf.monitor.ui.theme.DesignTokens
import com.devperf.monitor.ui.theme.LocalTokenColors
import com.devperf.monitor.ui.wallpaper.Wallpapers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    onNavigateFeedback: () -> Unit,
) {
    val colors = LocalTokenColors.current
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    // 收集快照当"重组合触发器"：提权刷新后快照会在下一个 tick 更新，
    // 这里借它让下面的特权通道三态重新读一次（backend 本身不是 State）
    val snapshot by viewModel.snapshot.collectAsState()
    val haptics = rememberHaptics()

    // 权限状态跟着系统走：从系统设置页授权后返回，这里要立刻变成"已授权"
    val overlayGranted by viewModel.overlayGranted.collectAsState()
    val notificationsGranted = OverlayPermissions.canPostNotifications(context)
    val shellBackend = snapshotBackend(viewModel)

    // 用户拨了开关但还没授权：等授权一到位就自动把服务拉起来，
    // 不用逼用户回到本页再拨一次
    var pendingEnable by remember { mutableStateOf(false) }
    LaunchedEffect(overlayGranted) {
        if (overlayGranted && pendingEnable) {
            pendingEnable = false
            viewModel.setOverlayEnabled(true)
        }
    }

    // 1.0.26 相册选图（自定义壁纸）：PickVisualMedia 走系统相册面板，不需要
    // 存储权限；选中后当场把字节复制进 filesDir（返回的 Uri 授权是临时的，
    // 存 Uri 会失效），复制或解码失败就保持当前壁纸，不打扰用户。
    val scope = rememberCoroutineScope()
    val pickWallpaperLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val path = Wallpapers.importFromUri(context, uri)
                if (path != null) viewModel.setWallpaperCustom(path)
            }
        }
    }

    EnterStagger(index = 0) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    PaddingValues(
                        start = DesignTokens.Spacing.S16.dp,
                        end = DesignTokens.Spacing.S16.dp,
                        // 顶部 72dp：给壁纸右上角的公司 logo 角标留位（角标占
                        // 10~60dp，见 AppNav.drawBrandWallpaper），「设置」标题
                        // 首屏不压着角标。
                        top = 72.dp,
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
                                    haptics.tick()
                                    pendingEnable = true
                                    onRequestOverlayPermission()
                                } else {
                                    haptics.select()
                                    viewModel.setOverlayEnabled(want)
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
                        text = "Android 端悬浮窗为 Material 3 Expressive 视觉的真实半透明卡片" +
                            "（系统不提供窗口外像素采样，无法真实模糊背后的应用）。",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )

                    Spacer(Modifier.height(DesignTokens.Spacing.S4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "默认纯数字模式",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        GlassSwitch(
                            checked = settings.overlayMiniMode,
                            onCheckedChange = {
                                haptics.select()
                                viewModel.setOverlayMiniMode(it)
                            },
                        )
                    }
                    Text(
                        text = "启动悬浮窗时只要数字、不显示卡片；关闭则显示完整卡片。" +
                            "运行中单击可在 卡片 → 纯数字 → 收起 之间循环切换。",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "贴边吸附",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        GlassSwitch(
                            checked = settings.snapToEdge,
                            onCheckedChange = {
                                haptics.select()
                                viewModel.setSnapToEdge(it)
                            },
                        )
                    }
                    Text(
                        text = "拖动到屏幕边缘时自动吸附位置（不再自动收窄变形；收起请单击循环到收起态）。",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )
                    // 透明度滑块（1.0.8 起接线到窗口 alpha）
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
                            onCheckedChange = {
                                haptics.select()
                                viewModel.setCollectInBackground(it)
                            },
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
                        onSelect = {
                            haptics.select()
                            viewModel.setRefreshMode(RefreshMode.entries[it])
                        },
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
                            onCheckedChange = {
                                haptics.select()
                                viewModel.setAlertsEnabled(it)
                            },
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
                            onCheckedChange = {
                                haptics.select()
                                viewModel.setAlertByNotification(it)
                            },
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
                            onCheckedChange = {
                                haptics.select()
                                viewModel.setAlertByOverlayPopup(it)
                            },
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
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S12.dp),
                ) {
                    Text(
                        text = "悬浮窗与首页的实时帧率（前台应用/游戏真实帧率）、各核心频率、" +
                            "后台应用内存需要 Shizuku（免 root）或 root 授权。" +
                            "未授权时帧率会显示「需 Shizuku」引导，不影响其他指标。",
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
                        onClick = {
                            haptics.tick()
                            viewModel.requestPrivilege()
                        },
                        variant = GlassButtonVariant.SECONDARY,
                    )
                    Text(
                        text = "使用方式：安装 Shizuku App 并启动服务后，点击上方按钮授权；" +
                            "授权结果弹窗由系统/Shizuku 展示。",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )
                    Text(
                        text = "温度说明：优先读机身温区；部分机型（如华为）系统限制普通应用读取，" +
                            "自动回退电池温度，详情页会标注「当前为电池温度」。",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )
                }
            }

            // ================= 主题 =================
            GlassSectionHeader(text = "外观")
            GlassPanel {
                Column(
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
                        onSelect = {
                            haptics.select()
                            viewModel.setThemeMode(ThemeMode.entries[it])
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "配色为 Catppuccin Mocha / Latte（双端同一 design-tokens.json 真源）。",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )
                    // 1.0.26 壁纸自由切换：内置三张 + 相册自定义。
                    // 选择立即生效（DataStore 流驱动，主界面与悬浮窗背景同步换）。
                    Text(
                        text = "壁纸",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                    WallpaperPicker(
                        settings = settings,
                        onSelectBuiltIn = { id ->
                            haptics.select()
                            viewModel.setWallpaper(id)
                        },
                        onPickFromGallery = {
                            pickWallpaperLauncher.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    androidx.activity.result.contract.ActivityResultContracts
                                        .PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                    )
                    Text(
                        text = "壁纸同时作为全局悬浮窗的背景。自定义图片只在本机处理，" +
                            "不上传、不进相册，随时可以换回内置的。",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )
                }
            }

            // ================= 意见反馈（1.0.27 起独立页面） =================
            // 不再内联在设置页：点这行跳到意见反馈页（提交链路见 FeedbackRepository）。
            GlassInfoRow(
                label = "意见反馈",
                value = "去填写 →",
                onClick = onNavigateFeedback,
            )

            // ================= 关于 =================
            GlassSectionHeader(text = "关于")
            GlassPanel {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // 品牌卡（1.0.28）：出品方「分子智能科技」logo 与落款。
                    // logo 白底，垫在白色圆角块上避免深色主题下出现突兀白边。
                    Image(
                        painter = painterResource(R.drawable.brand_logo),
                        contentDescription = "分子智能科技 Molecule Technology",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth(0.66f)
                            .clip(RoundedCornerShape(DesignTokens.Radius.SMALL.dp))
                            .background(androidx.compose.ui.graphics.Color.White)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                    Text(
                        text = "用心感知设备的每一次跳动",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )
                    Text(
                        text = "© 2026 分子智能科技 Molecule Technology",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                    )
                }
            }
            GlassPanel {
                Column(
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S8.dp),
                ) {
                    // 版本号读 BuildConfig：与 versionName 永远一致，发版只改一处
                    GlassInfoRow(
                        label = "版本",
                        value = BuildConfig.VERSION_NAME,
                    )
                    // 1.0.13 双包标识：plain 包让用户一眼区分（无液态玻璃版本）
                    if (!BuildConfig.LIQUID_GLASS) {
                        Text(
                            text = "无液态玻璃版本",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

/** 读取当前特权通道（每次组合刷新；三态指标由采集器维护）。 */
private fun snapshotBackend(viewModel: MonitorViewModel): PrivilegedShell.Backend =
    viewModel.snapshotShellBackend()

// ---------------------------------------------------------------------------
// 1.0.26 壁纸选择（外观区）
//
// 一行横滑缩略图：三张内置（青蓝 / 极光 / 薄荷）+「从相册选」入口；已经选过
// 自定义图时，行首插入当前自定义图的缩略图（再点一次 = 重新选图）。
// 内置缩略图走 320px 专用小图（wallpaper_thumb_*.jpg），不整图解码；
// 自定义缩略图现取现解码（2048px 封顶），文件没了就只显示相册入口。
// ---------------------------------------------------------------------------

/** 壁纸缩略图的统一外框尺寸（竖版，与壁纸 1024x2220 同比例）。 */
private val ThumbWidth = 76.dp
private val ThumbHeight = 108.dp

/** internal：设置页外观区直接调用；冒烟测试（WallpaperRenderTest）直接驱动它。 */
@Composable
internal fun WallpaperPicker(
    settings: MonitorSettings,
    onSelectBuiltIn: (WallpaperId) -> Unit,
    onPickFromGallery: () -> Unit,
) {
    val builtIns = listOf(
        Triple(WallpaperId.LIGHT_TEAL, R.drawable.wallpaper_thumb_light, "青蓝"),
        Triple(WallpaperId.AURORA, R.drawable.wallpaper_thumb_aurora, "极光"),
        Triple(WallpaperId.MINT, R.drawable.wallpaper_thumb_mint, "薄荷"),
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S8.dp)) {
        if (settings.wallpaper == WallpaperId.CUSTOM) {
            item(key = "custom") {
                CustomWallpaperThumb(
                    path = settings.wallpaperCustomPath,
                    onClick = onPickFromGallery,
                )
            }
        }
        items(items = builtIns, key = { it.first.name }) { (id, thumbRes, label) ->
            WallpaperThumbItem(
                painter = painterResource(thumbRes),
                label = label,
                selected = settings.wallpaper == id,
                onClick = { onSelectBuiltIn(id) },
            )
        }
        item(key = "gallery") {
            GalleryTile(onClick = onPickFromGallery)
        }
    }
}

/** 一张壁纸缩略图：图 + 底部名称；选中时主色描边 + 右上角对勾。 */
@Composable
private fun WallpaperThumbItem(
    painter: Painter,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(ThumbWidth, ThumbHeight)
                .clip(shape)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) cs.primary else cs.outline.copy(alpha = 0.4f),
                    shape = shape,
                )
                .clickable(onClick = onClick),
        ) {
            Image(
                painter = painter,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(cs.primary),
                    contentAlignment = Alignment.Center,
                ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "已选中",
                    tint = cs.onPrimary,
                    modifier = Modifier.size(12.dp),
                )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) cs.primary else cs.onSurfaceVariant,
        )
    }
}

/** 自定义壁纸缩略图：现取现解码，解码不出来就不占位（相册入口仍在）。 */
@Composable
private fun CustomWallpaperThumb(path: String?, onClick: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = path?.takeIf { File(it).isFile }?.let { p ->
            withContext(Dispatchers.IO) { Wallpapers.decodeCustom(p) }
        }
    }
    val image = bitmap
    if (image != null) {
        WallpaperThumbItem(
            painter = BitmapPainter(image),
            label = "自定义",
            selected = true,
            onClick = onClick,
        )
    }
}

/** 「从相册选」入口：虚线感（细描边）方块 + 加号。 */
@Composable
private fun GalleryTile(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(ThumbWidth, ThumbHeight)
                .clip(shape)
                .border(
                    width = 1.dp,
                    color = cs.outline.copy(alpha = 0.4f),
                    shape = shape,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "相册",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "自定义",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
        )
    }
}
