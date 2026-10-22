package com.devperf.monitor

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.devperf.monitor.data.CollectionService
import com.devperf.monitor.data.MetricsRepository
import com.devperf.monitor.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * 应用入口：持有全局单例（设置仓库 / 采集仓库）。
 *
 * 不在 Application.onCreate 中直接启动前台服务——从后台进程启动 FGS 可能被系统拒绝；
 * 启动时机放在 MainActivity（前台上下文）与悬浮窗开关处，失败时降级为普通后台采集。
 *
 * 崩溃监测：在业务初始化前安装 [CrashHandler]，拦截任何未捕获异常并弹窗展示技术栈，
 * 方便用户截图反馈。
 */
class MonitorApp : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var metricsRepository: MetricsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        // 崩溃展示进程（:crash）无需业务初始化，直接返回，避免重复初始化与递归崩溃
        if (isCrashProcess()) return

        // 须在业务初始化前安装，才能捕获后续所有未捕获异常（含 Compose 合成崩溃）
        CrashHandler.install(this)

        settingsRepository = SettingsRepository(this)
        metricsRepository = MetricsRepository(this, settingsRepository)

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching {
                val settings = settingsRepository.settings.first()
                if (settings.collectInBackground) {
                    CollectionService.start(this@MonitorApp)
                }
            }.onFailure { Log.w("MonitorApp", "自动启动采集失败（不影响使用）", it) }
        }
    }

    companion object {
        /** 当前进程是否为崩溃展示进程（独立进程，进程名以 ":crash" 结尾）。 */
        fun isCrashProcess(): Boolean = currentProcessName().endsWith(":crash")

        private fun currentProcessName(): String {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName() ?: ""
            } else {
                runCatching { File("/proc/self/cmdline").readText().trim('\u0000') }.getOrDefault("")
            }
        }
    }
}
