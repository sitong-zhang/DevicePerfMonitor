package com.devperf.monitor.overlay

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * Service 托管的 ComposeView 用的人工生命周期宿主。
 *
 * 背景：ComposeView 在 onAttachedToWindow 时会通过 ViewTreeLifecycleOwner 找宿主，
 * 找不到就直接抛 `IllegalStateException: ViewTreeLifecycleOwner not found`——
 * 悬浮窗是 Service 用 WindowManager 加的，没有 Activity/Fragment 宿主，
 * 所以必须自己提供一个，否则一点「显示悬浮窗」就崩，表现出来的就是「悬浮窗出不来」。
 *
 * 同时提供 SavedStateRegistryOwner（rememberSaveable 需要）和 ViewModelStoreOwner
 * （viewModel() 需要），三者一起挂到 ViewTree 上。
 */
internal class ServiceLifecycleOwner :
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    /** 创建后调用：把生命周期推进到 RESUMED，Compose 才会开始合成。 */
    fun attach() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    /** 窗口移除前调用：正常走完销毁流程并清空 ViewModelStore。 */
    fun destroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
