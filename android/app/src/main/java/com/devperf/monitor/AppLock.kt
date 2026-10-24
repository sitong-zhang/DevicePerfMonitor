package com.devperf.monitor

/**
 * 应用级软锁：强制更新没走完时，整个 App（含跑在独立窗口里的悬浮窗）都不可用。
 *
 * 为什么需要它：悬浮窗是 SYSTEM_ALERT_WINDOW 的独立 Window，Activity 里的
 * 强制更新弹窗盖不到它——只锁主界面会出现「App 已经锁死、悬浮窗还能照常看
 * 数据」的漏网。用这个进程内开关在悬浮窗的拉起入口统一拦截，并在发现强制
 * 更新时把已经挂着的窗口停掉，做到用户说的「不更新就什么都用不了」。
 *
 * 只在进程内存活：装上新版后进程重启，锁自然失效（不需要也不能持久化，
 * 否则一次检查失败就把用户永久锁在旧版里）。
 */
object AppLock {

    /** 是否有「必须更新才能继续」的更新正挂在流程上。 */
    @Volatile
    var updatePending: Boolean = false

    /** 强制更新期间一律不允许悬浮窗拉起/显示。 */
    fun overlayAllowed(): Boolean = !updatePending
}
