package com.devperf.monitor.data.collectors

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.devperf.monitor.core.Metric
import com.devperf.monitor.core.MetricId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设备温度采集器。
 *
 * 数据来源与三态：
 * - 系统热状态：PowerManager.getCurrentThermalStatus（API 29+，公开 API）；
 * - 热余量：PowerManager.getThermalHeadroom（API 30+，公开 API）。
 *   参数为预测秒数（0~60），本采集取 0（当前值）；返回 0.0~1.0 比值
 *   （1.0 = 达到 SEVERE 节流阈值），不支持时返回 NaN → UNAVAILABLE；
 * - 机身整体温度：取全部可读 thermal zone 的最大值（真实测量值，非模型估算，
 *   因此不同机型/ROM 之间可能存在口径差异，README 已说明）。
 */
class ThermalCollector(private val context: Context) : MetricCollector {

    private val powerManager = context.getSystemService(PowerManager::class.java)

    override fun start(scope: CoroutineScope) = Unit
    override fun stop() = Unit

    override suspend fun sample(): Map<MetricId, Metric<*>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        lastStatus = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                thermalStatusOf(powerManager.currentThermalStatus)
            } else ThermalStatus.UNSUPPORTED
        }.getOrDefault(ThermalStatus.UNSUPPORTED)

        lastHeadroom = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 参数为预测秒数（0~60），0 = 当前热余量；
                // 返回 0.0~1.0 比值（1.0 = 达到 SEVERE  throttling 阈值），
                // 设备不支持时返回 NaN → 归一为 null（UNAVAILABLE）
                powerManager.getThermalHeadroom(0)
                    .takeIf { it.isFinite() && it >= 0f }
            } else null
        }.getOrNull()

        val zones = ThermalZones.read()
        val deviceTemp = zones.maxOfOrNull { it.second }

        mapOf(
            MetricId.DEVICE_TEMP to (deviceTemp?.let { Metric.available(it, now) }
                ?: Metric.unavailable("thermal zone 不可读", now)),
        )
    }

    @Volatile
    var lastStatus: ThermalStatus = ThermalStatus.UNSUPPORTED
        private set

    @Volatile
    var lastHeadroom: Float? = null
        private set

    private fun thermalStatusOf(value: Int): ThermalStatus = when (value) {
        PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NONE
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
        PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
        PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalStatus.EMERGENCY
        else -> ThermalStatus.UNSUPPORTED
    }

    init {
        Log.d("ThermalCollector", "初始化：thermal zone + PowerManager 热状态")
    }
}
