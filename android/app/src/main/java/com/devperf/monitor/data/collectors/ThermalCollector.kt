package com.devperf.monitor.data.collectors

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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
 * - 机身整体温度：优先取全部可读 thermal zone 的最大值（真实测量值）；
 *   **zone 读不到时回退电池温度**（ACTION_BATTERY_CHANGED 的 EXTRA_TEMPERATURE，
 *   公开 API、所有机型可读）。华为等国产 ROM 的 SELinux 策略禁止普通应用读
 *   /sys/class/thermal，1.0.9 上这些机型悬浮窗/详情页完全没有温度——
 *   电池温度与机身温度强相关（充电/重载时同步上升），是行业通用的降级口径，
 *   但两者通常差 5~15℃，因此 [lastSource] 记录真实来源，UI 可标注
 *   "当前为电池温度"，不冒充 zone 读数。
 */
class ThermalCollector(private val context: Context) : MetricCollector {

    /** 温度读数来源（透明标注，不冒充）。 */
    enum class Source { NONE, ZONE, BATTERY }

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
        val zoneTemp = zones.maxOfOrNull { it.second }
            // zone 能列出来但全是 0/未初始化的，视为无效
            ?.takeIf { it > 0f }

        val (temp, source) = when {
            zoneTemp != null -> zoneTemp to Source.ZONE
            else -> {
                val battery = batteryTempC()
                if (battery != null) battery to Source.BATTERY else null to Source.NONE
            }
        }
        lastSource = source

        mapOf(
            MetricId.DEVICE_TEMP to (temp?.let { Metric.available(it, now) }
                ?: Metric.unavailable(
                    if (zones.isEmpty()) "thermal zone 不可读，电池温度也不可用"
                    else "thermal zone 读数无效，电池温度不可用",
                    now,
                )),
        )
    }

    /**
     * 电池温度（℃）：ACTION_BATTERY_CHANGED sticky 广播，EXTRA_TEMPERATURE
     * 单位为 0.1℃。所有机型可读，是 zone 不可读时机身温度的标准降级口径。
     * 合理范围 0~80℃，之外视为无效读数。
     */
    private fun batteryTempC(): Float? {
        val intent: Intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return null
        val raw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (raw == Int.MIN_VALUE) return null
        val c = raw / 10f
        return c.takeIf { it in 0f..80f }
    }

    @Volatile
    var lastStatus: ThermalStatus = ThermalStatus.UNSUPPORTED
        private set

    @Volatile
    var lastHeadroom: Float? = null
        private set

    /** 最近一次温度读数来源（供 UI 透明标注）。 */
    @Volatile
    var lastSource: Source = Source.NONE
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
        Log.d("ThermalCollector", "初始化：thermal zone（优先）+ 电池温度（回退）+ PowerManager 热状态")
    }
}
