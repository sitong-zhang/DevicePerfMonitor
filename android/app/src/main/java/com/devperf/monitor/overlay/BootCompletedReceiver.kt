package com.devperf.monitor.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.devperf.monitor.data.CollectionService
import com.devperf.monitor.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开机 / 应用更新广播接收器。
 *
 * 行为：
 * - BOOT_COMPLETED / MY_PACKAGE_REPLACED 后读取用户设置：
 *   - overlayEnabled = true → 启动悬浮窗（开机广播属于 FGS 后台启动豁免场景）；
 *   - collectInBackground = true → 启动采集服务；
 * - 启动失败（系统限制）只记日志，不崩溃；
 * - 悬浮窗权限被用户撤销时，OverlayService 自身会降级停止（见其 showOverlay）。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val settings = SettingsRepository(appContext).settings.first()
                if (settings.overlayEnabled) {
                    Log.i(TAG, "恢复悬浮窗（用户设置）")
                    OverlayService.start(appContext)
                }
                if (settings.collectInBackground) {
                    Log.i(TAG, "恢复采集服务（用户设置）")
                    CollectionService.start(appContext)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "开机恢复失败（忽略）", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootCompletedReceiver"
    }
}
