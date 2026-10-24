package com.devperf.monitor.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.devperf.monitor.BuildConfig
import java.io.File

/**
 * 应用内「强制更新」对话框。仅在新版本可用 / 下载中 / 下载完成 / 检查失败时显示。
 *
 * 设计要点（强制更新）：[Available] / [Downloading] / [Ready] 三个态全部
 * `onDismissRequest = {}`——既不可点背景关闭、也不可按返回键关闭，旧版本在更新
 * 前任何功能都用不了；只有 [Error]（连不上服务器、无法确认有新版本）允许关闭，
 * 避免把离线用户锁死。
 */
@Composable
fun UpdateDialog(viewModel: MonitorViewModel) {
    val state by viewModel.updateState.collectAsState()
    val context = LocalContext.current

    when (state) {
        is UpdateUiState.Available -> {
            val release = (state as UpdateUiState.Available).release
            AlertDialog(
                onDismissRequest = { /* 强制更新：不可关闭 */ },
                title = { Text("必须更新才能继续使用") },
                text = {
                    Text(
                        text = "发现新版本 v${release.version}，当前版本已停用，请更新后再使用。\n\n" +
                            (release.notes.ifBlank { "暂无更新说明。" }),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.startUpdateDownload() }) {
                        Text("立即更新")
                    }
                },
            )
        }

        is UpdateUiState.Downloading -> {
            val progress = (state as UpdateUiState.Downloading).progress
            AlertDialog(
                onDismissRequest = { /* 下载中：不可关闭 */ },
                title = { Text("正在下载更新…") },
                text = {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = { },
            )
        }

        is UpdateUiState.Ready -> {
            val file = (state as UpdateUiState.Ready).file
            AlertDialog(
                onDismissRequest = { /* 安装前：不可关闭 */ },
                title = { Text("下载完成") },
                text = { Text("点击「安装」完成更新（需先允许「安装未知应用」）。未完成更新前，旧版本仍无法使用。") },
                confirmButton = {
                    TextButton(onClick = { installApk(context, file) }) {
                        Text("安装")
                    }
                },
            )
        }

        is UpdateUiState.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissForOffline() },
                title = { Text("检查更新失败") },
                text = { Text("暂时无法连接更新服务器，无法确认是否为最新版。可「重试」，或点「关闭」先继续使用（联网成功后再打开会重新检查）。") },
                confirmButton = {
                    TextButton(onClick = { viewModel.checkForUpdate() }) { Text("重试") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissForOffline() }) { Text("关闭") }
                },
            )
        }

        else -> { /* Idle / Checking / NoUpdate：不弹窗 */ }
    }
}

/** 用 FileProvider 暴露 APK 并拉起系统安装器（Android 7+ 禁止 file:// 直装）。 */
private fun installApk(context: android.content.Context, file: File) {
    val uri: Uri = FileProvider.getUriForFile(
        context,
        "${BuildConfig.APPLICATION_ID}.fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
