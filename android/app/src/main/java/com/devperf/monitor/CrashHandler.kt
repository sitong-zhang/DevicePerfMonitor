package com.devperf.monitor

import android.os.Build
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃监测器。
 *
 * 作用：拦截主线程（含 Compose 合成崩溃）与协程线程的未捕获异常，
 * 把完整技术栈（机型 / 系统 / 异常类型 / 调用栈 / 全部线程栈）写入文件，
 * 并拉起独立进程 [CrashActivity] 全屏展示，方便用户截图反馈。
 *
 * 设计要点：
 * - 崩溃展示 Activity 运行在 `:crash` 独立进程，即使主进程主线程已死也能正常渲染；
 * - 不调用系统默认 handler 立即杀进程，给用户留出截图时间（进程由后台线程保活）；
 * - CrashActivity 自身崩溃时回退到系统默认处理，避免死循环。
 */
class CrashHandler private constructor(private val app: android.app.Application) :
    Thread.UncaughtExceptionHandler {

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        val report = runCatching { buildReport(thread, ex) }
            .getOrDefault("崩溃报告生成失败（异常：${ex.javaClass.name}）")
        // 先落盘：这是最可靠的一步。就算下面弹窗被系统拦掉，下次启动也能显示。
        BootTrace.writeCrash(report)
        // 再尝试立刻弹窗。Android 10+ 限制后台启动 Activity，这一步可能静默失败，
        // 失败也没关系——报告已经在文件里，下次进 MainActivity 会显示。
        launchCrashActivity(report)
        // 让进程干净退出，避免主线程已死、界面卡住不动。延迟一下，
        // 保证 startActivity 的 binder 请求先发出去。
        Thread {
            runCatching { Thread.sleep(1200) }
            runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
        }.apply { isDaemon = true }.start()
    }

    private fun buildReport(thread: Thread, ex: Throwable): String {
        val sw = StringWriter()
        val out = PrintWriter(sw)
        out.appendLine("========== 应用崩溃报告 / Crash Report ==========")
        out.appendLine("时间 / Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        out.appendLine("机型 / Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        out.appendLine("系统 / Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        out.appendLine("品牌 / Brand: ${Build.BRAND}  硬件 / Hardware: ${Build.HARDWARE}")
        out.appendLine("应用版本 / App: ${appVersion()}")
        out.appendLine("崩溃线程 / Thread: ${thread.name} (id=${thread.id})")
        out.appendLine("---------------------------------------------------")
        out.appendLine("异常类型 / Exception: ${ex.javaClass.name}")
        out.appendLine("异常信息 / Message: ${ex.message ?: "（无）"}")
        out.appendLine("---------------------------------------------------")
        out.appendLine("调用栈 / Stack trace:")
        ex.printStackTrace(out)
        out.appendLine("---------------------------------------------------")
        out.appendLine("全部线程栈 / All thread stacks:")
        for ((t, stack) in Thread.getAllStackTraces()) {
            out.appendLine("• ${t.name} [${t.state}]${if (t == thread) "  <-- 崩溃线程" else ""}")
            for (s in stack) out.appendLine("    at $s")
        }
        out.flush()
        return sw.toString()
    }

    private fun appVersion(): String = runCatching {
        val p = app.packageManager.getPackageInfo(app.packageName, 0)
        "${p.versionName} (${p.versionCode})"
    }.getOrDefault("unknown")

    private fun launchCrashActivity(report: String) {
        runCatching {
            CrashActivity.showFromCrash(app, report)
        }.onFailure { e ->
            // 拉不起来就算了：报告已落盘，下次启动会展示。这里只记一行，避免二次崩溃。
            Log.w("CrashHandler", "崩溃弹窗拉起失败（报告已保存，下次启动展示）", e)
        }
    }

    companion object {
        fun install(app: android.app.Application) {
            CrashHandler(app).install()
        }
    }
}
