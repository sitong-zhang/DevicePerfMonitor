package com.devperf.monitor

import android.app.Application
import android.util.Log
import com.devperf.monitor.data.CollectionService
import com.devperf.monitor.data.MetricsRepository
import com.devperf.monitor.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 应用入口：持有全局单例（设置仓库 / 采集仓库）。
 *
 * 不在 Application.onCreate 中直接启动前台服务——从后台进程启动 FGS 可能被系统拒绝；
 * 启动时机放在 MainActivity（前台上下文）与悬浮窗开关处，失败时降级为普通后台采集。
 */
class MonitorApp : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var metricsRepository: MetricsRepository
        private set

    override fun onCreate() {
        super.onCreate()
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
}
