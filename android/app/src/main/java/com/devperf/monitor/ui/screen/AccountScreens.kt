package com.devperf.monitor.ui.screen

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.BatteryManager
import android.os.StatFs
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devperf.monitor.BuildConfig
import com.devperf.monitor.MonitorApp
import com.devperf.monitor.account.AccountManager
import com.devperf.monitor.ui.MonitorViewModel
import com.devperf.monitor.ui.kit.AccountAvatar
import com.devperf.monitor.ui.kit.AccountEntryRow
import com.devperf.monitor.ui.kit.EnterStagger
import com.devperf.monitor.ui.kit.GlassButton
import com.devperf.monitor.ui.kit.GlassButtonVariant
import com.devperf.monitor.ui.kit.GlassInfoRow
import com.devperf.monitor.ui.kit.GlassPanel
import com.devperf.monitor.ui.kit.GlassSectionHeader
import com.devperf.monitor.ui.kit.GlassTextField
import com.devperf.monitor.ui.kit.LiquidDialog
import com.devperf.monitor.ui.kit.rememberHaptics
import com.devperf.monitor.ui.theme.LocalTokenColors
import com.devperf.monitor.ui.theme.DesignTokens
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 账号子页统一的返回栏（与用户端其余子页一致）。 */
@Composable
private fun AccountTopBar(title: String, onBack: () -> Unit) {
    val colors = LocalTokenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = DesignTokens.Spacing.S8.dp,
                vertical = DesignTokens.Spacing.S8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = colors.textPrimary)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

private fun formatDate(ts: Long): String {
    if (ts <= 0L) return "—"
    return try {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ts))
    } catch (_: Exception) {
        "—"
    }
}

private fun statusLabel(status: String): String = when (status) {
    "disabled" -> "已禁用"
    "restricted" -> "受限"
    else -> "正常"
}

// ---------------------------------------------------------------------------
// 头像卡 / 个人资料
// ---------------------------------------------------------------------------

@Composable
fun ProfileScreen(
    accountManager: AccountManager,
    onBack: () -> Unit,
) {
    val colors = LocalTokenColors.current
    val haptics = rememberHaptics()
    val account by accountManager.session.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(account?.nickname ?: "") }
    var saving by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    EnterStagger(index = 0) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    PaddingValues(
                        start = DesignTokens.Spacing.S16.dp,
                        end = DesignTokens.Spacing.S16.dp,
                        top = 24.dp,
                        bottom = 96.dp,
                    ),
                ),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S8.dp),
        ) {
            AccountTopBar(title = "个人资料", onBack = onBack)

            val acc = account
            if (acc != null) {
                GlassPanel {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S16.dp),
                    ) {
                        AccountAvatar(
                            name = acc.nickname.ifBlank { acc.account },
                            size = 72.dp,
                        )
                        Column {
                            Text(
                                text = acc.nickname.ifBlank { acc.account },
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary,
                            )
                            Text(
                                text = "@${acc.account}",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }

                GlassSectionHeader(text = "基本信息")
                GlassPanel {
                    GlassInfoRow(label = "账号", value = acc.account)
                    GlassInfoRow(label = "邮箱", value = acc.email.ifBlank { "—" })
                    GlassInfoRow(label = "手机号", value = if (acc.phone.isNotBlank()) acc.phone else "未绑定")
                    GlassInfoRow(label = "注册时间", value = formatDate(acc.createdAt))
                    GlassInfoRow(
                        label = "账号状态",
                        value = statusLabel(acc.status),
                        valueColor = when (acc.status) {
                            "disabled" -> colors.danger
                            "restricted" -> colors.warning
                            else -> colors.success
                        },
                    )
                }

                GlassSectionHeader(text = "昵称")
                GlassPanel {
                    if (editing) {
                        GlassTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            label = "昵称",
                            singleLine = true,
                        )
                        if (err != null) {
                            Text(
                                text = err!!,
                                color = colors.danger,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S12.dp),
                        ) {
                            GlassButton(
                                text = "取消",
                                onClick = {
                                    editing = false
                                    draft = acc.nickname
                                    err = null
                                },
                                variant = GlassButtonVariant.SECONDARY,
                                modifier = Modifier.weight(1f),
                            )
                            GlassButton(
                                text = if (saving) "保存中…" else "保存",
                                onClick = {
                                    haptics.tick()
                                    saving = true
                                    err = null
                                    scope.launch {
                                        val e = accountManager.updateProfile(draft)
                                        saving = false
                                        if (e == null) {
                                            editing = false
                                        } else {
                                            err = e
                                        }
                                    }
                                },
                                variant = GlassButtonVariant.PRIMARY,
                                enabled = !saving && draft.isNotBlank(),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        GlassInfoRow(label = "昵称", value = acc.nickname.ifBlank { "—" })
                        GlassButton(
                            text = "编辑昵称",
                            onClick = {
                                haptics.tick()
                                draft = acc.nickname
                                editing = true
                            },
                            variant = GlassButtonVariant.SECONDARY,
                        )
                    }
                }
            } else {
                GlassPanel {
                    Text("未登录", color = colors.textSecondary)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 安全中心
// ---------------------------------------------------------------------------

@Composable
fun SecurityCenterScreen(
    accountManager: AccountManager,
    viewModel: MonitorViewModel,
    onBack: () -> Unit,
) {
    val colors = LocalTokenColors.current
    val haptics = rememberHaptics()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val remote by viewModel.remoteState.collectAsState()
    val status = remote?.status ?: "normal"
    val note = (remote?.restrictionNote ?: "").trim()

    var showPw by remember { mutableStateOf(false) }
    var oldPw by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var confirmPw by remember { mutableStateOf("") }
    var pwSaving by remember { mutableStateOf(false) }
    var pwErr by remember { mutableStateOf<String?>(null) }

    var sessions by remember { mutableStateOf<List<AccountManager.SessionInfo>>(emptyList()) }
    var sessionsErr by remember { mutableStateOf<String?>(null) }
    var revoking by remember { mutableStateOf<String?>(null) }

    fun loadSessions() {
        scope.launch {
            val r = accountManager.listSessions()
            sessions = r.sessions
            sessionsErr = r.error
        }
    }
    LaunchedEffect(Unit) { loadSessions() }

    EnterStagger(index = 0) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    PaddingValues(
                        start = DesignTokens.Spacing.S16.dp,
                        end = DesignTokens.Spacing.S16.dp,
                        top = 24.dp,
                        bottom = 96.dp,
                    ),
                ),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S8.dp),
        ) {
            AccountTopBar(title = "安全中心", onBack = onBack)

            // 账号状态与限制
            GlassSectionHeader(text = "账号状态")
            GlassPanel {
                val (label, color) = when (status) {
                    "disabled" -> "已禁用" to colors.danger
                    "restricted" -> "受限" to colors.warning
                    else -> "正常" to colors.success
                }
                GlassInfoRow(label = "当前状态", value = label, valueColor = color)
                if (status == "restricted" && note.isNotBlank()) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(
                            horizontal = DesignTokens.Spacing.S12.dp,
                            vertical = DesignTokens.Spacing.S8.dp,
                        ),
                    )
                }
            }

            // 密码
            GlassSectionHeader(text = "登录密码")
            GlassPanel {
                GlassInfoRow(
                    label = "密码",
                    value = "••••••••",
                    onClick = { showPw = true },
                )
                GlassButton(
                    text = "修改密码",
                    onClick = {
                        haptics.tick()
                        oldPw = ""
                        newPw = ""
                        confirmPw = ""
                        pwErr = null
                        showPw = true
                    },
                    variant = GlassButtonVariant.PRIMARY,
                )
            }

            // 登录设备 / 会话
            GlassSectionHeader(text = "登录设备")
            GlassPanel {
                if (sessionsErr != null) {
                    Text(
                        text = sessionsErr ?: "加载失败",
                        color = colors.danger,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(
                            horizontal = DesignTokens.Spacing.S12.dp,
                            vertical = DesignTokens.Spacing.S8.dp,
                        ),
                    )
                } else if (sessions.isEmpty()) {
                    Text(
                        text = "暂无登录会话",
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(
                            horizontal = DesignTokens.Spacing.S12.dp,
                            vertical = DesignTokens.Spacing.S8.dp,
                        ),
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S8.dp)) {
                        sessions.forEach { s ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = DesignTokens.Spacing.S12.dp,
                                        vertical = DesignTokens.Spacing.S8.dp,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (s.current) "本机（当前）" else "其他设备",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.textPrimary,
                                    )
                                    Text(
                                        text = "${s.masked} · 登录于 ${formatDate(s.createdAt)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textSecondary,
                                    )
                                }
                                GlassButton(
                                    text = if (revoking == s.token) "…" else "吊销",
                                    onClick = {
                                        haptics.tick()
                                        revoking = s.token
                                        scope.launch {
                                            val e = accountManager.revokeSession(s.token)
                                            revoking = null
                                            if (e == null) {
                                                if (s.current) {
                                                    accountManager.logout()
                                                } else {
                                                    loadSessions()
                                                }
                                            }
                                        }
                                    },
                                    variant = GlassButtonVariant.DANGER,
                                    compact = true,
                                    enabled = revoking == null,
                                )
                            }
                        }
                    }
                }
            }

            // 安全建议
            GlassSectionHeader(text = "安全建议")
            GlassPanel {
                Text(
                    text = "• 使用与其他网站不同的独立密码；\n" +
                        "• 定期修改密码，不要在公共设备保持登录；\n" +
                        "• 发现陌生登录设备请立即吊销。",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(
                        horizontal = DesignTokens.Spacing.S12.dp,
                        vertical = DesignTokens.Spacing.S8.dp,
                    ),
                )
            }
        }
    }

    if (showPw) {
        LiquidDialog(
            title = "修改密码",
            confirmText = if (pwSaving) "提交中…" else "确定",
            dismissText = "取消",
            onConfirm = {
                pwSaving = true
                pwErr = null
                scope.launch {
                    val localErr = when {
                        oldPw.isEmpty() || newPw.isEmpty() -> "请填写当前密码与新密码"
                        newPw.length < 6 -> "新密码至少 6 位"
                        newPw != confirmPw -> "两次输入的新密码不一致"
                        else -> null
                    }
                    if (localErr != null) {
                        pwErr = localErr
                        pwSaving = false
                        return@launch
                    }
                    val e = accountManager.changePassword(oldPw, newPw)
                    pwSaving = false
                    if (e == null) {
                        showPw = false
                    } else {
                        pwErr = e
                    }
                }
            },
            onDismiss = { showPw = false },
            dismissible = true,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S8.dp)) {
                GlassTextField(
                    value = oldPw,
                    onValueChange = { oldPw = it },
                    label = "当前密码",
                    password = true,
                )
                GlassTextField(
                    value = newPw,
                    onValueChange = { newPw = it },
                    label = "新密码",
                    password = true,
                )
                GlassTextField(
                    value = confirmPw,
                    onValueChange = { confirmPw = it },
                    label = "确认新密码",
                    password = true,
                )
                if (pwErr != null) {
                    Text(
                        text = pwErr!!,
                        color = colors.danger,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 我的设备
// ---------------------------------------------------------------------------

@Composable
fun DevicesScreen(
    onBack: () -> Unit,
) {
    val colors = LocalTokenColors.current
    val context = LocalContext.current
    val app = context.applicationContext as MonitorApp
    // 一次采集这台设备能读到的所有信息（分组），展示时逐项列出
    val sections = remember { collectDeviceInfo(context) }

    EnterStagger(index = 0) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    PaddingValues(
                        start = DesignTokens.Spacing.S16.dp,
                        end = DesignTokens.Spacing.S16.dp,
                        top = 24.dp,
                        bottom = 96.dp,
                    ),
                ),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S8.dp),
        ) {
            AccountTopBar(title = "我的设备", onBack = onBack)

            sections.forEach { section ->
                GlassSectionHeader(text = section.first)
                GlassPanel {
                    section.second.forEach { (label, value) ->
                        GlassInfoRow(label = label, value = value)
                    }
                }
            }

            GlassSectionHeader(text = "账号")
            GlassPanel {
                val account by app.accountManager.session.collectAsState()
                GlassInfoRow(label = "已登录账号", value = account?.account ?: "未登录")
            }
        }
    }
}

/**
 * 采集这台设备能读到的全部信息：标识 / 系统 / 芯片与内存 / 屏幕 / 电池 / 网络。
 * 每一项都来自公开 API，读不到的字段如实留空或跳过——不编造。
 */
private fun collectDeviceInfo(context: android.content.Context): List<Pair<String, List<Pair<String, String>>>> {
    val abis = Build.SUPPORTED_ABIS?.joinToString(", ") ?: "—"
    val buildDate = try {
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(Build.TIME))
    } catch (_: Exception) {
        "—"
    }
    val kernel = readKernelVersion()

    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memInfo = android.app.ActivityManager.MemoryInfo()
    am?.getMemoryInfo(memInfo)
    val ramGB = if (memInfo.totalMem > 0) {
        "%.2f GB".format(memInfo.totalMem / 1024.0 / 1024.0 / 1024.0)
    } else {
        "读取失败"
    }

    val stat = try {
        StatFs(context.filesDir.absolutePath)
    } catch (_: Exception) {
        null
    }
    val totalStorage = stat?.let {
        val bytes = it.blockCountLong * it.blockSizeLong
        "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    } ?: "读取失败"
    val freeStorage = stat?.let {
        val bytes = it.availableBlocksLong * it.blockSizeLong
        "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    } ?: "—"

    // 屏幕：真实分辨率 + 密度 + 刷新率（display 在新版本被标记过时但依旧可用）
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    val metrics = DisplayMetrics()
    var refreshRate = 0f
    @Suppress("DEPRECATION")
    wm?.defaultDisplay?.apply {
        getRealMetrics(metrics)
        refreshRate = this.refreshRate
    }
    val density = when (metrics.densityDpi) {
        in 0..120 -> "ldpi"
        in 121..160 -> "mdpi"
        in 161..240 -> "hdpi"
        in 241..320 -> "xhdpi"
        in 321..480 -> "xxhdpi"
        in 481..640 -> "xxxhdpi"
        else -> "${metrics.densityDpi} dpi"
    }
    val resolution = if (metrics.widthPixels > 0) {
        "${metrics.widthPixels} × ${metrics.heightPixels}（${density}）"
    } else {
        "读取失败"
    }

    // 电池：粘性广播读当前电量与状态
    val battery = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val statusText = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
            BatteryManager.BATTERY_STATUS_FULL -> "已充满"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
            else -> "未知"
        }
        val plugText = when (plugged) {
            0 -> ""
            BatteryManager.BATTERY_PLUGGED_AC -> "（电源）"
            BatteryManager.BATTERY_PLUGGED_USB -> "（USB）"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "（无线）"
            else -> ""
        }
        "$pct% · $statusText$plugText"
    }.getOrDefault("读取失败")

    // 网络：当前连接类型
    val network = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "未连接"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                "移动数据（${networkTypeName(tm?.dataNetworkType ?: 0)}）"
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            else -> "其他"
        }
    }.getOrDefault("读取失败")

    val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MODEL.takeIf { it.isNotBlank() } ?: "—"
    } else {
        "—"
    }

    return listOf(
        "设备标识" to listOf(
            "品牌" to (Build.BRAND.takeIf { it.isNotBlank() } ?: "—"),
            "厂商" to (Build.MANUFACTURER.takeIf { it.isNotBlank() } ?: "—"),
            "型号" to (Build.MODEL.takeIf { it.isNotBlank() } ?: "—"),
            "产品名" to (Build.PRODUCT.takeIf { it.isNotBlank() } ?: "—"),
            "设备名" to (Build.DEVICE.takeIf { it.isNotBlank() } ?: "—"),
            "主板" to (Build.BOARD.takeIf { it.isNotBlank() } ?: "—"),
            "硬件" to (Build.HARDWARE.takeIf { it.isNotBlank() } ?: "—"),
        ),
        "系统与内核" to listOf(
            "Android 版本" to "Android ${Build.VERSION.RELEASE}",
            "API 级别" to "${Build.VERSION.SDK_INT}",
            "安全补丁" to (Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() } ?: "—"),
            "内核版本" to kernel,
            "构建号" to (Build.DISPLAY.takeIf { it.isNotBlank() } ?: "—"),
            "构建时间" to buildDate,
            "系统语言" to Locale.getDefault().displayLanguage,
            "指令集(ABI)" to abis,
        ),
        "芯片与存储" to listOf(
            "SoC 型号" to socModel,
            "运行内存(RAM)" to ramGB,
            "可用存储" to freeStorage,
            "存储总容量" to totalStorage,
        ),
        "屏幕" to listOf(
            "分辨率" to resolution,
            "刷新率" to if (refreshRate > 0) "%.0f Hz".format(refreshRate) else "读取失败",
        ),
        "电池" to listOf(
            "电量与状态" to battery,
        ),
        "网络" to listOf(
            "当前连接" to network,
        ),
        "应用" to listOf(
            "应用版本" to BuildConfig.VERSION_NAME,
            "构建类型" to BuildConfig.BUILD_TYPE,
        ),
    )
}

/** 读 /proc/version 拿内核版本（读不到回退到指纹里的内核串）。 */
private fun readKernelVersion(): String {
    return runCatching {
        val f = File("/proc/version")
        if (f.exists()) {
            BufferedReader(FileReader(f)).use { it.readLine()?.trim() ?: "—" }
        } else {
            "—"
        }
    }.getOrDefault("—")
}

/** 移动网络类型名（粗略映射）。 */
private fun networkTypeName(type: Int): String = when (type) {
    1 -> "2G(GPRS)"
    2 -> "2G(EDGE)"
    3 -> "3G(UMTS)"
    8 -> "3G(HSDPA)"
    9 -> "HSUPA"
    10 -> "HSPA"
    13 -> "4G(LTE)"
    14 -> "eHRPD"
    15 -> "HSPA+"
    20 -> "4G(LTE_CA)"
    else -> if (type > 0) "NR(5G?)" else "未知"
}

// ---------------------------------------------------------------------------
// 订阅
// ---------------------------------------------------------------------------

@Composable
fun SubscriptionScreen(
    onBack: () -> Unit,
) {
    val colors = LocalTokenColors.current
    EnterStagger(index = 0) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    PaddingValues(
                        start = DesignTokens.Spacing.S16.dp,
                        end = DesignTokens.Spacing.S16.dp,
                        top = 24.dp,
                        bottom = 96.dp,
                    ),
                ),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S8.dp),
        ) {
            AccountTopBar(title = "订阅", onBack = onBack)

            GlassSectionHeader(text = "当前套餐")
            GlassPanel {
                Column(
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.S8.dp),
                    modifier = Modifier.padding(DesignTokens.Spacing.S4.dp),
                ) {
                    Text(
                        text = "免费版",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "包含全部基础监测能力：总览、监测、账号与设备上报。",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )
                }
            }

            GlassSectionHeader(text = "已包含")
            GlassPanel {
                Text(
                    text = "• 实时性能总览与深度指标\n" +
                        "• 设备性能上报与历史快照\n" +
                        "• 账号安全中心（改密 / 登录设备管理）\n" +
                        "• 自定义壁纸与玻璃主题",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(
                        horizontal = DesignTokens.Spacing.S12.dp,
                        vertical = DesignTokens.Spacing.S8.dp,
                    ),
                )
            }

            GlassButton(
                text = "升级到专业版（即将开放）",
                onClick = { /* 占位：后续版本开放订阅 */ },
                variant = GlassButtonVariant.SECONDARY,
                enabled = false,
            )
        }
    }
}
