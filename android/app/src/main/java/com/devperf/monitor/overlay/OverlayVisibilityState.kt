package com.devperf.monitor.overlay

/**
 * 悬浮窗可见性状态机。
 *
 * 为什么单独抽一个不碰 Android 框架的类：1.0.8 的「退出应用自动挂出」在真机上
 * 从进过一次应用后就彻底失效——`OverlayService` 用一个裸 Boolean 记 `overlayVisible`，
 * `hide()` 从来不把它置回 false，于是退后台发的 ACTION_SHOW 全被
 * `if (overlayVisible) return` 吞掉，窗口再也出不来。这类"编译通过、真机才炸"的
 * 状态 bug 必须能在 JVM 单测里复现，所以把可见性的转换规则收敛到这里，
 * 由 `OverlayVisibilityStateTest` 钉死。
 *
 * 语义（返回值 = 调用方是否真的需要动窗口）：
 * - [requestShow]：已经显示就返回 false，调用方应跳过重建窗口（重复 addView 会炸）；
 * - [requestHide]：没在显示就返回 false；
 * - [reset]：窗口被销毁（服务停止 / controller detach）后复位，允许下次重新显示。
 *
 * 本类不持有任何 Android 对象，纯内存状态，可单测。
 */
internal class OverlayVisibilityState {

    private var visible = false

    /** 是否正在显示。 */
    val isVisible: Boolean get() = visible

    /** 请求显示：返回 true 表示调用方需要真正创建/挂载窗口。 */
    fun requestShow(): Boolean {
        if (visible) return false
        visible = true
        return true
    }

    /** 请求隐藏：返回 true 表示调用方需要真正移除窗口。 */
    fun requestHide(): Boolean {
        if (!visible) return false
        visible = false
        return true
    }

    /** 窗口已销毁（服务停止 / detach）：复位到未显示，允许下一次重新挂出。 */
    fun reset() {
        visible = false
    }
}
