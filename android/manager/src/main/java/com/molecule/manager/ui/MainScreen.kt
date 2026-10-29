package com.molecule.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.molecule.manager.ManagerViewModel
import com.molecule.manager.network.AdminApiClient
import com.molecule.manager.ui.theme.BrandBackdropBrush
import com.molecule.manager.ui.theme.BrandBlue
import com.molecule.manager.ui.theme.GlassButton
import com.molecule.manager.ui.theme.GlassCard
import com.molecule.manager.ui.theme.GlassTextField
import com.molecule.manager.ui.theme.LocalBackdrop
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
// 密码锁：管理密码 421083，输入一次本地永久记住（SharedPrefs，除非卸载）。
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
                    color = BrandBlue,
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
                color = BrandBlue,
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
            ) {
                Text(
                    text = "注册用户（${users.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BrandBlue,
                )
                GlassButton(onClick = { vm.loadUsers() }, tint = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(if (usersLoading) "刷新中…" else "刷新", color = MaterialTheme.colorScheme.onSurface)
                }
            }
            if (users.isEmpty() && !usersLoading) {
                Text(
                    text = if (token == null) "未连接后端" else "暂无注册用户",
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

        // 选中用户详情 + 管控
        if (selected != null) {
            if (detailLoading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            } else {
                UserDetailPanel(vm, selected!!)
            }
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
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GlassButton(onClick = { vm.openUser(user.id) }, tint = BrandBlue) {
                Text("管控", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun UserDetailPanel(vm: ManagerViewModel, detail: AdminApiClient.AdminUserDetail) {
    val user = detail.user
    var userAnnoTitle by remember { mutableStateOf("") }
    var userAnnoContent by remember { mutableStateOf("") }
    var note by remember { mutableStateOf(detail.restrictionNote ?: "") }

    // 可选管控的功能（与用户端 SyncManager.isFeatureDisabled 对应）
    val featureCandidates = listOf("overlay" to "悬浮窗", "sync" to "数据同步", "alert" to "告警提醒")

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = "用户管控 · ${user.nickname.ifBlank { user.account }}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = BrandBlue,
        )

        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoLine("账号", user.account)
                InfoLine("昵称", user.nickname.ifBlank { "—" })
                InfoLine("邮箱", user.email)
                InfoLine("手机号", user.phone.ifBlank { "—" })
                InfoLine("状态", statusLabel(user.status))
                InfoLine("注册时间", AdminApiClient.formatTime(user.createdAt))
                InfoLine("最近同步", AdminApiClient.formatTime(user.lastSyncAt))
                detail.restrictionNote?.takeIf { it.isNotBlank() }?.let {
                    InfoLine("限制说明", it)
                }
            }
        }

        // 账号状态
        Text("账号状态", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(vm, user.id, "normal", "正常")
            StatusChip(vm, user.id, "restricted", "受限")
            StatusChip(vm, user.id, "disabled", "禁用")
        }
        GlassTextField(value = note, onValueChange = { note = it }, label = "限制说明（选填）")
        GlassButton(
            onClick = { vm.setStatus(user.id, user.status, note) },
            tint = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存限制说明", color = MaterialTheme.colorScheme.onSurface)
        }

        // 功能开关
        Text("功能开关", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        featureCandidates.forEach { (feature, label) ->
            val disabled = detail.disabledFeatures.contains(feature)
            Row(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(label, color = MaterialTheme.colorScheme.onSurface)
                GlassButton(
                    onClick = { vm.toggleFeature(user.id, feature, !disabled) },
                    tint = if (disabled) MaterialTheme.colorScheme.error else BrandBlue,
                ) {
                    Text(if (disabled) "已禁用·恢复" else "禁用", color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }

        // 单独公告
        Text("单独公告", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GlassTextField(value = userAnnoTitle, onValueChange = { userAnnoTitle = it }, label = "公告标题")
        GlassTextField(value = userAnnoContent, onValueChange = { userAnnoContent = it }, label = "公告内容", minLines = 4)
        GlassButton(
            onClick = { vm.announceUser(user.id, userAnnoTitle, userAnnoContent) },
            tint = BrandBlue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("发送给该用户", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
        }

        // 操作记录
        if (detail.operations.isNotEmpty()) {
            Text("操作记录", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            GlassCard(tint = MaterialTheme.colorScheme.surfaceVariant) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    detail.operations.take(30).forEach { op ->
                        Text(
                            text = "· ${op.detail}（${AdminApiClient.formatTime(op.createdAt)}）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
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
private fun InfoLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun StatusChip(vm: ManagerViewModel, uid: Int, status: String, label: String) {
    GlassButton(
        onClick = { vm.setStatus(uid, status, "") },
        tint = if (status == "normal") BrandBlue else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onPrimary)
    }
}

// ---------------------------------------------------------------------------
// 全局公告面板（移除「重新加载线上」按钮；发布走后端 /api/admin/announce）
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
            color = BrandBlue,
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

private fun statusLabel(status: String): String = when (status) {
    "normal" -> "正常"
    "restricted" -> "受限"
    "disabled" -> "禁用"
    else -> status
}
