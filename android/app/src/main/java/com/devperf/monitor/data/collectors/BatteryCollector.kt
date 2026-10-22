package com.devperf.monitor.data.collectors

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.devperf.monitor.core.Metric
import com.devperf.monitor.core.MetricId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 电池采集器。
 *
 * 数据来源（均为公开 API，无需权限）：
 * - ACTION_BATTERY_CHANGED sticky 广播：温度/电压/电量/充电状态/健康粗粒度状态；
 * - BatteryManager 属性：实时电流（µA）。
 *
 * 三态说明：
 * - 电池健康【百分比】Android 无公开接口（设计容量/循环次数均不可读），
 *   常态 UNAVAILABLE；仅 EXTRA_HEALTH 的粗粒度状态可用。
 *   禁止用循环次数或充电次数估算冒充"健康度"。
 */
class BatteryCollector(private val context: Context) : MetricCollector {

    private val batteryManager = context.getSystemService(BatteryManager::class.java)

    @Volatile
    private var sticky: Intent? = null

    /** 最近一次采样得到的充电状态（公开 API，始终可推导）。 */
    @Volatile
    var chargingState: ChargingState = ChargingState.UNKNOWN
        private set

    /** 最近一次采样得到的电池健康粗粒度状态（EXTRA_HEALTH，公开 API 仅此粒度）。 */
    @Volatile
    var healthCoarse: BatteryHealthCoarse = BatteryHealthCoarse.UNKNOWN
        private set

    override fun start(scope: CoroutineScope) {
        runCatching {
            sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
    }

    override fun stop() {
        sticky = null
    }

    override suspend fun sample(): Map<MetricId, Metric<*>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val intent = sticky ?: runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull().also { sticky = it }

        if (intent == null) {
            val unavailable = Metric.unavailable<Float>("无法读取电池广播", now)
            return@withContext mapOf(
                MetricId.BATTERY_TEMP to unavailable,
                MetricId.BATTERY_CURRENT to unavailable,
                MetricId.BATTERY_VOLTAGE to unavailable,
                MetricId.BATTERY_LEVEL to unavailable,
            )
        }

        chargingState = chargingStateOf(intent)
        healthCoarse = healthOf(intent)

        val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val voltageRaw = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val levelPercent = when {
            level in 0..100 && scale in 1..100 -> level * 100f / scale
            else -> batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 }?.toFloat()
        }

        val temp = if (tempRaw != Int.MIN_VALUE) Metric.available(tempRaw / 10f, now)
        else Metric.unavailable("设备不报告电池温度", now)

        val voltage = if (voltageRaw > 0) Metric.available(voltageRaw / 1000f, now)
        else Metric.unavailable("设备不报告电池电压", now)

        val levelMetric = levelPercent?.let { Metric.available(it, now) }
            ?: Metric.unavailable("无法读取电量", now)

        // 实时电流：µA → mA；部分机型不报告（返回 0 或极大值），按不可用处理
        val currentRaw = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentMa = currentRaw?.takeIf { it != 0 && kotlin.math.abs(it) < 5_000_000 }?.div(1000f)
        val current = currentMa?.let { Metric.available(it, now) }
            ?: Metric.unavailable("设备不报告实时电流", now)

        return@withContext mapOf(
            MetricId.BATTERY_TEMP to temp,
            MetricId.BATTERY_CURRENT to current,
            MetricId.BATTERY_VOLTAGE to voltage,
            MetricId.BATTERY_LEVEL to levelMetric,
        )
    }

    companion object {
        fun chargingStateOf(intent: Intent): ChargingState = when (
            intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        ) {
            BatteryManager.BATTERY_STATUS_CHARGING -> ChargingState.CHARGING
            BatteryManager.BATTERY_STATUS_DISCHARGING -> ChargingState.DISCHARGING
            BatteryManager.BATTERY_STATUS_FULL -> ChargingState.FULL
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> ChargingState.NOT_CHARGING
            else -> ChargingState.UNKNOWN
        }

        fun healthOf(intent: Intent): BatteryHealthCoarse = when (
            intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
        ) {
            BatteryManager.BATTERY_HEALTH_GOOD -> BatteryHealthCoarse.GOOD
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> BatteryHealthCoarse.OVERHEAT
            BatteryManager.BATTERY_HEALTH_DEAD -> BatteryHealthCoarse.DEAD
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> BatteryHealthCoarse.OVER_VOLTAGE
            BatteryManager.BATTERY_HEALTH_COLD -> BatteryHealthCoarse.COLD
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> BatteryHealthCoarse.UNSPECIFIED_FAILURE
            else -> BatteryHealthCoarse.UNKNOWN
        }
    }
}
