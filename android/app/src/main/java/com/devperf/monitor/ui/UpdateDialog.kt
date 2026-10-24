package com.devperf.monitor.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Row
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
 * 应用内更新提示对话框。仅在新版本可用 / 下载中 / 下载完成 / 出错时显示，
 * 其余状态（Idle / Checking / NoUpdate / Dismissed）不弹窗。
 */
@Composable
fun UpdateDialog(viewModel: MonitorViewModel) {
    val state by viewModel.updateState.collectAsState()
    val context = LocalContext.current

    when (state) {
        is UpdateUiState.Available -> {
            val release = (state as UpdateUiState.Available).release
            AlertDialog(
                onDismissRequest = { viewModel.dismissUpdateLater() },
                title = { Text("发现新版本 v${release.version}") },
                text = {
                    Text(
                        text = release.notes.ifBlank { "暂无更新说明。" },
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.startUpdateDownload() }) {
                        Text("立即更新")
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { viewModel.ignoreUpdate() }) {
                            Text("忽略此版本")
                        }
                        TextButton(onClick = { viewModel.dismissUpdateLater() }) {
                            Text("稍后")
                        }
                    }
                },
            )
        }

        is UpdateUiState.Downloading -> {
            val progress = (state as UpdateUiState.Downloading).progress
            AlertDialog(
                onDismissRequest = { },
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
                onDismissRequest = { },
                title = { Text("下载完成") },
                text = { Text("点击「安装」完成更新（需允许「安装未知应用」）。") },
                confirmButton = {
                    TextButton(onClick = { installApk(context, file) }) {
                        Text("安装")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissUpdateLater() }) {
                        Text("稍后")
                    }
                },
            )
        }

        is UpdateUiState.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissUpdateLater() },
                title = { Text("检查更新失败") },
                text = { Text("暂时无法连接更新服务器。可稍后重试，或前往 GitHub Releases 手动下载。") },
                confirmButton = {
                    TextButton(onClick = { viewModel.checkForUpdate() }) { Text("重试") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissUpdateLater() }) { Text("关闭") }
                },
            )
        }

        else -> { /* Idle / Checking / NoUpdate / Dismissed：不弹窗 */ }
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
