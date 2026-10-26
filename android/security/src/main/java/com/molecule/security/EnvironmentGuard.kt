package com.molecule.security

import android.os.Debug

/**
 * EnvironmentGuard —— Kotlin 侧环境检测补充（Native 已做 Root/Frida/调试器/模拟器）。
 * 命中即视为不纯，配合 TokenVault 的 native 检测做「双保险」。
 *
 * 这里只做「软失败」判定（返回 true），不主动退出 App，避免误杀正常设备。
 */
object EnvironmentGuard {

    /** 调试器已附着 或 处于等待调试的状态。 */
    fun isBeingDebugged(): Boolean = Debug.isDebuggerConnected()

    /**
     * 检测 Xposed / LSPosed 等注入框架的痕迹：
     * 检查当前调用栈是否出现 Xposed 桥接类（de.robv.android.xposed）。
     */
    fun hasXposedStack(): Boolean {
        return try {
            val stacks = Thread.currentThread().stackTrace
            stacks.any {
                val c = it.className
                c.contains("de.robv.android.xposed") ||
                    c.contains("LSPosed") ||
                    c.contains("edxposed")
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** Kotlin 侧综合：任一命中即不纯。 */
    fun isHostDirty(): Boolean = isBeingDebugged() || hasXposedStack()
}
