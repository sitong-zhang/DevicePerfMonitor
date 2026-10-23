package com.devperf.monitor.overlay

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Service 托管的 ViewTree 宿主（LifecycleOwner + SavedStateRegistryOwner）。
 *
 * 背景（崩溃修复）：Compose 1.11 起，AbstractComposeView.onAttachedToWindow →
 * ensureCompositionCreated → resolveComposeViewContext() 会强制要求 ViewTree 上
 * 取到 LifecycleOwner 与 SavedStateRegistryOwner，缺任何一个直接抛
 * IllegalStateException（"Composed into the View which doesn't propagate
 * ViewTreeLifecycleOwner!"），挂窗口即崩。只设 setParentCompositionContext(Recomposer)
 * 不够——父合成上下文不带宿主信息，Recomposer 也不是 ComposeViewContext。
 * Service 没有 Activity/Fragment 宿主，悬浮窗与垃圾桶两个 ComposeView 都靠这里兜底。
 *
 * 生命周期由 [OverlayService] 转发（Service 实例一次对应一个宿主）：
 * - onCreate → ON_CREATE + performRestore(null)
 * - onStartCommand → 推进到 RESUMED（重复启动命令幂等）
 * - onDestroy → DESTROY
 *
 * savedState 只做占位：悬浮窗内容不持久化（performRestore(null)），
 * 界面状态（缩放/拖动/形态）都在 OverlayController 的 StateFlow 里，不走 Bundle。
 * 不提供 ViewModelStoreOwner：ComposeViewContext 允许其为 null，
 * 且悬浮窗/垃圾桶内容不使用 viewModel()。
 */
class ServiceViewTreeOwner : SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)

    // create() 只是构造；performRestore 内部会自动 performAttach（需先到 CREATED）
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    /** [OverlayService.onCreate] 时调用（服务实例只调一次）。 */
    fun onCreate() {
        // 顺序不能反：savedstate 1.4.0 的 SavedStateRegistryImpl.performAttach 要求
        // 宿主 lifecycle 仍处于 INITIALIZED（Recreator 必须在 ON_CREATE 派发前注册），
        // 否则抛 IllegalStateException("Restarter must be created only during
        // owner's initialization stage")——服务直接创建失败。
        // performRestore 内部会自动 performAttach，所以先 restore、后推 CREATED
        // （与 ComponentActivity 的顺序一致）。
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    /** [OverlayService.onStartCommand] 时调用；START_STICKY 重进时幂等。 */
    fun onStartCommand() {
        // setter 会按需补发 ON_START/ON_RESUME，已 RESUMED 时是 no-op，不会回退状态
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /** [OverlayService.onDestroy] 时调用（窗口先摘除，再销毁宿主）。 */
    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    /**
     * 把自己挂到指定 View 的 ViewTree 上。
     *
     * 必须在 windowManager.addView 之前调用——ComposeView 是挂窗口时才去
     * resolveComposeViewContext()，晚了就已经抛了。
     *
     * 注意 API 形态：这两个 setter 在 Kotlin 侧是扩展函数
     * （`View.setViewTreeLifecycleOwner` / `View.setViewTreeSavedStateRegistryOwner`），
     * 不是 `ViewTreeLifecycleOwner.set(view, owner)` 那样的静态方法——Java 看到的
     * 静态门面是 @file:JvmName 生成的，Kotlin 导入类名会报 unresolved reference。
     */
    fun attachTo(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }
}
