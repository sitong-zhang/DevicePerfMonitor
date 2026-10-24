package com.devperf.monitor.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
// 只引 material-icons-core：这个 artifact 里可用的图标有限（没有 Download /
// SystemUpdate），所以「更新」用 Refresh、「完成」用 CheckCircle、「失败」用 Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.devperf.monitor.BuildConfig
import java.io.File

/**
 * 应用内「强制更新」对话框（1.0.20 重做 UI）。
 *
 * UI 要点：
 * - 不再是「一坨原始 Markdown + 一堆符号」：图标 + 版本对照 + 分隔线 + 可滚动的
 *   更新说明（[ReleaseNotes] 做标题 / 引用 / 列表 / 加粗的正常排版）+ 实心按钮；
 * - 更新说明区最高 280dp，超出滚动，避免长文案把按钮顶出屏幕。
 *
 * 强制更新要点：[Available] / [Downloading] / [Ready] 三个态全部
 * `onDismissRequest = {}`——点背景、按返回键都关不掉，旧版本在更新前任何功能都
 * 用不了；只有 [Error]（连不上服务器、无法确认有新版本）允许关闭，避免把离线用户锁死。
 */
@Composable
fun UpdateDialog(viewModel: MonitorViewModel) {
    val state by viewModel.updateState.collectAsState()
    val context = LocalContext.current

    when (state) {
        is UpdateUiState.Available -> {
            val release = (state as UpdateUiState.Available).release
            // 提前申请「安装未知应用」权限（1.0.19）：授权页回来自动开始下载，
            // 下载完成时权限已就位，点「安装」一步走完。
            var pendingDownload by remember { mutableStateOf(false) }
            ObserveResume {
                if (pendingDownload) {
                    pendingDownload = false
                    if (canInstallPackages(context)) viewModel.startUpdateDownload()
                }
            }
            UpdateDialogShell(
                icon = Icons.Filled.Refresh,
                title = "必须更新才能继续使用",
                subtitle = "当前版本已停用，更新后才能继续使用",
                dismissible = false,
                confirmText = "立即更新",
                onConfirm = {
                    if (canInstallPackages(context)) {
                        viewModel.startUpdateDownload()
                    } else {
                        pendingDownload = true
                        openInstallPermissionSettings(context)
                    }
                },
            ) {
                VersionCompare(current = BuildConfig.VERSION_NAME, target = release.version)
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Text(
                    text = "更新内容",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ReleaseNotes(
                    text = release.notes,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }

        is UpdateUiState.Downloading -> {
            val progress = (state as UpdateUiState.Downloading).progress
            UpdateDialogShell(
                icon = Icons.Filled.Refresh,
                title = "正在下载更新",
                subtitle = "下载完成后会提示安装，请勿关闭网络",
                dismissible = false,
                confirmText = "",
                onConfirm = {},
            ) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "$progress%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        is UpdateUiState.Ready -> {
            val file = (state as UpdateUiState.Ready).file
            // 授权回来自动续装（1.0.18）：修「授权回来又回到下载界面」的断链
            var pendingInstall by remember { mutableStateOf(false) }
            ObserveResume {
                if (pendingInstall) {
                    pendingInstall = false
                    if (canInstallPackages(context)) installApk(context, file)
                }
            }
            UpdateDialogShell(
                icon = Icons.Filled.CheckCircle,
                title = "下载完成",
                subtitle = "点击下方「安装」，按系统提示完成更新",
                dismissible = false,
                confirmText = "安装",
                onConfirm = {
                    if (canInstallPackages(context)) {
                        installApk(context, file)
                    } else {
                        pendingInstall = true
                        openInstallPermissionSettings(context)
                    }
                },
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }

        is UpdateUiState.Error -> {
            UpdateDialogShell(
                icon = Icons.Filled.Warning,
                title = "检查更新失败",
                subtitle = "暂时无法连接更新服务器，无法确认是否为最新版",
                dismissible = true,
                onDismiss = { viewModel.dismissForOffline() },
                confirmText = "重试",
                onConfirm = { viewModel.checkForUpdate() },
                dismissText = "关闭",
            ) {
                Text(
                    text = "可以「重试」；也可以先「关闭」继续使用旧版本——联网成功后"
                        + "再打开 App 会重新检查。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        else -> { /* Idle / Checking / NoUpdate：不弹窗 */ }
    }
}

/**
 * 对话框外壳：图标 + 标题 + 副标题 + 内容槽 + 按钮行。
 * [dismissible] 为 false 时背景点击与返回键都无效（强制更新）。
 */
@Composable
private fun UpdateDialogShell(
    icon: ImageVector,
    title: String,
    subtitle: String,
    dismissible: Boolean,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String? = null,
    onDismiss: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (dismissible) onDismiss?.invoke() },
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        },
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        text = { Column { content() } },
        confirmButton = {
            if (confirmText.isNotBlank()) {
                Button(onClick = onConfirm) { Text(confirmText) }
            }
        },
        dismissButton = {
            if (dismissText != null && onDismiss != null) {
                OutlinedButton(onClick = onDismiss) { Text(dismissText) }
            }
        },
    )
}

/** 版本对照：当前 → 目标，两个药丸标签。 */
@Composable
private fun VersionCompare(current: String, target: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VersionChip(text = "当前 v$current", highlight = false)
        Text(
            text = "→",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VersionChip(text = "新版 v$target", highlight = true)
    }
}

@Composable
private fun VersionChip(text: String, highlight: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (highlight) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (highlight) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** 监听回到前台（从系统授权页/安装器返回），用于自动续上被打断的流程。 */
@Composable
private fun ObserveResume(onResume: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/** 本应用是否已获「安装未知应用」授权（API 26+ 才有该机制；更早版本直接装）。 */
private fun canInstallPackages(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        context.packageManager.canRequestPackageInstalls()

/** 跳到本应用的「安装未知应用」系统授权页（返回后由 ON_RESUME 自动续上）。 */
private fun openInstallPermissionSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${BuildConfig.APPLICATION_ID}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    // 个别 ROM 不认该 action 时静默失败：待办状态已记下，用户回来再点一次即可。
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
