package com.devperf.monitor.analysis

import com.devperf.monitor.data.MonitorSettings
import com.devperf.monitor.data.collectors.ChargingState
import com.devperf.monitor.data.collectors.DeviceSnapshot
import com.devperf.monitor.data.storage.HistoryStore
import com.devperf.monitor.core.MetricId
import com.devperf.monitor.util.Format

/**
 * 场景化智能建议引擎（纯本地规则，无联网、无大模型）。
 *
 * 规则（与需求逐条对应）：
 * 1. 高温：T = max(CPU 温度, 电池温度)
 *    - 轻微（40~45℃）：降亮度、清后台；
 *    - 中度（45~55℃）：关高刷、降画质；
 *    - 严重（>55℃）：停高负载、静置降温，并按 0.5℃/分钟自然散热估算降温时长
 *      （预计时长 = (T - 40) / 0.5 分钟）；
 * 2. 卡顿：帧时间标准差 > 8ms 或连续 3 次采样掉帧率 > 10% → 清后台、切性能模式；
 * 3. 耗电：连续 30s 放电电流 > 1500mA → 列出当前高耗电后台类型建议；
 * 4. 内存：占用 > 85% → 建议清理后台应用类型；
 * 5. 数据不足：输出"数据不足，无法分析"，禁止编造建议。
 */
object AdviceEngine {

    enum class Severity { INFO, WARNING, CRITICAL }

    data class Advice(
        val id: String,
        val severity: Severity,
        val title: String,
        val detail: String,
    )

    /** 自然散热速率：℃/分钟。 */
    const val COOLING_RATE_C_PER_MIN = 0.5f

    fun analyze(
        snapshot: DeviceSnapshot,
        settings: MonitorSettings,
        history: HistoryStore,
    ): List<Advice> {
        val advice = mutableListOf<Advice>()
        val cpuTemp = snapshot.cpu.temperatureC.value
        val batteryTemp = snapshot.battery.temperatureC.value
        val maxTemp = listOfNotNull(cpuTemp, batteryTemp).maxOrNull()

        // ---------- 1. 高温场景 ----------
        if (maxTemp != null) {
            when {
                maxTemp >= 55f -> {
                    val coolingMinutes = (maxTemp - 40f) / COOLING_RATE_C_PER_MIN
                    advice += Advice(
                        id = "heat_severe",
                        severity = Severity.CRITICAL,
                        title = "严重过热（${Format.temp(maxTemp)}）",
                        detail = "立即停止高负载应用并静置降温。" +
                            "按自然散热约 ${Format.coolingMinutes(coolingMinutes)}" +
                            "可降至 40℃（估算值，仅供参考）。",
                    )
                }
                maxTemp >= 45f -> {
                    advice += Advice(
                        id = "heat_moderate",
                        severity = Severity.WARNING,
                        title = "中度发热（${Format.temp(maxTemp)}）",
                        detail = "建议关闭高刷新率、降低游戏画质，避免边充边玩。",
                    )
                }
                maxTemp >= 40f -> {
                    advice += Advice(
                        id = "heat_light",
                        severity = Severity.INFO,
                        title = "轻微发热（${Format.temp(maxTemp)}）",
                        detail = "建议降低屏幕亮度、清理不必要的后台应用。",
                    )
                }
            }
        }

        // ---------- 2. 卡顿场景 ----------
        val frameStdev = snapshot.display.frameStdevMs.value
        val jankRatio = snapshot.display.jankRatio30s.value
        val recentJank = history.series(MetricId.JANK_RATIO, 10).takeLast(3)
        val sustainedJank = recentJank.size >= 3 && recentJank.all { it.value > 10f }
        if ((frameStdev != null && frameStdev > 8f) || (jankRatio != null && jankRatio > 10f) || sustainedJank) {
            advice += Advice(
                id = "jank",
                severity = Severity.WARNING,
                title = "检测到卡顿",
                detail = buildString {
                    if (frameStdev != null) append("帧时间标准差 ${Format.value(frameStdev, 1)}ms。")
                    if (jankRatio != null) append("最近掉帧率 ${Format.value(jankRatio, 1)}%。")
                    append("建议清理后台应用、切换到性能模式。")
                },
            )
        }

        // ---------- 3. 耗电场景 ----------
        val current = snapshot.battery.currentMa.value
        val discharging = snapshot.battery.chargingState == ChargingState.DISCHARGING
        val recentCurrent = history.series(MetricId.BATTERY_CURRENT, 30)
        val sustainedDrain = recentCurrent.size >= 15 && recentCurrent.all { it.value < -1500f }
        if (discharging && ((current != null && current < -1500f) || sustainedDrain)) {
            val topApps = snapshot.memory.perApp.value?.take(3)
            advice += Advice(
                id = "battery_drain",
                severity = Severity.WARNING,
                title = "放电电流偏高",
                detail = buildString {
                    append("持续大电流放电会加速电池老化。")
                    if (topApps != null && topApps.isNotEmpty()) {
                        append("当前占用最高的后台：")
                        append(topApps.joinToString("、") { it.packageName })
                        append("。")
                    }
                    append("建议关闭定位/蓝牙/同步等耗电项。")
                },
            )
        }

        // ---------- 4. 内存场景 ----------
        val usageRatio = snapshot.memory.usageRatio.value
        if (usageRatio != null && usageRatio > 85f) {
            val topApps = snapshot.memory.perApp.value?.take(3)
            advice += Advice(
                id = "memory_high",
                severity = Severity.WARNING,
                title = "内存占用过高（${Format.value(usageRatio, 0)}%）",
                detail = buildString {
                    append("建议清理后台应用。")
                    if (topApps != null && topApps.isNotEmpty()) {
                        append("当前占用最高：")
                        append(topApps.joinToString("、") { "${it.packageName}(${Format.value(it.mb, 0)}MB)" })
                        append("。")
                    } else {
                        append("（后台应用明细需 root/Shizuku，当前不可用）")
                    }
                },
            )
        }

        // ---------- 5. 数据不足 ----------
        val anyData = listOfNotNull(cpuTemp, batteryTemp, frameStdev, jankRatio, current, usageRatio)
            .isNotEmpty()
        if (!anyData) {
            advice += Advice(
                id = "insufficient",
                severity = Severity.INFO,
                title = "数据不足，无法分析",
                detail = "等待采集到温度、帧率、电流或内存数据后再给出建议。",
            )
        }

        return advice.sortedBy { it.severity.ordinal }
    }
}
