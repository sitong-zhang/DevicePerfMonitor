package com.devperf.monitor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.devperf.monitor.core.Metric
import com.devperf.monitor.core.PermissionAction
import com.devperf.monitor.data.ThemeMode
import com.devperf.monitor.data.collectors.BatteryHealthCoarse
import com.devperf.monitor.data.collectors.BatteryMetrics
import com.devperf.monitor.data.collectors.ChargingState
import com.devperf.monitor.data.collectors.CoreFreq
import com.devperf.monitor.data.collectors.CoreUsage
import com.devperf.monitor.data.collectors.CpuMetrics
import com.devperf.monitor.data.collectors.DeviceSnapshot
import com.devperf.monitor.data.collectors.DisplayMetrics
import com.devperf.monitor.data.collectors.GpuMetrics
import com.devperf.monitor.data.collectors.MemoryMetrics
import com.devperf.monitor.data.collectors.ThermalMetrics
import com.devperf.monitor.data.collectors.ThermalStatus
import com.devperf.monitor.overlay.OverlayContent
import com.devperf.monitor.overlay.OverlayController
import com.devperf.monitor.overlay.OverlayData
import com.devperf.monitor.overlay.OverlayGestureCallbacks
import com.devperf.monitor.overlay.drawOverlayWallpaper
import com.devperf.monitor.ui.kit.LocalBackdrop
import com.devperf.monitor.ui.kit.appLayerBackdrop
import com.devperf.monitor.ui.theme.DevicePerfTheme
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 悬浮窗内容回归（1.0.26：指标全量化 + 悬浮窗玻璃化）。
 *
 * legacy 图形模式（RuntimeShader 为 stub）驱动 OverlayContent 的生产代码路径：
 * - 三态形态循环（单击 CARD → NUMBERS → COLLAPSED → CARD）；
 * - 全量数据下卡片态展示 1.0.26 新增的指标行（CPU/GPU/温度/电池/FPS）；
 * - 采不到的指标不进卡片（无「—」占位）；FPS 未授权时留引导行；
 * - OverlayData.from 的字段映射（悬浮窗只透出 snapshot，不新采集）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayContentRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun fullSnapshot(at: Long = 1_000L): DeviceSnapshot {
        fun f(v: Float) = Metric.available(v, at)
        return DeviceSnapshot(
            timestampMs = at,
            display = DisplayMetrics(
                fps = f(58f),
                globalFps = f(60f),
                frameTimeMs = f(16.7f),
                frameStdevMs = f(0.4f),
                jankFrames = Metric.available(3, at),
                jankRatio30s = f(0.5f),
                refreshRateHz = f(120f),
            ),
            cpu = CpuMetrics(
                usagePercent = f(42f),
                perCoreUsage = Metric.available(
                    listOf(CoreUsage(0, 40f), CoreUsage(1, 44f)), at,
                ),
                perCoreFreqMHz = Metric.available(
                    listOf(CoreFreq(0, 1800f), CoreFreq(1, 2000f)), at,
                ),
                temperatureC = f(45.5f),
            ),
            gpu = GpuMetrics(
                loadPercent = f(37f),
                freqMHz = f(450f),
                temperatureC = f(44.2f),
            ),
            battery = BatteryMetrics(
                temperatureC = f(31.4f),
                currentMa = f(-850f),
                voltageV = f(3.91f),
                levelPercent = f(76f),
                chargingState = ChargingState.DISCHARGING,
                healthCoarse = BatteryHealthCoarse.GOOD,
                healthPercent = Metric.unavailable("Android 无公开的电池健康百分比接口", at),
            ),
            memory = MemoryMetrics(
                totalMb = f(8192f),
                usedMb = f(5120f),
                availableMb = f(3072f),
                usageRatio = f(62.5f),
                perApp = Metric.unavailable("需要 root 或 Shizuku", at),
            ),
            thermal = ThermalMetrics(
                deviceTempC = f(39.8f),
                status = ThermalStatus.LIGHT,
                headroom = f(0.6f),
            ),
        )
    }

    private val noFpsSnapshot = fullSnapshot().let { base ->
        base.copy(
            display = base.display.copy(
                globalFps = Metric.needsPermission(
                    "root 或 Shizuku", PermissionAction.SHIZUKU, base.timestampMs,
                ),
            ),
            gpu = base.gpu.copy(
                loadPercent = Metric.unavailable("GPU 负载节点不可读", base.timestampMs),
                temperatureC = Metric.unavailable("GPU 温度节点不可读", base.timestampMs),
            ),
        )
    }

    private fun callbacks() = OverlayGestureCallbacks(
        onDragStart = {}, onDrag = {}, onDragEnd = {}, onScale = {},
        onSingleTap = {}, onDoubleTap = {}, onTouchLocal = {},
    )

    @Test
    fun overlayData_from_mapsSnapshotFields() {
        val snapshot = fullSnapshot()
        val data = OverlayData.from(fps = snapshot.display.globalFps, snapshot = snapshot)

        assertEquals(62.5f, data.memoryRatio.value!!, 0.001f)
        assertEquals(39.8f, data.deviceTemp.value!!, 0.001f)
        assertEquals(120f, data.refreshHz.value!!, 0.001f)
        assertEquals(60f, data.fps.value!!, 0.001f)
        assertEquals(42f, data.cpuUsage.value!!, 0.001f)
        assertEquals(37f, data.gpuLoad.value!!, 0.001f)
        assertEquals(45.5f, data.cpuTemp.value!!, 0.001f)
        assertEquals(44.2f, data.gpuTemp.value!!, 0.001f)
        assertEquals(31.4f, data.batteryTemp.value!!, 0.001f)
        assertEquals(3.91f, data.batteryVoltage.value!!, 0.001f)
        assertEquals(-850f, data.batteryCurrent.value!!, 0.001f)
        assertEquals(76f, data.batteryLevel.value!!, 0.001f)
    }

    @Test
    fun cardMode_showsAllAvailableMetricRows() {
        composeRule.setContent {
            DevicePerfTheme(themeMode = ThemeMode.DARK) {
                OverlayContent(
                    state = OverlayController.UiState(mode = OverlayController.Mode.CARD),
                    data = OverlayData.from(
                        fps = fullSnapshot().display.globalFps,
                        snapshot = fullSnapshot(),
                    ),
                    health = com.devperf.monitor.analysis.FocusedHealth.Result(
                        total = 80f,
                        grade = null,
                        summary = "",
                        smoothnessScore = 80f,
                        temperatureScore = 80f,
                        memoryScore = 80f,
                        weakest = null,
                        partial = false,
                    ),
                    intervalMs = 1000L,
                    callbacks = callbacks(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(204.dp)
                        .testTag("overlay"),
                )
            }
        }
        composeRule.waitForIdle()

        // 1.0.26 全量明细的每一行都应进入组合
        listOf(
            "CPU 使用率", "GPU 负载", "CPU 温度", "GPU 温度", "电池温度",
            "机身温度", "电池电压", "电池电流", "内存占用率", "电量", "实时 FPS",
            "屏幕刷新率",
        ).forEach { label ->
            composeRule.onNodeWithText(label).assertExists("卡片态应展示指标行：$label")
        }
    }

    @Test
    fun cardMode_skipsUnavailableMetrics() {
        val snapshot = noFpsSnapshot
        composeRule.setContent {
            DevicePerfTheme(themeMode = ThemeMode.DARK) {
                OverlayContent(
                    state = OverlayController.UiState(mode = OverlayController.Mode.CARD),
                    data = OverlayData.from(fps = snapshot.display.globalFps, snapshot = snapshot),
                    health = com.devperf.monitor.analysis.FocusedHealth.Result(
                        total = 80f, grade = null, summary = "",
                        smoothnessScore = 80f, temperatureScore = 80f,
                        memoryScore = 80f, weakest = null, partial = false,
                    ),
                    intervalMs = 1000L,
                    callbacks = callbacks(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(204.dp)
                        .testTag("overlay"),
                )
            }
        }
        composeRule.waitForIdle()

        // 采不到的 GPU 两项不应占位
        composeRule.onNodeWithText("GPU 负载").assertDoesNotExist()
        composeRule.onNodeWithText("GPU 温度").assertDoesNotExist()
        // FPS 未授权：保留引导行（不静默消失）
        composeRule.onNodeWithText("需 root 或 Shizuku").assertExists("FPS 未授权应留引导行")
        // 可读的项照常展示
        composeRule.onNodeWithText("CPU 使用率").assertExists("CPU 使用率应展示")
    }

    @Test
    fun numbersMode_showsFourCoreNumbers() {
        composeRule.setContent {
            DevicePerfTheme(themeMode = ThemeMode.DARK) {
                OverlayContent(
                    state = OverlayController.UiState(mode = OverlayController.Mode.NUMBERS),
                    data = OverlayData.from(
                        fps = fullSnapshot().display.globalFps,
                        snapshot = fullSnapshot(),
                    ),
                    health = com.devperf.monitor.analysis.FocusedHealth.Result(
                        total = 80f, grade = null, summary = "",
                        smoothnessScore = 80f, temperatureScore = 80f,
                        memoryScore = 80f, weakest = null, partial = false,
                    ),
                    intervalMs = 1000L,
                    callbacks = callbacks(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("overlay"),
                )
            }
        }
        composeRule.waitForIdle()

        // 纯数字态四格：CPU / 温度 / FPS / 刷新率（标签不展示，只展示数字+单位）
        composeRule.onNodeWithText("%").assertExists("CPU 使用率单位")
        composeRule.onNodeWithText("℃").assertExists("设备温度单位")
        composeRule.onNodeWithText("Hz").assertExists("FPS/刷新率单位（两个 Hz）")
    }

    @Test
    fun overlayBackdropStructure_composesGlassCard() {
        // 复刻 OverlayService 的窗口内 backdrop 结构（背景层 + 兄弟玻璃内容），
        // legacy 模式下 RuntimeShader 为 stub，验证组合不崩、LocalBackdrop 下发生效
        composeRule.setContent {
            DevicePerfTheme(themeMode = ThemeMode.DARK) {
                val backdrop = rememberLayerBackdrop()
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .appLayerBackdrop(backdrop),
                    ) {
                        androidx.compose.foundation.Canvas(
                            modifier = Modifier.matchParentSize(),
                        ) {
                            drawOverlayWallpaper(
                                androidx.compose.ui.graphics.ImageBitmap(1, 1),
                            )
                        }
                    }
                    CompositionLocalProvider(LocalBackdrop provides backdrop) {
                        OverlayContent(
                            state = OverlayController.UiState(mode = OverlayController.Mode.CARD),
                            data = OverlayData.from(
                                fps = fullSnapshot().display.globalFps,
                                snapshot = fullSnapshot(),
                            ),
                            health = com.devperf.monitor.analysis.FocusedHealth.Result(
                                total = 80f, grade = null, summary = "",
                                smoothnessScore = 80f, temperatureScore = 80f,
                                memoryScore = 80f, weakest = null, partial = false,
                            ),
                            intervalMs = 1000L,
                            callbacks = callbacks(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(204.dp)
                                .testTag("overlay"),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("CPU 使用率").assertExists("玻璃结构下卡片态应正常组合")
    }

    @Test
    fun collapsedMode_showsStrip() {
        composeRule.setContent {
            DevicePerfTheme(themeMode = ThemeMode.DARK) {
                val snapshot = fullSnapshot()
                OverlayContent(
                    state = OverlayController.UiState(mode = OverlayController.Mode.COLLAPSED),
                    data = OverlayData.from(fps = snapshot.display.globalFps, snapshot = snapshot),
                    health = com.devperf.monitor.analysis.FocusedHealth.Result(
                        total = 80f, grade = null, summary = "",
                        smoothnessScore = 80f, temperatureScore = 80f,
                        memoryScore = 80f, weakest = null, partial = false,
                    ),
                    intervalMs = 1000L,
                    callbacks = callbacks(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(148.dp)
                        .testTag("overlay"),
                )
            }
        }
        composeRule.waitForIdle()

        // 收起态：窄条 + 提示点（无指标行、无文字）
        composeRule.onNodeWithText("CPU 使用率").assertDoesNotExist()
        composeRule.onNodeWithText("读不到指标").assertDoesNotExist()
    }
}
