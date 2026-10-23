package com.devperf.monitor.overlay

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [ServiceViewTreeOwner] 回归测试。
 *
 * 这个类是悬浮窗三轮崩溃（帧时钟缺失 → ViewTree 宿主缺失 → 宿主初始化顺序错误）
 * 的收尾产物，三次都是「编译通过、真机才炸」的类型，所以把调用契约钉在这里：
 *
 * 1. `onCreate()` 内部顺序必须是先 `performRestore` 再把 lifecycle 推到 CREATED——
 *    savedstate 1.4.0 的 `SavedStateRegistryImpl.performAttach` 要求宿主仍处于
 *    INITIALIZED，反了会抛
 *    `IllegalStateException: Restarter must be created only during owner's initialization stage`，
 *    服务直接创建失败（1.0.6 线上事故）。
 * 2. `onStartCommand()` 可重复调用（START_STICKY 重进），必须幂等停在 RESUMED。
 * 3. `onDestroy()` 必须到达 DESTROYED，否则 ComposeView 的宿主永不销毁。
 */
class ServiceViewTreeOwnerTest {

    /**
     * JVM 单元测试没有主线程 Looper（isReturnDefaultValues 下 getMainLooper() 返回 null，
     * LifecycleRegistry 的主线程校验会 NPE），设个委托伪装主线程。
     */
    @Before
    fun fakeMainThread() {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })
    }

    @Test
    fun onCreate_不抛异常且到达CREATED() {
        val owner = ServiceViewTreeOwner()
        assertEquals(Lifecycle.State.INITIALIZED, owner.lifecycle.currentState)
        // 旧顺序（先 currentState = CREATED 再 performRestore）在这里必抛，
        // 这就是 1.0.6 华为机上「Unable to create service」的直接原因
        owner.onCreate()
        assertEquals(Lifecycle.State.CREATED, owner.lifecycle.currentState)
    }

    @Test
    fun onStartCommand_重复调用幂等停在RESUMED() {
        val owner = ServiceViewTreeOwner()
        owner.onCreate()
        owner.onStartCommand()
        owner.onStartCommand()
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
    }

    @Test
    fun onDestroy_到达DESTROYED() {
        val owner = ServiceViewTreeOwner()
        owner.onCreate()
        owner.onStartCommand()
        owner.onDestroy()
        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
    }

    @Test
    fun 完整生命周期流转() {
        val owner = ServiceViewTreeOwner()
        owner.onCreate()
        owner.onStartCommand()
        owner.onDestroy()
        // 顺序断言：任何一步抛异常都会让测试失败，不需要额外 try-catch
        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
    }
}
