package com.devperf.monitor

import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 崩溃展示 Activity（运行在 `:crash` 独立进程）。
 *
 * 纯 View 实现（不依赖 Compose / 液态玻璃），避免与主进程同样的渲染崩溃。
 * 功能：全屏展示技术栈 + 复制 + 关闭应用。
 */
class CrashActivity : Activity() {

    companion object {
        const val EXTRA_REPORT = "extra_report"

        /** true = 崩溃当下弹出的（主进程已死，关闭时顺手杀主进程）；false = 下次启动补展示。 */
        const val EXTRA_KILL_APP = "extra_kill_app"

        /** 崩溃当下调用：独立 task，覆盖当前界面。 */
        fun showFromCrash(context: Context, report: String) {
            val intent = Intent(context, CrashActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
                putExtra(EXTRA_REPORT, report)
                putExtra(EXTRA_KILL_APP, true)
            }
            context.startActivity(intent)
        }

        /** 下次启动补展示：不建新 task、不杀主进程，关掉就回到正常界面。 */
        fun showPending(context: Context, report: String) {
            val intent = Intent(context, CrashActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_REPORT, report)
                putExtra(EXTRA_KILL_APP, false)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val report = intent?.getStringExtra(EXTRA_REPORT)
            ?: runCatching { filesDir.resolve("crash_pending.txt").readText() }.getOrNull()
            ?: "（未能读取崩溃详情）"
        val killApp = intent?.getBooleanExtra(EXTRA_KILL_APP, true) ?: true

        val textView = TextView(this).apply {
            text = report
            setTextIsSelectable(true)
            textSize = 11f
            setTextColor(0xFFE6E6E6.toInt())
            typeface = Typeface.MONOSPACE
            setPadding(20, 16, 20, 16)
        }

        val scroll = ScrollView(this).apply {
            addView(textView)
        }

        val copyBtn = Button(this).apply {
            text = "复制崩溃信息"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("crash", report))
                Toast.makeText(this@CrashActivity, "已复制，可粘贴给开发者", Toast.LENGTH_SHORT).show()
            }
        }
        val closeBtn = Button(this).apply {
            text = if (killApp) "关闭应用" else "关闭，继续使用"
            setOnClickListener {
                if (killApp) killApp() else finish()
            }
        }

        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
            addView(copyBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(closeBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val title = TextView(this).apply {
            text = if (killApp) "应用崩溃了（Crash）" else "上次启动崩溃过（补展示报告）"
            textSize = 18f
            setTextColor(0xFFF2B8B5.toInt())
            gravity = Gravity.CENTER
            setPadding(8, 12, 8, 12)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1B1B1F.toInt())
            setPadding(12, 12, 12, 12)
            addView(title)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buttonBar)
        }
        setContentView(root)
    }

    private fun killApp() {
        // 杀掉主进程（崩溃发生在主进程），再结束当前崩溃展示进程
        runCatching {
            (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.killBackgroundProcesses(packageName)
        }
        Process.killProcess(Process.myPid())
        finishAffinity()
        @Suppress("DEPRECATION")
        System.runFinalization()
        System.exit(0)
    }
}
