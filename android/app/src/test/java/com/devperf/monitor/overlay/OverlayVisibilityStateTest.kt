package com.devperf.monitor.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OverlayVisibilityState] 回归测试。
 *
 * 钉的是 1.0.8 线上那个「自动挂出失效」的 bug：服务里用一个裸 Boolean 记
 * `overlayVisible`，`hide()` 从不复位，于是 `showOverlay()` 开头的
 * `if (overlayVisible) return` 从第二次开始把所有 ACTION_SHOW 全吞掉——
 * 用户进过一次应用之后，退后台再也挂不出悬浮窗。
 *
 * 这类状态 bug 编译期、lint 都发现不了，真机复现又要反复切后台，所以把
 * 可见性的转换规则抽成不依赖 Android 框架的 [OverlayVisibilityState]，在这里钉死。
 */
class OverlayVisibilityStateTest {

    @Test
    fun 首次显示返回true且状态变为可见() {
        val state = OverlayVisibilityState()

        assertTrue(state.requestShow())
        assertTrue(state.isVisible)
    }

    @Test
    fun 已显示时重复显示返回false_避免重复挂载窗口() {
        val state = OverlayVisibilityState()
        state.requestShow()

        // 重复 addView 同一个 view 会抛，第二次必须拦住
        assertFalse(state.requestShow())
        assertTrue(state.isVisible)
    }

    @Test
    fun 隐藏后能再次显示() {
        val state = OverlayVisibilityState()

        assertTrue(state.requestShow())
        assertTrue(state.requestHide())
        assertFalse(state.isVisible)

        // 核心回归点：1.0.8 就是在这里永远返回 false，悬浮窗再也挂不出来
        assertTrue(state.requestShow())
        assertTrue(state.isVisible)
    }

    @Test
    fun 未显示时隐藏返回false() {
        val state = OverlayVisibilityState()

        assertFalse(state.requestHide())
        assertFalse(state.isVisible)
    }

    @Test
    fun 多次隐藏显示循环仍然正确() {
        val state = OverlayVisibilityState()

        repeat(3) {
            assertTrue(state.requestShow())
            assertTrue(state.requestHide())
        }
        assertFalse(state.isVisible)
        assertTrue(state.requestShow())
    }

    @Test
    fun 服务停止后reset允许重新显示() {
        val state = OverlayVisibilityState()
        state.requestShow()

        // 服务被杀 / 窗口 detach：状态复位，下次能重新挂出
        state.reset()

        assertFalse(state.isVisible)
        assertTrue(state.requestShow())
    }

    @Test
    fun 挂载失败后复位能重新显示() {
        val state = OverlayVisibilityState()
        state.requestShow()

        // showOverlay 里挂载抛异常时的兜底路径：复位后必须还能重试
        state.reset()

        assertTrue(state.requestShow())
    }
}
