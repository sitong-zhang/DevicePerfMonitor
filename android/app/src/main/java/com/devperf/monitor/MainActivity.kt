package com.devperf.monitor

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.devperf.monitor.data.CollectionService
import com.devperf.monitor.overlay.OverlayPermissions
import com.devperf.monitor.overlay.OverlayService
import com.devperf.monitor.ui.MonitorViewModel
import com.devperf.monitor.ui.nav.AppNav
import com.devperf.monitor.ui.theme.DevicePerfTheme
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.OnRequestPermissionResultListener

/**
 * 唯一 Activity：承载液态玻璃主题 + 导航。
 *
 * 职责（对应需求权限与降级条目）：
 * - POST_NOTIFICATIONS 运行时申请（API 33+，拒绝后仅告警通知不可用，其余功能正常）；
 * - SYSTEM_ALERT_WINDOW 引导：设置页开关跳系统悬浮窗设置页（多级降级见
 *   [OverlayPermissions.requestOverlayPermission]），返回后在 onResume 刷新服务状态；
 * - Shizuku 授权回调：透传给 Shizuku SDK，成功后 ViewModel 刷新三态；
 * - 前台上下文启动采集/悬浮窗服务（后台启动 FGS 会被系统拒绝，失败降级不崩溃）。
 */
class MainActivity : ComponentActivity() {

    /** 手动持有 ViewModel：依赖仅 Application 上下文，无需工厂注入。 */
    private val viewModel: MonitorViewModel by lazy { MonitorViewModel(application) }

    /** 通知权限（API 33+）：拒绝仅影响告警通知，不阻断主流程。 */
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 结果不改变 UI 结构：通知权限态由设置页实时查询展示
        }

    private val shizukuPermissionListener =
        OnRequestPermissionResultListener { _, _ ->
            // Shizuku API 13.1.5 起授权结果由 SDK 内部自动分发给监听器
            // （旧版的静态转发 Shizuku.onRequestPermissionResult(int,int) 已移除）；
            // 这里只需刷新特权通道三态（root/Shizuku/无）。
            viewModel.requestPrivilege()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BootTrace.mark("MainActivity.onCreate")

        // 上次启动没走完（崩溃被记录，或崩溃时弹窗被系统拦掉）→ 这次前台补展示报告。
        // 放在 setContent 之前，保证崩溃报告一定先被看到。
        BootTrace.pendingReport()?.let { report ->
            BootTrace.clearPending()
            runCatching { CrashActivity.showPending(this, report) }
                .onFailure { Log.w("MainActivity", "崩溃报告展示失败", it) }
        }

        maybeRequestNotifications()
        runCatching { Shizuku.addRequestPermissionResultListener(shizukuPermissionListener) }

        BootTrace.mark("setContent 开始")
        setContent {
            val settings by viewModel.settings.collectAsState()
            DevicePerfTheme(settings.themeMode) {
                AppNav(
                    viewModel = viewModel,
                    onRequestOverlayPermission = {
                        // 多级降级：本应用 overlay 设置页 → 应用详情页；全部失败返回 false，
                        // 由 UI 给出文字指引（见设置页说明）
                        OverlayPermissions.requestOverlayPermission(this)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回：重新问一次权限，权限给了就把悬浮窗服务拉起来
        viewModel.refreshOverlayPermission()
        val settings = viewModel.settings.value
        if (settings.overlayEnabled && OverlayPermissions.canDrawOverlays(this)) {
            // 关键：这里只 ENSURE（拉起服务、不显示窗口），不能 SHOW。
            // Android 12+ 禁止后台新建前台服务，必须在还处于前台时把服务起好，
            // 之后退后台由 MonitorApp 发 ACTION_SHOW 命令（对已运行的 FGS 发命令
            // 不受限制），「退出应用自动挂出」才能真的生效。
            // 显示/隐藏的时机统一由 MonitorApp 的前后台监听决定，这里不插手，
            // 否则会和它的 ACTION_HIDE 打架（1.0.8 自动挂出失效的根因之一）。
            OverlayService.ensure(this)
        }
        // 界面已经可见 → 本次启动算成功，清掉崩溃残留与轨迹
        BootTrace.markBooted()
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        super.onDestroy()
    }

    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    /** 确保采集服务运行（首页/设置页调用；FGS 启动被拒时降级为普通 Service）。 */
    fun ensureCollectionService() {
        runCatching { CollectionService.start(this) }
            .onFailure {
                Log.w("MainActivity", "前台采集服务启动被系统拒绝，降级为普通服务", it)
                runCatching { startService(Intent(this, CollectionService::class.java)) }
            }
    }
}
