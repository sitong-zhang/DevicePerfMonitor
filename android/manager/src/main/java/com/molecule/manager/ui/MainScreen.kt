package com.molecule.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.molecule.manager.ManagerViewModel
import com.molecule.manager.network.FeedbackComment
import com.molecule.manager.network.FeedbackIssue
import com.molecule.manager.ui.theme.BrandAmber
import com.molecule.manager.ui.theme.BrandBlue

@Composable
fun MainScreen(vm: ManagerViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "分子智能科技 · 管理端",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = BrandBlue,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("公告管理") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("反馈收件箱") })
        }
        if (tab == 0) AnnouncementPanel(vm) else FeedbackPanel(vm)
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = vm::onTitleChange,
            label = { Text("公告标题") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = content,
            onValueChange = vm::onContentChange,
            label = { Text("公告内容（支持换行，· 开头为要点）") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 8,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.loadAnnouncement() }, enabled = !loading) {
                Text("重新加载线上")
            }
            OutlinedButton(
                onClick = { vm.publishAnnouncement() },
                enabled = !loading && title.isNotBlank(),
            ) {
                Text("发布公告", color = BrandBlue)
            }
        }
        if (loading) CircularProgressIndicator()
        Text(text = status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FeedbackPanel(vm: ManagerViewModel) {
    val issues by vm.issues.collectAsState()
    val loading by vm.issuesLoading.collectAsState()
    val selected by vm.selectedIssue.collectAsState()

    LaunchedEffect(Unit) { vm.loadIssues() }

    Box(modifier = Modifier.fillMaxSize()) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (issues.isEmpty()) {
                    item { Text("暂无反馈（label=feedback 的 issue）", modifier = Modifier.padding(16.dp)) }
                }
                items(issues) { issue -> IssueCard(issue) { vm.selectIssue(issue) } }
            }
        }
    }

    if (selected != null) {
        IssueDetailDialog(issue = selected!!, vm = vm, onDismiss = { vm.selectIssue(null) })
    }
}

@Composable
private fun IssueCard(issue: FeedbackIssue, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = issue.title.ifBlank { "(无标题)" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("#${issue.number}", style = MaterialTheme.typography.labelMedium, color = BrandBlue)
                Text(issue.state, style = MaterialTheme.typography.labelMedium, color = BrandAmber)
                Text("@${issue.user}", style = MaterialTheme.typography.labelMedium)
                Text(issue.createdAt.take(10), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun IssueDetailDialog(issue: FeedbackIssue, vm: ManagerViewModel, onDismiss: () -> Unit) {
    val comments by vm.comments.collectAsState()
    val commentsLoading by vm.commentsLoading.collectAsState()
    val replyText by vm.replyText.collectAsState()
    val replyStatus by vm.replyStatus.collectAsState()
    val replyBusy by vm.replyBusy.collectAsState()

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(8.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部：编号 + 标题 + 状态 + 关闭
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "#${issue.number} ${issue.title.ifBlank { "(无标题)" }}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StateChip(issue.state)
                            Text(
                                "@${issue.user} · ${issue.createdAt.take(10)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) { Text("✕") }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )

                // 对话区：反馈正文 + 全部评论
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        MessageBubble(
                            who = issue.user,
                            whenText = issue.createdAt,
                            body = issue.body.ifBlank { "(无正文)" },
                            isOfficial = false,
                        )
                    }
                    if (commentsLoading) {
                        item { Text("加载对话中…", modifier = Modifier.padding(8.dp)) }
                    }
                    items(comments) { c ->
                        MessageBubble(
                            who = c.user,
                            whenText = c.createdAt,
                            body = c.body.ifBlank { "(无内容)" },
                            isOfficial = true,
                        )
                    }
                    if (!commentsLoading && comments.isEmpty()) {
                        item { Text("暂无回复，写下第一条回复吧。", modifier = Modifier.padding(8.dp)) }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )

                // 回复区
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = replyText,
                        onValueChange = vm::onReplyChange,
                        label = { Text("回复内容（会作为评论发布到该反馈下）") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        enabled = !replyBusy,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { vm.postReply(issue.number) },
                            enabled = !replyBusy && replyText.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) {
                            if (replyBusy) CircularProgressIndicator(
                                modifier = Modifier.height(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            ) else Text("发送回复")
                        }
                        OutlinedButton(
                            onClick = { vm.resolveIssue(issue.number, issue.state != "closed") },
                            enabled = !replyBusy,
                        ) {
                            Text(if (issue.state == "closed") "重新打开" else "标记已解决")
                        }
                    }
                    if (replyStatus.isNotBlank()) {
                        Text(
                            replyStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (replyStatus.startsWith("回复已发布") ||
                                replyStatus.startsWith("已标记") ||
                                replyStatus.startsWith("已重新打开")
                            ) {
                                BrandBlue
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StateChip(state: String) {
    val (bg, fg, label) = when (state) {
        "open" -> Triple(
            BrandBlue.copy(alpha = 0.15f),
            BrandBlue,
            "处理中",
        )
        else -> Triple(
            BrandAmber.copy(alpha = 0.18f),
            BrandAmber,
            "已解决",
        )
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

@Composable
private fun MessageBubble(
    who: String,
    whenText: String,
    body: String,
    isOfficial: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isOfficial) {
                    BrandBlue.copy(alpha = 0.08f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "@$who",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isOfficial) BrandBlue else MaterialTheme.colorScheme.onSurface,
            )
            if (isOfficial) {
                Text(
                    "官方回复",
                    style = MaterialTheme.typography.labelSmall,
                    color = BrandBlue,
                )
            }
            Text(
                whenText.take(19).replace("T", " "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}
