package com.molecule.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.molecule.manager.ManagerViewModel
import com.molecule.manager.network.AdminApiClient
import com.molecule.manager.ui.theme.BrandBlue
import com.molecule.manager.ui.theme.BrandBackdropBrush
import com.molecule.manager.ui.theme.GlassButton
import com.molecule.manager.ui.theme.GlassCard
import com.molecule.manager.ui.theme.GlassTextField
import com.molecule.manager.ui.theme.LocalBackdrop
import com.molecule.manager.ui.theme.StatusDisabled
import com.molecule.manager.ui.theme.StatusNormal
import com.molecule.manager.ui.theme.StatusRestricted
import com.molecule.manager.ui.theme.brandAccent
import com.molecule.manager.ui.theme.rememberAppBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun MainScreen(vm: ManagerViewModel) {
    val backdrop = rememberAppBackdrop()
    Box(modifier = Modifier.fillMaxSize()) {
        // 背景层：只录品牌壁纸，玻璃折射的就是它（内容不能进录制子树）
        Box(
            modifier = Modifier
                .matchParentSize()
                .layerBackdrop(backdrop)
                .background(BrandBackdropBrush),
        )
        CompositionLocalProvider(LocalBackdrop provides backdrop) {
            val unlocked by vm.unlocked.collectAsState()
            if (!unlocked) {
                PasswordLockScreen(vm)
            } else {
                ManagerContent(vm)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 密码锁：管理密码构建期注入，输入一次本地永久记住（SharedPrefs，除非卸载）。
// ---------------------------------------------------------------------------

@Composable
private fun PasswordLockScreen(vm: ManagerViewModel) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        GlassCard(modifier = Modifier.fillMaxWidth(0.9f)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "管理端 · 请输入密码",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = brandAccent(),
                )
                Text(
                    text = "本应用仅限管理人员使用",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GlassTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = "管理密码",
                    isPassword = true,
                    keyboardType = KeyboardType.Password,
                )
                if (error.isNotBlank()) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                GlassButton(
                    onClick = {
                        val err = vm.tryUnlock(input)
                        error = err ?: ""
                    },
                    tint = BrandBlue,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("解锁", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 管理端主内容：全局公告 + 用户列表 + 选中用户管控
// ---------------------------------------------------------------------------

@Composable
private fun ManagerContent(vm: ManagerViewModel) {
    val users by vm.users.collectAsState()
    val usersLoading by vm.usersLoading.collectAsState()
    val selected by vm.selectedUser.collectAsState()
    val detailLoading by vm.detailLoading.collectAsState()
    val status by vm.status.collectAsState()
    val token by vm.adminToken.collectAsState()

    // 进入即拉用户列表；token 就绪后自动拉一次
    LaunchedEffect(token) { if (token != null) vm.loadUsers() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Text(
                text = "分子智能科技 · 管理端",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = brandAccent(),
            )
            Text(
                text = "账号管控与公告发布（数据实时来自后端）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        GlobalAnnouncementPanel(vm)

        // 用户列表
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "注册用户（${users.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = brandAccent(),
                )
                GlassButton(onClick = { vm.loadUsers() }, tint = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(if (usersLoading) "刷新中…" else "刷新", color = MaterialTheme.colorScheme.onSurface)
                }
            }
            if (users.isEmpty() && !usersLoading) {
                Text(
                    text = if (token == null) "未连接后端，正在重试…" else "暂无注册用户",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                users.forEach { user ->
                    UserRow(vm, user)
                }
            }
        }

        // 选中用户详情 + 管控。
        // 注意：刷新详情（detailLoading）时不替换面板，否则列表高度塌缩、
        // 滚动位置会被顶回最上面（用户实测反馈的 bug）。
        if (selected != null) {
            UserDetailPanel(vm, selected!!)
        }

        if (status.isNotBlank()) {
            GlassCard(tint = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(text = status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun UserRow(vm: ManagerViewModel, user: AdminApiClient.AdminUser) {
    GlassCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                Text(
                    text = user.nickname.ifBlank { user.account },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "@${user.account} · ${user.email}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "状态：${statusLabel(user.status)} · 操作 ${user.opCount} 次 · 同步 ${AdminApiClient.formatTime(user.lastSyncAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor(user.status),
                )
            }
            GlassButton(onClick = { vm.openUser(user.id) }, tint = BrandBlue) {
                Text("管控", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// 各功能禁用后对用户端的实际影响说明（与 SyncManager 拦截点一一对应）
private val FEATURE_DESC = mapOf(
    "overlay" to "退到后台时的实时性能悬浮窗",
    "sync" to "向官方服务器的设备数据上传",
    "alert" to "阈值触发的告警通知",
    "announcement" to "官方公告弹窗与横幅",
    "deep_monitor" to "「监测」页的深度指标采集",
    "background_collect" to "应用退后台后的持续采集",
    "feedback" to "设置页的意见反馈入口",
    "auto_update" to "启动时的新版本检查",
)

@Composable
private fun UserDetailPanel(vm: ManagerViewModel, detail: AdminApiClient.AdminUserDetail) {
    val user = detail.user
    var userAnnoTitle by remember { mutableStateOf("") }
    var userAnnoContent by remember { mutableStateOf("") }
    var note by remember(user.id) { mutableStateOf(detail.restrictionNote ?: "") }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = "用户管控 · ${user.nickname.ifBlank { user.account }}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = brandAccent(),
        )

        // ---- 账号资料 ----
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoLine("账号", user.account)
                InfoLine("昵称", user.nickname.ifBlank { "—" })
                InfoLine("邮箱", user.email)
                InfoLine("手机号", user.phone.ifBlank { "—" })
                InfoLine("注册时间", AdminApiClient.formatTime(user.createdAt))
                InfoLine("最近同步", AdminApiClient.formatTime(user.lastSyncAt))
                InfoLine("状态", statusLabel(user.status), valueColor = statusColor(user.status))
                detail.restrictionNote?.takeIf { it.isNotBlank() }?.let {
                    InfoLine("限制说明", it)
                }
            }
        }

        // ---- 账号状态 ----
        SectionTitle("账号状态")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusChip(vm, user.id, user.status, note, "normal", "正常", StatusNormal)
            StatusChip(vm, user.id, user.status, note, "restricted", "受限", StatusRestricted)
            StatusChip(vm, user.id, user.status, note, "disabled", "禁用", StatusDisabled)
        }
        GlassTextField(value = note, onValueChange = { note = it }, label = "限制说明（选填）")
        GlassButton(
            onClick = { vm.setStatus(user.id, user.status, note) },
            tint = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存限制说明", color = MaterialTheme.colorScheme.onSurface)
        }

        // ---- 功能开关（全部用户端功能，真实生效）----
        SectionTitle("功能开关")
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ManagerViewModel.FEATURES.forEach { (feature, label) ->
                    val disabled = detail.disabledFeatures.contains(feature)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = FEATURE_DESC[feature] ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        GlassButton(
                            onClick = { vm.toggleFeature(user.id, feature, !disabled) },
                            tint = if (disabled) StatusDisabled else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = if (disabled) "已禁用 · 恢复" else "使用中 · 禁用",
                                color = if (disabled) Color.White else MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
        Text(
            text = "开关立即生效：用户端在对应功能入口真实拦截（悬浮窗停服务、同步断流、公告不弹等）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        // ---- 单独公告 ----
        SectionTitle("单独公告")
        GlassTextField(value = userAnnoTitle, onValueChange = { userAnnoTitle = it }, label = "公告标题")
        GlassTextField(value = userAnnoContent, onValueChange = { userAnnoContent = it }, label = "公告内容", minLines = 4)
        GlassButton(
            onClick = { vm.announceUser(user.id, userAnnoTitle, userAnnoContent) },
            tint = BrandBlue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("发送给该用户", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
        }

        // ---- 操作记录（人话渲染）----
        if (detail.operations.isNotEmpty()) {
            SectionTitle("操作记录")
            GlassCard(tint = MaterialTheme.colorScheme.surfaceVariant) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    detail.operations.take(30).forEach { op ->
                        Column {
                            Text(
                                text = renderOp(op),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = AdminApiClient.formatTime(op.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        GlassButton(
            onClick = { vm.closeUser() },
            tint = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("收起", color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
    )
}

@Composable
private fun InfoLine(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = valueColor,
            modifier = Modifier.widthIn(max = 220.dp),
        )
    }
}

@Composable
private fun StatusChip(
    vm: ManagerViewModel,
    uid: Int,
    currentStatus: String,
    note: String,
    status: String,
    label: String,
    color: Color,
) {
    val selected = currentStatus == status
    GlassButton(
        onClick = { vm.setStatus(uid, status, note) },
        tint = if (selected) color else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            label,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// ---------------------------------------------------------------------------
// 操作记录渲染：把机器格式 / 用户端上报翻译成管理人员一眼能懂的人话。
// 新版后端已直接写中文；这里兜底翻译旧格式与用户端上报格式。
// ---------------------------------------------------------------------------

private fun renderOp(op: AdminApiClient.UserOperation): String {
    val d = op.detail.trim()
    return when {
        // 用户端设置上报："overlay → 开"、"theme → DARK"
        d.contains(" → ") -> {
            val key = d.substringBefore("→").trim()
            val v = d.substringAfter("→").trim()
            "用户把「${settingLabel(key)}」设为 ${settingValue(key, v)}"
        }
        // 旧版状态格式："status=restricted; note=xxx"
        d.startsWith("status=") -> {
            val st = d.substringAfter("status=").substringBefore(";").trim()
            val note = d.substringAfter("note=", "").trim()
                .takeIf { it.isNotBlank() }?.let { "，说明：$it" } ?: ""
            "账号状态改为「${statusLabel(st)}」$note"
        }
        // 旧版功能开关格式："overlay=禁用"
        Regex("^\\w+=(启用|禁用)$").matches(d) -> {
            val key = d.substringBefore("=")
            val act = d.substringAfter("=")
            "「${featureLabel(key)}」被$act"
        }
        else -> d // 新版后端已是人话（或用户端自定义事件），原样展示
    }
}

private fun featureLabel(key: String): String =
    ManagerViewModel.FEATURES.firstOrNull { it.first == key }?.second ?: key

private fun settingLabel(key: String): String = when (key) {
    "overlay" -> "悬浮窗"
    "theme" -> "主题"
    "wallpaper" -> "壁纸"
    "background" -> "后台采集"
    "alerts" -> "告警提醒"
    "refresh" -> "刷新频率"
    else -> key
}

private fun settingValue(key: String, v: String): String = when (key) {
    "overlay", "background", "alerts" -> v
    "theme" -> when (v.uppercase()) {
        "LIGHT" -> "浅色"; "DARK" -> "深色"; "SYSTEM" -> "跟随系统"; else -> v
    }
    "refresh" -> when (v.uppercase()) {
        "REALTIME" -> "实时"; "BACKGROUND" -> "省电"; else -> v
    }
    else -> v
}

@Composable
private fun statusColor(status: String): Color = when (status) {
    "normal" -> StatusNormal
    "restricted" -> StatusRestricted
    "disabled" -> StatusDisabled
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun statusLabel(status: String): String = when (status) {
    "normal" -> "正常"
    "restricted" -> "受限"
    "disabled" -> "禁用"
    else -> status
}

// ---------------------------------------------------------------------------
// 全局公告面板（发布走后端 /api/admin/announce）
// ---------------------------------------------------------------------------

@Composable
private fun GlobalAnnouncementPanel(vm: ManagerViewModel) {
    val title by vm.annoTitle.collectAsState()
    val content by vm.annoContent.collectAsState()
    val status by vm.annoStatus.collectAsState()
    val loading by vm.annoLoading.collectAsState()

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = "全局公告",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = brandAccent(),
        )
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassTextField(value = title, onValueChange = vm::onTitleChange, label = "公告标题")
                GlassTextField(value = content, onValueChange = vm::onContentChange, label = "公告内容（支持换行）", minLines = 6)
            }
        }
        GlassButton(
            onClick = { vm.publishAnnouncement() },
            tint = BrandBlue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterVertically),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("发布全局公告", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
            }
        }
        if (status.isNotBlank()) {
            GlassCard(tint = MaterialTheme.colorScheme.surfaceVariant) {
                Text(text = status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
