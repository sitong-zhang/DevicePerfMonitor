package com.devperf.monitor.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
            // 「安装未知应用」授权的续装逻辑（1.0.18）：第一次点「安装」若还没授权，
            // 跳系统授权页并记住 pendingInstall；从授权页回来（ON_RESUME）时若已授权
            // 就自动拉起安装器，用户不用再点一次。修复 1.0.17「授权回来又回到下载
            // 界面」的体验断链（配合 checkForUpdate 的 Ready 守卫）。
            var pendingInstall by remember { mutableStateOf(false) }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME && pendingInstall) {
                        pendingInstall = false
                        if (canInstallPackages(context)) {
                            installApk(context, file)
                        }
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            AlertDialog(
                onDismissRequest = { /* 安装前：不可关闭 */ },
                title = { Text("下载完成") },
                text = { Text("点击「安装」完成更新（需先允许「安装未知应用」）。未完成更新前，旧版本仍无法使用。") },
                confirmButton = {
                    TextButton(onClick = {
                        if (canInstallPackages(context)) {
                            installApk(context, file)
                        } else {
                            // 还没授权：跳到本应用的「安装未知应用」系统授权页，
                            // 回来自动续装（见上面的 ON_RESUME 观察者）
                            pendingInstall = true
                            openInstallPermissionSettings(context)
                        }
                    }) {
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

/** 本应用是否已获「安装未知应用」授权（API 26+ 才有该机制；更早版本直接装）。 */
private fun canInstallPackages(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        context.packageManager.canRequestPackageInstalls()

/** 跳到本应用的「安装未知应用」系统授权页（返回后由 ON_RESUME 自动续装）。 */
private fun openInstallPermissionSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${BuildConfig.APPLICATION_ID}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    // 个别 ROM 不认该 action 时静默失败：pendingInstall 已记下，用户手动回
    // 到 App 再点一次「安装」即可（那时多半已授权）。
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
