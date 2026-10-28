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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.molecule.manager.ManagerViewModel
import com.molecule.manager.ui.theme.BrandBackdropBrush
import com.molecule.manager.ui.theme.BrandBlue
import com.molecule.manager.ui.theme.GlassButton
import com.molecule.manager.ui.theme.GlassCard
import com.molecule.manager.ui.theme.GlassTextField
import com.molecule.manager.ui.theme.LocalBackdrop
import com.molecule.manager.ui.theme.rememberAppBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop

@Composable
fun MainScreen(vm: ManagerViewModel) {
    val backdrop = rememberAppBackdrop()
    Box(modifier = Modifier.fillMaxSize()) {
        // 背景层：只录品牌壁纸，玻璃折射的就是它（绝不能让内容进录制子树）
        Box(
            modifier = Modifier
                .matchParentSize()
                .layerBackdrop(backdrop)
                .background(BrandBackdropBrush),
        )
        // 内容与玻璃元素都是背景层的兄弟
        CompositionLocalProvider(LocalBackdrop provides backdrop) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                AnnouncementPanel(vm)
                SignReportsPanel(vm)
                DataUploadsPanel(vm)
            }
        }
    }
}

@Composable
private fun AnnouncementPanel(vm: ManagerViewModel) {
    val title by vm.annoTitle.collectAsState()
    val content by vm.annoContent.collectAsState()
    val status by vm.annoStatus.collectAsState()
    val loading by vm.annoLoading.collectAsState()

    LaunchedEffect(Unit) { vm.loadAnnouncement() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "分子智能科技 · 管理端",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = BrandBlue,
        )
        Text(
            text = "公告管理（反馈由用户端直接进邮箱，此处不再处理收件箱）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassTextField(
                    value = title,
                    onValueChange = vm::onTitleChange,
                    label = "公告标题",
                )
                GlassTextField(
                    value = content,
                    onValueChange = vm::onContentChange,
                    label = "公告内容（支持换行，· 开头为要点）",
                    minLines = 8,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton(
                onClick = { vm.loadAnnouncement() },
                tint = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.weight(1f),
            ) {
                Text("重新加载线上", color = MaterialTheme.colorScheme.onSurface)
            }
            GlassButton(
                onClick = { vm.publishAnnouncement() },
                tint = BrandBlue,
                modifier = Modifier.weight(1f),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterVertically),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("发布公告", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (status.isNotBlank()) {
            GlassCard(tint = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun SignReportsPanel(vm: ManagerViewModel) {
    val reports by vm.signReports.collectAsState()
    val status by vm.signReportsStatus.collectAsState()
    val loading by vm.signReportsLoading.collectAsState()

    LaunchedEffect(Unit) { vm.loadSignReports() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Brand 签名记录",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = BrandBlue,
            )
            GlassButton(
                onClick = { vm.loadSignReports() },
                tint = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("刷新", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        if (status.isNotBlank()) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        reports.forEach { report ->
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = report.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = report.createdAt,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // body 是 ```json 包裹的回执，直接展示原文
                    val detail = report.body
                        .removePrefix("```json").removePrefix("```")
                        .removeSuffix("```").trim()
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DataUploadsPanel(vm: ManagerViewModel) {
    val uploads by vm.dataUploads.collectAsState()
    val status by vm.dataUploadsStatus.collectAsState()
    val loading by vm.dataUploadsLoading.collectAsState()

    LaunchedEffect(Unit) { vm.loadDataUploads() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "数据上传（来源应用不固定 · 通道固定）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = BrandBlue,
            )
            GlassButton(
                onClick = { vm.loadDataUploads() },
                tint = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("刷新", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        if (status.isNotBlank()) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        uploads.forEach { upload ->
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = upload.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "应用：${upload.app}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "压缩包 · 数据 ${upload.rawChars} 字符" +
                            (if (upload.isZip) "（ZIP）" else "（JSON）"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = upload.exportedAt,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
