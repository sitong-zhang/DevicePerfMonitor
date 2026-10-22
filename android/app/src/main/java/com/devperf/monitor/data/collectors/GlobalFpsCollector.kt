package com.devperf.monitor.data.collectors

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.util.Log
import com.devperf.monitor.core.Metric
import com.devperf.monitor.core.MetricId
import com.devperf.monitor.core.MetricState
import com.devperf.monitor.core.PermissionAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 全局 FPS 采集器（其他应用/游戏的实时帧率）。
 *
 * 可行性说明（README「API-权限-可用性-降级」对照表）：
 * - Android 没有向普通 App 公开全局 FPS 的 API，唯一合规路径是 shell 级命令：
 *   `dumpsys gfxinfo <前台包名>` 解析 "Total frames rendered" / "Janky frames"；
 * - 执行通道：root(su) 或 Shizuku（免 root，用户显式授权）；
 * - 前台包名来源：UsageStatsManager（需"使用情况访问权"）或 shell `dumpsys activity activities`；
 * - 两者皆无 → UNAVAILABLE，UI 显示"— 不可用"，绝不估算。
 *
 * 注意：Shizuku 以 shell(2000) 权限运行，与 adb shell 能力一致；
 * 部分厂商 ROM 会限制 shell 读取 gfxinfo，此时同样降级不可用。
 */
class GlobalFpsCollector(
    private val context: Context,
    private val shell: PrivilegedShell,
) : MetricCollector {

    private data class Sample(val totalFrames: Long, val jankyFrames: Long, val at: Long)

    private var previous: Sample? = null
    private var lastPackage: String? = null

    override fun start(scope: CoroutineScope) = Unit
    override fun stop() {
        previous = null
        lastPackage = null
    }

    override suspend fun sample(): Map<MetricId, Metric<*>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        if (shell.backend == PrivilegedShell.Backend.NONE) {
            val state: MetricState<Float> =
                if (shell.shizukuInstalled) {
                    MetricState.NeedsPermission("Shizuku 授权", PermissionAction.SHIZUKU)
                } else {
                    MetricState.Unavailable("未安装 Shizuku，也未获取 root")
                }
            return@withContext mapOf(MetricId.GLOBAL_FPS to Metric(state, now))
        }

        val pkg = foregroundPackage()
        if (pkg == null) {
            return@withContext mapOf(
                MetricId.GLOBAL_FPS to Metric.unavailable<Float>(
                    "无法获取前台应用（需使用情况访问权或特权通道）", now,
                ),
            )
        }

        val output = shell.run("dumpsys gfxinfo $pkg") ?: return@withContext mapOf(
            MetricId.GLOBAL_FPS to Metric.unavailable<Float>("dumpsys gfxinfo 执行失败", now),
        )

        val total = TOTAL_FRAMES.find(output)?.groupValues?.get(1)?.toLongOrNull()
        val janky = JANKY_FRAMES.find(output)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        if (total == null || total <= 0L) {
            // 目标应用近期没有渲染帧（刚启动/已退后台），不编造
            return@withContext mapOf(
                MetricId.GLOBAL_FPS to Metric.unavailable<Float>(
                    "前台应用暂无帧数据（可能刚启动或已退后台）", now,
                ),
            )
        }

        val current = Sample(total, janky, now)
        val before = previous
        previous = current
        lastPackage = pkg

        if (before == null || before.totalFrames >= total || now <= before.at) {
            // 首次采样或计数器被重置：只建立基线
            return@withContext mapOf(
                MetricId.GLOBAL_FPS to Metric.unavailable<Float>("正在建立基线…", now),
            )
        }

        val elapsedSec = (now - before.at) / 1000f
        val frames = total - before.totalFrames
        val fps = frames / elapsedSec
        if (fps <= 0f || fps > 1000f) {
            return@withContext mapOf(
                MetricId.GLOBAL_FPS to Metric.unavailable<Float>("帧率数据异常，已丢弃", now),
            )
        }
        mapOf(MetricId.GLOBAL_FPS to Metric.available(fps, now))
    }

    /** 前台应用包名：优先 UsageStats（需授权），其次特权 shell。 */
    private suspend fun foregroundPackage(): String? {
        if (hasUsageStatsPermission()) {
            val usm = context.getSystemService(UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            val stats = runCatching {
                usm?.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 15_000, now)
            }.getOrNull()
            val top = stats?.maxByOrNull { it.lastTimeUsed }
            if (top != null && now - top.lastTimeUsed < 15_000) return top.packageName
        }
        val output = shell.run("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -5")
        return output?.lineSequence()
            ?.mapNotNull { RESUMED_ACTIVITY.find(it)?.groupValues?.get(1) }
            ?.firstOrNull()
            ?.takeIf { it.contains(".") }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = runCatching {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }.getOrDefault(AppOpsManager.MODE_ERRORED)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 当前正在监控的前台应用（诊断用）。 */
    fun currentTarget(): String? = lastPackage

    private companion object {
        const val TAG = "GlobalFpsCollector"
        val TOTAL_FRAMES = Regex("Total frames rendered:\\s*(\\d+)")
        val JANKY_FRAMES = Regex("Janky frames:\\s*(\\d+)")
        /** "mResumedActivity: ActivityRecord{... u0 com.pkg/.MainActivity t123}" */
        val RESUMED_ACTIVITY = Regex("u\\d+\\s+([A-Za-z0-9_.]+)/")
    }
}
