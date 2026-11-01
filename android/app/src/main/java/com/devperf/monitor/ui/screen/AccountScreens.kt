package com.devperf.monitor.ui.screen

import android.os.Build
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

            GlassSectionHeader(text = "当前设备")
            GlassPanel {
                GlassInfoRow(label = "型号", value = "${Build.MANUFACTURER} ${Build.MODEL}")
                GlassInfoRow(label = "系统", value = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                GlassInfoRow(label = "应用版本", value = BuildConfig.VERSION_NAME)
            }

            GlassSectionHeader(text = "数据上报")
            GlassPanel {
                Text(
                    text = "本机性能数据由后台每小时自动同步一次；" +
                        "总览 / 监测页实时采集，管理端可下发管控与公告。",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(
                        horizontal = DesignTokens.Spacing.S12.dp,
                        vertical = DesignTokens.Spacing.S8.dp,
                    ),
                )
            }

            GlassSectionHeader(text = "账号")
            GlassPanel {
                val account by app.accountManager.session.collectAsState()
                GlassInfoRow(label = "已登录账号", value = account?.account ?: "未登录")
            }
        }
    }
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
