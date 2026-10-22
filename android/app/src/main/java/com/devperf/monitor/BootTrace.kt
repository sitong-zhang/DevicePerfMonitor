package com.devperf.monitor

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动轨迹记录器。
 *
 * 存在的理由：Android 10 起限制了"后台启动 Activity"，如果崩溃发生在还没有任何可见
 * 界面的时候（Application / ContentProvider / 首帧合成之前），直接 startActivity 会被
 * 系统静默拦掉——结果就是"闪退但看不到任何报错"。
 *
 * 所以改成两步：
 * 1. 启动过程中每一步写一行轨迹到文件，崩溃内容也写进文件；
 * 2. 下次启动进到 MainActivity（前台，允许弹窗）时，如果发现上次没走完，就把轨迹 +
 *    崩溃栈一起弹出来，然后才继续正常界面。
 *
 * 这样哪怕崩溃发生在监测器安装之前、或者是 native 层崩溃（Java 层捕获不到），
 * 至少能定位到"死在哪一步"。
 */
object BootTrace {

    private const val TRACE_FILE = "boot_trace.txt"
    private const val CRASH_FILE = "crash_pending.txt"
    private const val BOOTED_FILE = "booted.flag"

    private val lock = Any()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var dir: File? = null

    /** 上次启动遗留的未处理报告（崩溃栈 + 轨迹），在 [beginProcess] 时读入内存。 */
    @Volatile
    private var pending: String? = null

    /** 本次启动是否已经走完（走完后不再记轨迹）。 */
    @Volatile
    private var booted = false

    /** 进程最早期调用（Application.attachBaseContext）：判定上次启动是否失败。 */
    fun beginProcess(context: Context) {
        val d = context.filesDir
        dir = d
        val bootedFlag = File(d, BOOTED_FILE)
        val trace = File(d, TRACE_FILE)
        val crash = File(d, CRASH_FILE)

        val lastBootOk = bootedFlag.exists()
        // 清掉标记：本次启动走完才会重新写
        runCatching { bootedFlag.delete() }

        if (lastBootOk) {
            // 上次正常走完，残留文件没用了
            runCatching { trace.delete() }
            runCatching { crash.delete() }
            pending = null
            return
        }

        val leftover = buildString {
            val c = runCatching { crash.readText() }.getNullIfEmpty()
            val t = runCatching { trace.readText() }.getNullIfEmpty()
            if (c != null) {
                appendLine("========== 上次启动崩溃 / Last crash ==========")
                append(c.trimEnd())
                appendLine()
            }
            if (t != null) {
                appendLine()
                appendLine("========== 上次启动轨迹 / Boot trace ==========")
                appendLine("（最后一行就是卡住/崩溃的位置）")
                append(t.trimEnd())
            }
        }
        pending = if (leftover.isBlank() || leftover == "\n") null else leftover
        // 轨迹文件重新开写
        runCatching { trace.writeText("") }
        runCatching { crash.delete() }
    }

    /** 记一步。启动走完之后自动不再记录，避免长期写文件。 */
    fun mark(step: String) {
        if (booted) return
        val d = dir ?: return
        synchronized(lock) {
            runCatching {
                File(d, TRACE_FILE).appendText("${fmt.format(Date())}  $step\n")
            }
        }
    }

    /** 崩溃内容落盘（供下次启动展示）。 */
    fun writeCrash(report: String) {
        val d = dir ?: return
        synchronized(lock) {
            runCatching { File(d, CRASH_FILE).writeText(report) }
        }
    }

    /** 界面真正可见后调用：标记本次启动成功，清掉所有残留。 */
    fun markBooted() {
        booted = true
        val d = dir ?: return
        synchronized(lock) {
            runCatching { File(d, BOOTED_FILE).writeText("ok") }
            runCatching { File(d, TRACE_FILE).delete() }
            runCatching { File(d, CRASH_FILE).delete() }
        }
        pending = null
    }

    /** 上次启动遗留的报告，没有则 null。 */
    fun pendingReport(): String? = pending

    /** 展示完之后清掉，避免每次启动都弹。 */
    fun clearPending() {
        pending = null
        val d = dir ?: return
        synchronized(lock) {
            runCatching { File(d, CRASH_FILE).delete() }
            runCatching { File(d, TRACE_FILE).writeText("") }
        }
    }

    private fun Result<String>.getNullIfEmpty(): String? {
        val v = getOrNull() ?: return null
        return if (v.isBlank()) null else v
    }
}
