package com.devperf.monitor

import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Context
import android.os.Build
import android.util.Log
import com.devperf.monitor.account.AccountManager
import com.devperf.monitor.account.SyncManager
import com.devperf.monitor.data.CollectionService
import com.devperf.monitor.data.MetricsRepository
import com.devperf.monitor.data.SettingsRepository
import com.devperf.monitor.overlay.OverlayPermissions
import com.devperf.monitor.overlay.OverlayService
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
 * 崩溃监测：在 attachBaseContext 里就装上（比 ContentProvider 和 onCreate 都早），
 * 保证 ShizukuProvider 这类最早初始化的组件崩溃也能被记录。
 */
class MonitorApp : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var metricsRepository: MetricsRepository
        private set

    lateinit var accountManager: AccountManager
        private set

    lateinit var syncManager: SyncManager
        private set

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // 这里比 ContentProvider.onCreate 和 Application.onCreate 都早，
        // 是最早能装崩溃钩子的地方。
        runCatching {
            BootTrace.beginProcess(this)
            CrashHandler.install(this)
        }.onFailure {
            Log.w("MonitorApp", "崩溃监测器安装失败", it)
        }
        BootTrace.mark("进程启动 attachBaseContext")
    }

    override fun onCreate() {
        BootTrace.mark("Application.onCreate 开始")
        super.onCreate()
        // 崩溃展示进程（:crash）无需业务初始化，直接返回，避免重复初始化与递归崩溃
        if (isCrashProcess()) return

        runCatching {
            settingsRepository = SettingsRepository(this)
            BootTrace.mark("SettingsRepository 就绪")
            // 官方后端地址：启动即从仓库自动发现并缓存，用户无需填写
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                val prefs = getSharedPreferences("backend", MODE_PRIVATE)
                prefs.getString("official_url", null)?.let {
                    com.devperf.monitor.net.ApiClient.officialUrl = it
                }
                val url = com.devperf.monitor.net.ApiClient.resolveServerUrl()
                if (!url.isNullOrBlank()) prefs.edit().putString("official_url", url).apply()
            }
            metricsRepository = MetricsRepository(this, settingsRepository)
            BootTrace.mark("MetricsRepository 就绪")
            accountManager = AccountManager(this)
            syncManager = SyncManager(this, settingsRepository, accountManager)
            BootTrace.mark("AccountManager / SyncManager 就绪")
            // 管理端管控真实生效：监听下发的禁用清单，悬浮窗被禁立即停服务、
            // 后台采集被禁立即停采集。UI 侧各入口另有拦截（同步/公告/反馈/更新）。
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                syncManager.remoteState.collect { state ->
                    val disabled = state?.disabledFeatures.orEmpty()
                    if ("overlay" in disabled) {
                        runCatching { OverlayService.stop(this@MonitorApp) }
                    }
                    if ("background_collect" in disabled) {
                        runCatching { CollectionService.stop(this@MonitorApp) }
                    }
                }
            }
        }.onFailure {
            BootTrace.mark("业务初始化失败: ${it.javaClass.simpleName}: ${it.message}")
            throw it
        }

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching {
                val settings = settingsRepository.settings.first()
                BootTrace.mark("设置读取完成")
                // 管理端禁用「后台采集」时不启动（运行中被禁由 remoteState 监听停止）
                if (settings.collectInBackground && !syncManager.isFeatureDisabled("background_collect")) {
                    CollectionService.start(this@MonitorApp)
                    BootTrace.mark("后台采集已启动")
                }
            }.onFailure { Log.w("MonitorApp", "自动启动采集失败（不影响使用）", it) }
        }
        BootTrace.mark("Application.onCreate 结束")
        watchForeground()
    }

    /**
     * 进出前台的切换：退出应用时把悬浮窗挂出来，回到应用时收起来。
     *
     * 用 ActivityLifecycleCallbacks 自己数 resumed 的 Activity 数量，
     * 不引 ProcessLifecycleOwner 依赖；数量从 1 变 0 就是真的退到后台了
     * （应用内跳转不会触发）。
     */
    private fun watchForeground() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var resumed = 0

            override fun onActivityResumed(activity: android.app.Activity) {
                resumed += 1
                if (resumed == 1) {
                    hideOverlayOnEnter()
                }
            }

            override fun onActivityPaused(activity: android.app.Activity) {
                resumed -= 1
                if (resumed <= 0) {
                    resumed = 0
                    showOverlayOnExit()
                }
            }

            override fun onActivityCreated(
                activity: android.app.Activity,
                savedInstanceState: android.os.Bundle?,
            ) = Unit

            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(
                activity: android.app.Activity,
                outState: android.os.Bundle,
            ) = Unit

            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
    }

    /**
     * 回到前台：收起悬浮窗。
     *
     * 开关关着时不发命令——否则服务没在跑的话 `startService` 会把它拉起来，
     * 随即又被 `observeSettings` 的 `stopSelf()` 停掉，白白启停一次。
     */
    private fun hideOverlayOnEnter() {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching {
                if (settingsRepository.settings.first().overlayEnabled) {
                    OverlayService.hide(this@MonitorApp)
                }
            }.onFailure { Log.w("MonitorApp", "回到应用收起悬浮窗失败", it) }
        }
    }

    /**
     * 退到后台：开关开着且权限已给才挂悬浮窗，否则安静地什么都不做。
     *
     * 前提是服务已在应用前台时被 `OverlayService.ensure` 拉起（Android 12+
     * 禁止后台新建前台服务），这里只是给已运行的 FGS 发一条 SHOW 命令。
     */
    private fun showOverlayOnExit() {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching {
                // 管理端禁用「悬浮窗」：即使本地开关开着也不挂出
                if (syncManager.isFeatureDisabled("overlay")) return@launch
                val settings = settingsRepository.settings.first()
                if (settings.overlayEnabled && OverlayPermissions.canDrawOverlays(this@MonitorApp)) {
                    OverlayService.start(this@MonitorApp)
                }
            }.onFailure { Log.w("MonitorApp", "退到后台显示悬浮窗失败", it) }
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
