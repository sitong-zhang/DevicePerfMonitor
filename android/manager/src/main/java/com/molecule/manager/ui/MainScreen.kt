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
            AnnouncementPanel(vm)
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
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
