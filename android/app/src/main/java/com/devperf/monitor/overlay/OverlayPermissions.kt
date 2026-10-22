package com.devperf.monitor.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 悬浮窗权限与降级。
 *
 * 权限模型（Android 8.0+ 统一入口）：
 * - SYSTEM_ALERT_WINDOW 是特殊权限，必须在系统设置页「显示在其他应用上层」手动授权；
 * - [canDrawOverlays] 为唯一权威判断（部分厂商 ROM 有额外限制，如 MIUI 需要
 *   单独弹窗确认，此时系统会返回 false，按「未授权」降级处理）；
 * - 未授权时悬浮窗不显示，UI 引导跳转 [overlaySettingsIntent]；
 * - Android 6.0 以下该权限在安装时自动授予（[canDrawOverlays] 恒 true）。
 */
object OverlayPermissions {

    private const val TAG = "OverlayPermissions"

    /** 是否已获得悬浮窗权限。 */
    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /**
     * 跳转悬浮窗权限设置页。
     *
     * 优先跳本应用详情页（Android 8.0+ 支持 ACTION_MANAGE_OVERLAY_PERMISSION 带包名）；
     * 部分机型该 Intent 无响应时降级为应用详情页，再降级为系统设置主页。
     * 返回 false 表示所有跳转方式均不可用（调用方应给出文字指引）。
     */
    fun requestOverlayPermission(context: Context): Boolean {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                )
            }
            add(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            add(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        for (intent in intents) {
            val resolvable = runCatching {
                context.packageManager.resolveActivity(intent, 0) != null ||
                    context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
            }.getOrDefault(false)
            if (resolvable) {
                runCatching { context.startActivity(intent) }
                    .onSuccess { return true }
                    .onFailure { Log.w(TAG, "权限设置页跳转失败，尝试下一种", it) }
            }
        }
        return false
    }

    /** 通知权限（Android 13+）：未授权时告警通知静默降级。 */
    fun canPostNotifications(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
}
