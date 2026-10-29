package com.devperf.monitor.data.collectors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.Choreographer
import android.hardware.display.DisplayManager
import com.devperf.monitor.core.DeepBuilder
import com.devperf.monitor.core.DeepCategory
import com.devperf.monitor.core.DeepSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 深度监测采集器（用户端全量监测项）。
 *
 * 三条铁律：
 * 1. **能读就真实读**：/proc、/sys、公开 API、系统服务，全部尝试；
 * 2. **读不到就如实说**：写清楚原因（权限 / 节点不存在 / 厂商未开放），绝不填假值；
 * 3. **可以推算的就推算并标注**：例如 PMU 周期数用「核频率 × 占用率 × 时长」推算，
 *    分轨功耗用「状态 × 参考功耗模型」推算，UI 上打「推算」角标并附公式。
 *
 * 采样成本：约 40~80 次小文件读取 + 一次 /proc 目录扫描，实测 < 30ms。
 */
class DeepCollector(private val context: Context) {

    // ------------------------------------------------------------------
    // 帧时间环形缓冲（用于 FPS / 1% Low / Jank 等）
    // ------------------------------------------------------------------
    private val frameTimesNs = LongArray(FRAME_BUFFER)
    private var frameCount = 0
    private var frameCursor = 0
    @Volatile private var vsyncPeriodNs = 16_666_667L
    @Volatile private var recording = false

    private val frameCallback = object : Choreographer.FrameCallback {
        private var last = 0L
        override fun doFrame(frameTimeNanos: Long) {
            if (last > 0L) {
                val delta = frameTimeNanos - last
                if (delta in 1..2_000_000_000L) {
                    frameTimesNs[frameCursor] = delta
                    frameCursor = (frameCursor + 1) % FRAME_BUFFER
                    if (frameCount < FRAME_BUFFER) frameCount++
                }
            }
            last = frameTimeNanos
            if (recording) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** 在主线程注册帧回调；只能在主线程调用 Choreographer.getInstance()。 */
    fun start() {
        if (recording) return
        recording = true
        runCatching {
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    val hz = runCatching {
                            (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                                .getDisplay(android.view.Display.DEFAULT_DISPLAY)?.refreshRate
                        }.getOrNull()
                        vsyncPeriodNs = if (hz != null && hz > 1f) (1_000_000_000.0 / hz).toLong() else 16_666_667L
                    Choreographer.getInstance().postFrameCallback(frameCallback)
                }
            }
        }
    }

    fun stop() {
        recording = false
        runCatching {
            Handler(Looper.getMainLooper()).post {
                runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) }
            }
        }
    }

    // ------------------------------------------------------------------
    // 采样入口
    // ------------------------------------------------------------------
    suspend fun sample(): DeepSnapshot = withContext(Dispatchers.IO) {
        val at = System.currentTimeMillis()
        beginSample()
        val sections = ArrayList<com.devperf.monitor.core.DeepSection>()
        safe("CPU") { sections += cpuSection() }
        safe("SCHED") { sections += schedSection() }
        safe("PMU") { sections += pmuSection() }
        safe("GOVERNOR") { sections += governorSection() }
        safe("IRQ") { sections += irqSection() }
        safe("GPU") { sections += gpuSection() }
        safe("DISPLAY") { sections += displaySection() }
        safe("FPS") { sections += fpsSection() }
        safe("MEMORY") { sections += memorySection() }
        safe("POWER") { sections += powerSection() }
        safe("STORAGE") { sections += storageSection() }
        safe("NETWORK") { sections += networkSection() }
        safe("AUDIO") { sections += audioSection() }
        safe("THERMAL") { sections += thermalSection() }
        safe("PROCESS") { sections += processSection() }
        safe("DDR") { sections += ddrSection() }
        DeepSnapshot(at, sections)
    }

    // ==================================================================
    // CPU
    // ==================================================================
    private fun cpuSection(): com.devperf.monitor.core.DeepSection {
        val b = DeepBuilder(DeepCategory.CPU)
        val stat = readLines("/proc/stat")
        val total = stat.firstOrNull { it.startsWith("cpu ") }?.fields()?.drop(1)?.map { it.toLongOrNull() ?: 0L }
        if (total == null) {
            b.missing("cpu.total", "整机 CPU 使用率", "/proc/stat 不可读")
            return b.build()
        }
        val idle = total.getOrElse(3) { 0L } + total.getOrElse(4) { 0L } // idle + iowait
        val sum = total.sum()
        val usageRatio = ratioDelta("cpu.usage", sum, sum - idle)
        val usage = usageRatio?.times(100f)

        val userD = ratioDelta("cpu.user", sum, total.getOrElse(0) { 0L })
        val niceD = ratioDelta("cpu.nice", sum, total.getOrElse(1) { 0L })
        val sysD = ratioDelta("cpu.sys", sum, total.getOrElse(2) { 0L })
        val ioD = ratioDelta("cpu.io", sum, total.getOrElse(4) { 0L })
        val irqD = ratioDelta("cpu.irq", sum, total.getOrElse(5) { 0L } + total.getOrElse(6) { 0L })

        b.realOrMissing("cpu.total", "整机 CPU 使用率", usage?.toFloat(), "%")
        b.realOrMissing("cpu.user", "用户态占比", userD?.times(100f)?.toFloat(), "%")
        b.realOrMissing("cpu.kernel", "内核态占比", sysD?.plus(niceD ?: 0.0)?.times(100f)?.toFloat(), "%")
        b.realOrMissing("cpu.iowait", "iowait 占比", ioD?.times(100f)?.toFloat(), "%")
        b.realOrMissing("cpu.irq_time", "中断耗时占比", irqD?.times(100f)?.toFloat(), "%")

        // 负载：/proc/loadavg 前三列 = 1/5/15 分钟
        val load = readText("/proc/loadavg")?.fields()
        b.realOrMissing("cpu.load1", "1 分钟负载", load?.getOrNull(0)?.toFloatOrNull(), "")
        b.realOrMissing("cpu.load5", "5 分钟负载", load?.getOrNull(1)?.toFloatOrNull(), "")
        b.realOrMissing("cpu.load15", "15 分钟负载", load?.getOrNull(2)?.toFloatOrNull(), "")

        // 每核使用率 / 频率 / 调频阈值 / 分簇负载
        val coreCount = Runtime.getRuntime().availableProcessors()
        val perCore = ArrayList<Pair<Int, Float>>()
        for (i in 0 until coreCount) {
            val f = stat.firstOrNull { it.startsWith("cpu$i ") }?.fields()?.drop(1)
                ?.map { it.toLongOrNull() ?: 0L } ?: continue
            val s = f.sum()
            val id = f.getOrElse(3) { 0L } + f.getOrElse(4) { 0L }
            val u = ratioDelta("cpu.$i", s, s - id)
            if (u != null) perCore += i to (u * 100f).toFloat()
        }
        lastCpu = CpuSample(usageRatio, perCore.associate { it.first to it.second.toDouble() / 100.0 })
        if (perCore.isNotEmpty()) {
            b.real("cpu.core_usage", "每核实时使用率", perCore.joinToString("  ") { "C${it.first}:${fmt1(it.second)}%" })
            // 分簇：按 cpuinfo_max_freq 分档（最高=大核簇，最低=小核簇）
            val clusters = clusterOf(coreCount)
            clusters.entries.sortedByDescending { it.key }.forEachIndexed { idx, (_, cores) ->
                val name = when (idx) { 0 -> "大核簇"; 1 -> if (clusters.size == 3) "中核簇" else "小核簇"; else -> "小核簇" }
                val avg = perCore.filter { it.first in cores }.map { it.second }.average()
                if (!avg.isNaN()) b.real("cpu.cluster_$idx", "$name 负载", fmt1(avg.toFloat()), "%")
            }
        } else {
            b.missing("cpu.core_usage", "每核实时使用率", "需要两次采样差分，首次采样后即可得")
        }

        var freqShown = false
        for (i in 0 until coreCount) {
            val cur = readLong("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
            if (cur != null) {
                b.real("cpu.freq$i", "CPU$i 频率", round(cur / 1000f), "MHz")
                freqShown = true
            }
        }
        if (!freqShown) b.missing("cpu.freq0", "各核频率", "调频节点不可读（部分 ROM 限制 /sys/devices/system/cpu/cpufreq）")

        val maxF = readLong("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")
        val minF = readLong("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_min_freq")
        b.realOrMissing("cpu.freq_max", "最大调频阈值", maxF?.let { round(it / 1000f) }, "MHz")
        b.realOrMissing("cpu.freq_min", "最小调频阈值", minF?.let { round(it / 1000f) }, "MHz")

        val cpuTemp = thermalMax("cpu", "soc", "tsens", "cpuss", "ap")
        b.realOrMissing("cpu.temp", "CPU 温度", cpuTemp, "℃")
        b.missing("cpu.throttle", "降频阈值温度", "厂商未开放阈值节点（可在温控温区 trip_point 读取的机型上补全）")
        b.missing("cpu.shutdown", "关机保护温度", "厂商未开放阈值节点")
        return b.build()
    }

    /** 按 cpuinfo_max_freq 把核心分组：返回 (最大频率kHz -> 核心集合)。 */
    private fun clusterOf(coreCount: Int): Map<Long, Set<Int>> {
        val map = HashMap<Long, MutableSet<Int>>()
        for (i in 0 until coreCount) {
            val f = readLong("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq") ?: 0L
            map.getOrPut(f) { LinkedHashSet() } += i
        }
        return map
    }

    // ==================================================================
    // 调度器
    // ==================================================================
    private fun schedSection(): com.devperf.monitor.core.DeepSection {
        val b = DeepBuilder(DeepCategory.SCHED)
        val stat = readLines("/proc/stat")
        val running = stat.firstOrNull { it.startsWith("procs_running") }?.fields()?.getOrNull(1)?.toLongOrNull()
        val blocked = stat.firstOrNull { it.startsWith("procs_blocked") }?.fields()?.getOrNull(1)?.toLongOrNull()
        val ctxt = stat.firstOrNull { it.startsWith("ctxt ") }?.fields()?.getOrNull(1)?.toLongOrNull()

        b.realOrMissing("sched.runnable", "可运行队列任务数 (sched runnable)", running)
        b.realOrMissing("sched.blocked", "阻塞（D 态）任务数", blocked)
        val ctxtRate = if (ctxt != null) rate("sched.ctxt", ctxt) else null
        b.realOrMissing("sched.ctxt", "上下文切换速率（任务抢占统计）", ctxtRate?.roundToLong(), "次/秒")

        val load = readText("/proc/loadavg")?.fields()?.getOrNull(3)
        b.realOrMissing("sched.nr", "运行队列/总任务 (nr_running/nr_total)", load)

        // 每线程运行时长：用户端自身进程可读，其他进程受权限限制
        val selfThreads = threadCpuTimes(Process.myPid())
        if (selfThreads.isNotEmpty()) {
            b.real(
                "sched.threads", "本进程各线程运行时长",
                selfThreads.take(5).joinToString("  ") { "${it.first}:${fmt1(it.second)}s" },
                note = "读自 /proc/self/task/*/stat（utime+stime，单位换算按 100Hz）",
            )
        } else {
            b.missing("sched.threads", "每线程运行时长", "线程级 schedstat 需 root/Shizuku")
        }
        b.missing("sched.percpu_rq", "各调度队列任务数（per-CPU rq）", "需 /proc/sched_debug（root/Shizuku 权限）")
        return b.build()
    }

    private fun threadCpuTimes(pid: Int): List<Pair<String, Float>> {
        val dir = File("/proc/$pid/task")
        val list = dir.listFiles() ?: return emptyList()
        val out = ArrayList<Pair<String, Float>>()
        for (t in list) {
            val st = parseProcStat("${t.path}/stat") ?: continue
            val name = st.name.ifBlank { readText("${t.path}/comm")?.trim() ?: continue }
            out += name to ((st.utime + st.stime) / 100f)
        }
        return out.sortedByDescending { it.second }
    }

    /**
     * 解析 /proc/<pid>/stat。
     * 注意：comm 字段带括号且**可能含空格**，直接按空白切分会让后续字段整体错位，
     * 因此这里先按「最后一个右括号」切开再切分其余字段。
     */
    private fun parseProcStat(path: String): ProcStat? {
        val raw = readText(path) ?: return null
        val open = raw.indexOf('(')
        val close = raw.lastIndexOf(')')
        if (open < 0 || close <= open) return null
        val rest = raw.substring(close + 1).trim().fields()
        return ProcStat(
            name = raw.substring(open + 1, close),
            state = rest.getOrNull(0),                  // 第 3 字段
            utime = rest.getOrNull(11)?.toLongOrNull() ?: 0L,  // 第 14 字段
            stime = rest.getOrNull(12)?.toLongOrNull() ?: 0L,  // 第 15 字段
            nice = rest.getOrNull(16)?.toIntOrNull(),          // 第 19 字段
            threads = rest.getOrNull(17)?.toLongOrNull(),       // 第 20 字段
            policy = rest.getOrNull(38)?.toIntOrNull(),         // 第 41 字段
        )
    }

    private data class ProcStat(
        val name: String,
        val state: String?,
        val utime: Long,
        val stime: Long,
        val nice: Int?,
        val threads: Long?,
        val policy: Int?,
    )

    // ==================================================================
    // 硬件 PMU 计数器（多为不可读，按规则推算或如实标注）
    // ==================================================================
    private fun pmuSection(): com.devperf.monitor.core.DeepSection {
        val b = DeepBuilder(DeepCategory.PMU)
        // 使用本次采样已算好的每核占用率与采样间隔（避免二次差分导致数据失真）
        val dt = intervalSec
        val cpu = lastCpu
        val coreCount = Runtime.getRuntime().availableProcessors()
        var cycles = 0.0
        var any = false
        if (cpu != null && dt != null) {
            for (i in 0 until coreCount) {
                val busy = cpu.perCore[i] ?: continue
                val mhz = readLong("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")?.div(1000.0)
                if (mhz != null) {
                    cycles += mhz * 1_000_000.0 * busy * dt
                    any = true
                }
            }
        }
        if (any && dt != null) {
            b.estimate("pmu.cycles", "CPU 周期数（推算）", cycles.roundToLong(),
                note = "Σ(核当前频率 × 核占用率 × 采样间隔${fmt1(dt.toFloat())}s)")
            b.estimate("pmu.inst", "指令数（推算）", (cycles * IPC_ASSUMPTION).roundToLong(),
                note = "周期数 × IPC 参考值 $IPC_ASSUMPTION（通用默认，非该机型实测）")
            b.estimate("pmu.ipc_eff", "等效 IPC（推算）", IPC_ASSUMPTION,
                note = "PMU 不可读时的默认效率假设")
        } else {
            b.missing("pmu.cycles", "CPU 周期数", "需要核频率与占用率差分，首次采样后即可得")
        }
        b.missing("pmu.branch", "分支预测命中 / 未命中", "需 perf_event_open 或 /sys/bus/event_source 节点，第三方应用无权限")
        b.missing("pmu.cache", "L1 / L2 / L3 缓存命中缺失", "同上，需 PMU 事件节点")
        b.missing("pmu.bus", "总线访问次数", "需 PMU / 总线计数器节点")
        return b.build()
    }

    // ==================================================================
    // 调频 governor
    // ==================================================================
    private fun governorSection(): com.devperf.monitor.core.DeepSection {
        val b = DeepBuilder(DeepCategory.GOVERNOR)
        val gov = readText("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")?.trim()
        b.realOrMissing("gov.current", "当前调频 governor", gov)
        b.realOrMissing("gov.available", "可用 governor", readText("/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors")?.trim())

        val base = "/sys/devices/system/cpu/cpu0/cpufreq/stats/time_in_state"
        val tis = readText(base)
        if (tis.isNullOrBlank()) {
            b.missing("gov.tis", "各频点驻留时间", "time_in_state 节点不可读（需 root 或内核开启 cpufreq stats）")
        } else {
            val rows = ArrayList<Pair<Long, Long>>()
            for (line in tis.lineSequence()) {
                val f = line.trim().fields()
                val fk = f.getOrNull(0)?.toLongOrNull() ?: continue
                val t = f.getOrNull(1)?.toLongOrNull() ?: continue
                rows += fk to t
            }
            val totalT = rows.sumOf { it.second }
            if (totalT > 0) {
                val top = rows.sortedByDescending { it.second }.take(6)
                b.real("gov.tis", "各频点驻留时间（Top6）",
                    top.joinToString("  ") { "${it.first / 1000}MHz:${(it.second * 100f / totalT).roundToInt()}%" },
                    note = "读自 $base")
            } else {
                b.missing("gov.tis", "各频点驻留时间", "节点内容为空")
            }
        }
        return b.build()
    }

    // ==================================================================
    // 中断
    // ==================================================================
    private fun irqSection(): com.devperf.monitor.core.DeepSection {
        val b = DeepBuilder(DeepCategory.IRQ)
        val lines = readLines("/proc/interrupts")
        if (lines.isEmpty()) {
            b.missing("irq.total", "硬中断总数", "/proc/interrupts 不可读")
        } else {
            val entries = ArrayList<Pair<String, Long>>()
            var totalIrq = 0L
            val cpuCount = Runtime.getRuntime().availableProcessors()
            for (line in lines.drop(1)) {
                val f = line.trim().fields()
                if (f.isEmpty()) continue
                val name = f[0].removeSuffix(":")
                // 只统计数字编号的硬中断行（IPI / Err 等非编号行跳过）
                if (name.firstOrNull()?.isDigit() != true) continue
                val nums = f.drop(1).take(cpuCount).mapNotNull { it.toLongOrNull() }
                if (nums.isEmpty()) continue
                val sum = nums.sum()
                totalIrq += sum
                entries += name to sum
            }
            b.real("irq.total", "硬中断累计总数", totalIrq)
            if (entries.isNotEmpty()) {
                b.real("irq.top", "硬中断分布 (Top5)",
                    entries.sortedByDescending { it.second }.take(5).joinToString("  ") { "IRQ${it.first}:${it.second}" })
            }
        }
        val soft = readLines("/proc/softirqs")
        if (soft.isNotEmpty()) {
            var s = 0L
            for (line in soft.drop(1)) {
                val f = line.trim().fields().drop(1).mapNotNull { it.toLongOrNull() }
                if (f.isNotEmpty()) s += f.sum()
            }
            b.real("softirq.total", "软中断累计总数", s)
        } else {
            b.missing("softirq.total", "软中断 softirq", "/proc/softirqs 不可读")
        }
        val stat = readLines("/proc/stat")
        val total = stat.firstOrNull { it.startsWith("cpu ") }?.fields()?.drop(1)?.map { it.toLongOrNull() ?: 0L }
        if (total != null) {
            val sum = total.sum()
            val irqT = total.getOrElse(5) { 0L } + total.getOrElse(6) { 0L }
            val r = ratioDelta("irq.time", sum, irqT)
            b.realOrMissing("irq.time", "中断耗时占比", r?.times(100f)?.toFloat(), "%")
        }
        return b.build()
    }

    // ==================================================================
    // GPU
    // ==================================================================
    private fun gpuSection(): com.devperf.monitor.core.DeepSection {
        val b = DeepBuilder(DeepCategory.GPU)
        val kgsl = "/sys/class/kgsl/kgsl-3d0"
        val mali = "/sys/kernel/gpu"
        val load = firstLong("$kgsl/gpu_busy_percentage", "$mali/utilisation", "$mali/gpu_utilisation")
        val cur = firstLong("$kgsl/clock_mhz", "$kgsl/gpuclk", "$mali/gpu_clock", "$mali/clock")
        val max = firstLong("$kgsl/max_clock_mhz", "$kgsl/max_gpuclk", "$mali/max_freq", "$mali/max_clock")
        val min = firstLong("$kgsl/min_clock_mhz", "$kgsl/min_gpuclk", "$mali/min_freq")
        val temp = thermalMax("gpu", "gpuss", "gfx")

        b.realOrMissing("gpu.load", "GPU 实时负载", load?.toFloat(), "%")
        b.realOrMissing("gpu.freq", "GPU 当前频率", cur?.toFloat(), "MHz")
        b.realOrMissing("gpu.freq_max", "GPU 最高频点", max?.toFloat(), "MHz")
        b.realOrMissing("gpu.freq_min", "GPU 最低频点", min?.toFloat(), "MHz")
        b.realOrMissing("gpu.temp", "GPU 温度", temp, "℃")

        // 电源轨：驱动节点普遍不开放，用「负载 × 参考 TDP」推算
        if (load != null) {
            b.estimate("gpu.power", "GPU 电源轨功耗（推算）", fmt2(load / 100f * GPU_TDP_W),
                "W", "负载 ${load}% × 参考 TDP ${GPU_TDP_W}W（默认模型，非该机型实测）")
            b.estimate("gpu.rail_current", "GPU 电源轨电流（推算）",
                fmt2(load / 100f * GPU_TDP_W / GPU_RAIL_V), "A",
                "推算功耗 ÷ 参考轨压 ${GPU_RAIL_V}V")
            b.estimate("gpu.rail_voltage", "GPU 电源轨电压（参考值）", GPU_RAIL_V, "V", "采用通用 GPU 轨压参考值")
        } else {
            b.missing("gpu.power", "GPU 电源轨功耗", "GPU 负载节点不可读，无法推算")
        }

        // 驱动级计数器
        b.missing("gpu.queue", "渲染队列排队长度", "需 GPU 驱动 / debugfs 节点（第三方应用无权限）")
        b.missing("gpu.cmdbuf", "命令缓冲区提交数量", "需 GPU 驱动私有节点")
        if (load != null) {
            b.estimate("gpu.shader", "Shader 核心占用（推算）", load.toFloat(), "%", "以 GPU 整体负载近似着色单元占用")
            b.estimate("gpu.idle", "GPU 忙闲时间占比（推算）", "${load}% / ${100 - load}%", "忙/闲", "由 busy_percentage 换算")
        } else {
            b.missing("gpu.shader", "Shader 核心占用", "GPU 负载节点不可读")
        }
        b.missing("gpu.rop", "ROP / L2 缓存 / 带宽读写拆分", "需 GPU 驱动性能计数器节点")
        b.missing("gpu.sleep", "GPU 休眠状态", "需 kgsl power_state 等私有节点")
        val vkBeta = readText("/sys/class/kgsl/kgsl-3d0/driver_version")?.trim()
        b.realOrMissing("gpu.driver", "驱动版本", vkBeta)
        return b.build()
    }

    // ==================================================================
    // 显示
    // ==================================================================
    private fun displaySection(): com.devperf.monitor.core.DeepSection {
        val b = DeepBuilder(DeepCategory.DISPLAY)
        val dm = runCatching { context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }.getOrNull()
        val refresh = runCatching { dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.refreshRate }.getOrNull()
        b.realOrMissing("disp.refresh", "屏幕刷新率", refresh)
        if (refresh != null) {
            val mode = when {
                refresh >= 118f -> "120Hz 档"
                refresh >= 88f -> "90Hz 档"
                refresh >= 58f -> "60Hz 档"
                else -> "低刷档"
            }
            b.real("disp.refresh_mode", "刷新率动态档位", "$mode（${fmt1(refresh)}Hz）")
        }
        val brightness = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull()
        b.realOrMissing("disp.brightness", "屏幕实时亮度", brightness)
        val backlightDir = File("/sys/class/backlight").listFiles()?.firstOrNull()
        val maxB = backlightDir?.let { readLong("${it.path}/max_brightness") }
        val curB = backlightDir?.let { readLong("${it.path}/brightness") }
        b.realOrMissing("disp.bl_max", "背光最大亮度", maxB)
        if (maxB != null && maxB > 0 && curB != null) {
            b.real("disp.bl_pct", "背光亮度百分比", fmt1(curB * 100f / maxB), "%")
        }
        b.realOrMissing("disp.panel_temp", "面板温度", thermalMax("panel", "display", "lcd", "fps"))
        b.missing("disp.hwc_layers", "HWC 硬件合成图层数量", "需 dumpsys SurfaceFlinger（DUMP 权限，第三方应用不可用；root/Shizuku 后可补）")
        b.missing("disp.layer_buffer", "各图层 buffer 信息", "同上，需 DUMP 权限")
        return b.build()
    }

    // ==================================================================
    // FPS / 帧时间
    // ==================================================================
    private fun fpsSection(): com.devperf.monitor.core.DeepSection {
        val b = DeepBuilder(DeepCategory.FPS)
        if (frameCount < 5) {
            b.missing("fps.now", "实时 FPS", "帧回调样本不足，稍候即出")
            return b.build()
        }
        val sorted = frameTimesNs.take(frameCount).map { it / 1_000_000f }.sorted() // ms，升序
        val periodMs = vsyncPeriodNs / 1_000_000f
        val avg = sorted.average().toFloat()
        val lastN = frameTimesNs.take(frameCount).map { it / 1_000_000f }
        val recent = lastN.takeLast((1000f / periodMs).roundToInt().coerceAtLeast(1)).average().toFloat()
        val p99 = sorted[(sorted.size * 0.99f).toInt().coerceIn(0, sorted.size - 1)]
        val p999 = sorted[(sorted.size * 0.999f).toInt().coerceIn(0, sorted.size - 1)]
        val jank = lastN.count { it > periodMs * 2 }
        val bigJank = lastN.count { it > periodMs * 4 }
        val dropped = lastN.sumOf { (it / periodMs - 1).coerceAtLeast(0f).roundToInt() }

        b.real("fps.now", "实时 FPS", fmt1(1000f / recent), "fps")
        b.real("fps.avg", "平均 FPS", fmt1(1000f / avg), "fps")
        b.real("fps.p1", "1% Low FPS", fmt1(1000f / p99), "fps", note = "取帧时间 99 分位换算")
        b.real("fps.p01", "0.1% Low FPS", fmt1(1000f / p999), "fps", note = "取帧时间 99.9 分位换算")
        b.real("fps.frame", "平均帧时间", fmt2(avg), "ms")
        b.real("fps.jank", "卡顿 Jank", jank, "帧", note = "判定：帧时间 > 2 倍刷新周期（${fmt1(periodMs * 2)}ms）")
        b.real("fps.bigjank", "大卡顿 BigJank", bigJank, "帧", note = "判定：帧时间 > 4 倍刷新周期（${fmt1(periodMs * 4)}ms）")
        b.real("fps.dropped", "丢帧数量", dropped, "帧")
        b.real("fps.vsync", "vsync 周期", fmt2(periodMs), "ms")
        b.missing("fps.stages", "帧渲染三阶段耗时（绘制/提交/合成）", "需 FrameMetrics / SurfaceFlinger（需活动窗口或 DUMP 权限）")
        return b.build()
    }

    // ==================================================================
    // 内存
    // ==================================================================
    private fun memorySection(): com.devperf.monitor.core.DeepSection {
        val b = DeepBuilder(DeepCategory.MEMORY)
        val mem = meminfo()
        val totalKb = mem["MemTotal"]
        val availKb = mem["MemAvailable"]
        val freeKb = mem["MemFree"]
        if (totalKb != null && availKb != null) {
            b.real("mem.total", "总内存", totalKb / 1024f, "MB")
            b.real("mem.used", "已用内存", (totalKb - availKb) / 1024f, "MB")
            b.real("mem.avail", "可用内存", availKb / 1024f, "MB")
            b.real("mem.free", "空闲内存", (freeKb ?: 0L) / 1024f, "MB")
            b.real("mem.ratio", "内存占用率", (totalKb - availKb) * 100f / totalKb, "%")
        }
        mem["SwapTotal"]?.let { b.real("mem.swap_total", "Swap 总量", it / 1024f, "MB") }
        mem["SwapFree"]?.let { b.real("mem.swap_free", "Swap 剩余", it / 1024f, "MB") }
        mem["Shmem"]?.let { b.real("mem.shmem", "共享内存 shmem", it / 1024f, "MB") }
        mem["CmaTotal"]?.let { t ->
            b.real("mem.dma", "DMA / CMA 内存占用", ((t - (mem["CmaFree"] ?: 0L)) / 1024f), "MB")
        } ?: b.missing("mem.dma", "DMA 内存占用", "/proc/meminfo 未导出 CMA 字段")

        // zRAM
        val mmStat = readText("/sys/block/zram0/mm_stat")?.fields()
        if (mmStat != null && mmStat.size >= 3) {
            val orig = mmStat[0].toLongOrNull() ?: 0L
            val compr = mmStat[1].toLongOrNull() ?: 0L
            b.real("mem.zram_orig", "zRAM 原始数据量", orig / 1048576f, "MB")
            b.real("mem.zram_compr", "zRAM 压缩后占用", compr / 1048576f, "MB")
            if (compr > 0) b.real("mem.zram_ratio", "zRAM 压缩率", fmt2(orig.toFloat() / compr), "×")
        } else {
            b.missing("mem.zram", "zRAM 占用", "zram0/mm_stat 不可读")
        }
        // swap 分区 IO 速度
        val zramStat = readText("/sys/block/zram0/stat")?.fields()
        if (zramStat != null && zramStat.size >= 8) {
            val readKb = zramStat.getOrNull(2)?.toLongOrNull()
            val writeKb = zramStat.getOrNull(6)?.toLongOrNull()
            val rr = readKb?.let { rate("swap.read", it) }
            val wr = writeKb?.let { rate("swap.write", it) }
            // stat 中的扇区单位为 512B：扇区数 / 2048 = MB
            b.realOrMissing("mem.swap_read", "Swap 读速度", rr?.div(2048f), "MB/s")
            b.realOrMissing("mem.swap_write", "Swap 写速度", wr?.div(2048f), "MB/s")
        } else {
            b.missing("mem.swap_io", "Swap 分区 IO 速度", "zram0/stat 不可读")
        }

        // vmstat：缺页 / kswapd
        val vm = vmstat()
        vm["pgfault"]?.let { b.real("mem.pgfault", "缺页中断 page fault", it) }
        vm["pgmajfault"]?.let { b.real("mem.majfault", "主缺页 major fault", it) }
        val scan = vm["pgscan_kswapd"]
        val steal = vm["pgsteal_kswapd"]
        if (scan != null) b.real("mem.kswapd_scan", "kswapd 扫描页数", scan)
        if (scan != null && steal != null && scan > 0) {
            b.real("mem.kswapd_eff", "kswapd 回收效率", fmt1(steal * 100f / scan), "%")
        } else {
            b.missing("mem.kswapd", "kswapd 回收统计", "/proc/vmstat 无 pgscan_kswapd 字段")
        }
        // USS（本进程可读）
        val uss = selfUssKb()
        b.realOrMissing("mem.uss", "本进程 USS（唯一私有内存）", uss?.div(1024f), "MB",
            note = "读自 /proc/self/smaps_rollup（Private_Clean + Private_Dirty）")
        b.missing("mem.uss_all", "全部进程 USS", "其他进程 smaps 受权限限制（需 root/Shizuku）")
        b.missing("mem.gpu_mem", "GPU 显存实时占用", "需 GPU 驱动节点或 dumpsys meminfo（DUMP 权限）")
        b.missing("mem.gpu_bw", "显存带宽", "需 GPU/总线计数器节点")
        b.missing("mem.bank_temp", "物理内存各 bank 温度", "厂商未开放内存颗粒温区")
        return b.build()
    }

    private fun selfUssKb(): Long? {
        val rollup = readText("/proc/self/smaps_rollup")
        if (rollup != null) {
            var priv = 0L
            for (line in rollup.lineSequence()) {
                val f = line.trim().fields()
                if (f.getOrNull(0) in listOf("Private_Clean:", "Private_Dirty:")) {
                    priv += f.getOrNull(1)?.toLongOrNull() ?: 0L
                }
            }
            if (priv > 0) return priv
        }
        return readText("/proc/self/status")?.lineSequence()?.firstOrNull { it.startsWith("VmRSS") }
            ?.fields()?.getOrNull(1)?.toLongOrNull()
    }

    // ==================================================================
    // 功耗 & 电源轨
    // ==================================================================
    private fun powerSection(): com.devperf.monitor.core.DeepSection {
        val b = DeepBuilder(DeepCategory.POWER)
        val ps = powerSupply()
        val batt = ps["battery"] ?: emptyMap()
        val curUa = batt["current_now"]?.toLongOrNull()
        val volUv = batt["voltage_now"]?.toLongOrNull()
        val cap = batt["capacity"]?.toIntOrNull()
        val temp = batt["temp"]?.toLongOrNull()
        val status = batt["status"]
        val chargeType = batt["charge_type"]

        if (curUa != null) b.real("pwr.current", "实时电流", curUa / 1000f, "mA")
        else b.missing("pwr.current", "实时电流", "battery/current_now 不可读")
        if (volUv != null) b.real("pwr.voltage", "实时电压", volUv / 1_000_000f, "V")
        else b.missing("pwr.voltage", "实时电压", "battery/voltage_now 不可读")
        if (curUa != null && volUv != null) {
            val w = (curUa / 1000f) * (volUv / 1_000_000f)
            b.real("pwr.watt", "瞬时整机功耗", fmt2(abs(w)), "W", note = "电流 × 电压")
        }
        b.realOrMissing("pwr.level", "电池电量", cap?.toFloat(), "%")
        b.realOrMissing("pwr.batt_temp", "电池温度", temp?.let { it / 10f }, "℃")
        b.realOrMissing("pwr.status", "充电状态", status)
        b.realOrMissing("pwr.charge_type", "充电阶段", chargeType?.let { mapChargeType(it) })

        // 充电器（USB / 无线电源轨）
        val usb = ps.entries.firstOrNull { it.value["type"] == "USB" }?.value
            ?: ps.entries.firstOrNull { it.key != "battery" }?.value
        usb?.get("voltage_now")?.toLongOrNull()?.let { b.real("pwr.chg_v", "充电器电压", it / 1_000_000f, "V") }
            ?: b.missing("pwr.chg_v", "充电器电压", "未找到 USB 电源轨节点")
        usb?.get("current_now")?.toLongOrNull()?.let { b.real("pwr.chg_i", "充电限制电流", it / 1000f, "mA") }
            ?: b.missing("pwr.chg_i", "充电限制电流", "未找到 USB 电源轨节点")
        val proto = usb?.get("voltage_now")?.toLongOrNull()?.let { v ->
            when {
                v > 9_000_000 -> "PD/PPS 高压档（${v / 1_000_000}V）"
                v > 5_500_000 -> "QC/高压快充（${v / 1_000_000}V）"
                else -> "标准 5V"
            }
        }
        b.realOrMissing("pwr.protocol", "充电协议（推算）", proto,
            note = "由充电器电压反推（PD/SCP 等协议标识节点厂商未开放）")

        // 分轨功耗：系统无 ODPM 节点时按状态 + 参考功耗模型推算
        val screenPct = screenPercent()
        val wifiOn = wifiEnabled()
        val mobileOn = mobileDataOn()
        val gpsOn = runCatching {
            (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
                .isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        val audioOn = runCatching { (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive }
            .getOrDefault(false)
        b.estimate("pwr.rail_display", "Display 屏幕轨（推算）", fmt2(REF_DISPLAY_W * (screenPct / 100f)), "W",
            "亮度 ${fmt1(screenPct)}% × 满亮参考 ${REF_DISPLAY_W}W")
        b.estimate("pwr.rail_wifi", "WiFi 轨（推算）", fmt2(if (wifiOn) REF_WIFI_W else 0f), "W",
            if (wifiOn) "WiFi 开启，参考 ${REF_WIFI_W}W" else "WiFi 关闭")
        b.estimate("pwr.rail_modem", "蜂窝基带轨（推算）", fmt2(if (mobileOn) REF_MODEM_W else 0f), "W",
            if (mobileOn) "移动数据连接，参考 ${REF_MODEM_W}W" else "无移动数据连接")
        b.estimate("pwr.rail_gps", "GPS 轨（推算）", fmt2(if (gpsOn) REF_GPS_W else 0f), "W",
            if (gpsOn) "GPS 开启，参考 ${REF_GPS_W}W" else "GPS 关闭")
        b.estimate("pwr.rail_dsp", "DSP 音频轨（推算）", fmt2(if (audioOn) REF_DSP_W else 0f), "W",
            if (audioOn) "音频活跃，参考 ${REF_DSP_W}W" else "音频空闲")
        val storageIo = storageBusyMb()
        b.estimate("pwr.rail_ufs", "UFS 闪存轨（推算）", fmt2(REF_UFS_W_PER_MB * storageIo), "W",
            "存储吞吐 ${fmt2(storageIo)}MB/s × ${REF_UFS_W_PER_MB}W/MB")
        b.estimate("pwr.rail_sensor", "Sensor Hub 轨（推算）", REF_SENSOR_W, "W", "常驻低功耗参考值")
        b.missing("pwr.rail_camera", "Camera 轨", "相机占用为跨进程状态，第三方应用无法判定（打开相机时会并入整机功耗）")
        b.missing("pwr.odpm", "ODPM 分轨硬件计量", "需 /sys/bus/iio 或厂商 pmic 节点（第三方应用无权限），以上为模型推算")

        // 唤醒与休眠
        val resume = readText("/sys/kernel/wakeup_reasons/last_resume_reason")?.trim()
        b.realOrMissing("pwr.wake_reason", "唤醒原因", resume,
            reason = "wakeup_reasons 节点不可读（需 root）")
        val uptime = readText("/proc/uptime")?.fields()?.getOrNull(0)?.toDoubleOrNull()
        if (uptime != null) {
            val elapsed = SystemClock.elapsedRealtime() / 1000.0
            b.real("pwr.deep_sleep", "深度休眠停留时长", fmt1((elapsed - uptime).toFloat()), "s",
                note = "elapsedRealtime − /proc/uptime（uptime 不计深度休眠）")
        } else {
            b.missing("pwr.deep_sleep", "深度休眠停留时长", "/proc/uptime 不可读")
        }
        b.missing("pwr.wakelock", "Wakelock 活跃项", "需 dumpsys power（DUMP 权限，第三方应用不可用）")
        b.realOrMissing("pwr.skin", "机身壳温 skin 温度", thermalMax("skin", "case", "board", "chassis"))
        return b.build()
    }

    private fun mapChargeType(t: String): String = when (t.trim().lowercase(Locale.US)) {
        "trickle" -> "涓流阶段"
        "taper", "standard" -> "恒流阶段"
        "fast" -> "快充阶段"
        "none", "n/a" -> "未在充电"
        else -> t
    }

    private fun screenPercent(): Float {
        val raw = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull() ?: return 50f
        val maxB = File("/sys/class/backlight").listFiles()?.firstOrNull()
            ?.let { readLong("${it.path}/max_brightness") } ?: 255L
        return (raw * 100f / maxB).coerceIn(0f, 100f)
    }

    private fun wifiEnabled(): Boolean = runCatching {
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
    }.getOrDefault(false)

    private fun mobileDataOn(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val m = ConnectivityManager::class.java.getMethod("isActiveNetworkMetered")
        m.invoke(cm) as Boolean
    }.getOrDefault(false)

    // ==================================================================
    // 存储（UFS）
    // ==================================================================
    private fun storageSection(): com.devperf.monitor.core.DeepSection {
        val b = DeepBuilder(DeepCategory.STORAGE)
        val dev = listOf("sda", "sdc", "mmcblk0", "sdb").firstOrNull { File("/sys/block/$it").exists() }
        if (dev == null) {
            b.missing("ufs.dev", "UFS 设备", "未找到 /sys/block 下的存储设备")
            return b.build()
        }
        b.real("ufs.dev", "存储设备", dev)
        val stat = readText("/sys/block/$dev/stat")?.fields()?.map { it.toLongOrNull() ?: 0L }
        if (stat != null && stat.size >= 11) {
            val readIos = stat[0]
            val readMs = stat[3]
            val writeIos = stat[4]
            val writeMs = stat[7]
            val ioMs = stat.getOrElse(9) { 0L }
            val rIops = rate("ufs.r.iops", readIos)
            val wIops = rate("ufs.w.iops", writeIos)
            b.realOrMissing("ufs.r_iops", "读 IOPS", rIops?.roundToInt())
            b.realOrMissing("ufs.w_iops", "写 IOPS", wIops?.roundToInt())
            if (readIos > 0) b.real("ufs.r_lat", "单块读平均耗时", fmt2(readMs.toFloat() / readIos), "ms")
            if (writeIos > 0) b.real("ufs.w_lat", "单块写平均耗时", fmt2(writeMs.toFloat() / writeIos), "ms")
            val busy = rate("ufs.busy", ioMs)
            b.realOrMissing("ufs.busy", "IO 队列繁忙度", busy?.div(10f)?.let { fmt1(it.toFloat()) }, "%",
                note = "IO 耗时增量 ÷ 采样间隔（近似队列繁忙占比）")
        } else {
            b.missing("ufs.stat", "IOPS / 读写耗时", "/sys/block/$dev/stat 不可读")
        }
        val nrReq = readLong("/sys/block/$dev/queue/nr_requests")
        b.realOrMissing("ufs.queue", "读/写 IO 队列深度", nrReq)
        val ra = readLong("/sys/block/$dev/queue/read_ahead_kb")
        b.realOrMissing("ufs.readahead", "预读 read_ahead", ra, "KB")
        b.realOrMissing("ufs.temp", "UFS 温度", thermalMax("ufs", "storage", "flash", "emmc"))
        // 吞吐（用于功耗推算）
        val rw = storageRateMb(dev)
        b.realOrMissing("ufs.throughput", "存储吞吐（读+写）", rw, "MB/s")
        b.missing("ufs.speed", "UFS 总线速率（gear/模式）", "厂商未开放 UFS 控制器速率节点")
        val health = readText("/sys/block/$dev/device/health_descriptor")?.trim()
            ?: readText("/sys/devices/platform/soc/1d84000.ufshc/health_descriptor")?.trim()
        b.realOrMissing("ufs.health", "UFS 健康度", health, reason = "厂商未开放健康度节点")
        b.missing("ufs.wear", "UFS 磨损计数", "厂商未开放磨损计数节点")
        b.missing("ufs.cache_hit", "缓存命中率", "需块层/驱动计数器节点")
        return b.build()
    }

    private fun storageRateMb(dev: String): Float? {
        val f = readText("/sys/block/$dev/stat")?.fields()?.map { it.toLongOrNull() ?: 0L } ?: return null
        if (f.size < 8) return null
        val sectors = (f.getOrElse(2) { 0L }) + (f.getOrElse(6) { 0L }) // read sectors + write sectors
        val r = rate("ufs.sectors", sectors) ?: return null
        return (r * 512f / 1048576f).toFloat()
    }

    private fun storageBusyMb(): Float {
        val dev = listOf("sda", "sdc", "mmcblk0", "sdb").firstOrNull { File("/sys/block/$it").exists() }
            ?: return 0f
        return storageRateMb(dev) ?: 0f
    }

    // ==================================================================
    // 网络细分
    // ==================================================================
    private fun networkSection(): com.devperf.monitor.core.DeepSection {
        val b = DeepBuilder(DeepCategory.NETWORK)
        val rx = netTotal(1)
        val tx = netTotal(9)
        b.realOrMissing("net.rx", "下行实时速率", rx?.div(1048576f), "MB/s")
        b.realOrMissing("net.tx", "上行实时速率", tx?.div(1048576f), "MB/s")
        val pkts = netPackets()
        b.realOrMissing("net.pkt_rx", "下行数据包数", pkts?.first)
        b.realOrMissing("net.pkt_tx", "上行数据包数", pkts?.second)

        // WiFi
        val wm = runCatching {
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        }.getOrNull()
        val info = runCatching { wm?.connectionInfo }.getOrNull()
        if (info != null && info.bssid != null) {
            val freq = info.frequency
            b.real("wifi.band", "WiFi 频段", if (freq in 4900..5900) "5G" else if (freq > 2000) "2.4G" else "未知")
            b.real("wifi.freq", "WiFi 频率", freq, "MHz")
            b.real("wifi.channel", "WiFi 信道", channelOf(freq))
            b.real("wifi.rssi", "WiFi 信号 RSSI", info.rssi, "dBm")
            b.real("wifi.link", "WiFi 链路速率", info.linkSpeed, "Mbps")
        } else {
            b.missing("wifi.band", "WiFi 频段", "未连接 WiFi 或 ACCESS_WIFI_STATE 未授予")
        }
        b.estimate("wifi.power_state", "WiFi 功耗状态（推算）",
            if (wifiEnabled()) "活跃（参考 ${REF_WIFI_W}W）" else "关闭",
            note = "由 WiFi 开关状态 + 参考功耗推算，驱动级功耗状态节点未开放")
        b.missing("wifi.scan", "WiFi 扫描次数", "需系统私有统计（第三方应用不可读）")

        // 蜂窝
        val tm = runCatching { context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager }.getOrNull()
        val granted = runCatching {
            context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (tm != null && granted) {
            val type = runCatching { tm.dataNetworkType }.getOrNull()
            b.realOrMissing("cell.type", "蜂窝网络类型（4G/5G NR）", type?.let { mapNetworkType(it) })
            b.missing("cell.rsrp", "RSRP / RSRQ 信噪比", "需持续监听 PhoneStateListener，本版本按秒采样不驻留监听")
        } else {
            b.missing("cell.type", "蜂窝网络类型", "未授予 READ_PHONE_STATE")
        }
        b.realOrMissing("cell.temp", "基带温度", thermalMax("modem", "mdm", "pa", "rf", "radio"))
        return b.build()
    }

    private fun mapNetworkType(t: Int): String = when (t) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
        TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA -> "3G HSPA"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "未知"
        else -> "type=$t"
    }

    private fun channelOf(freq: Int): Int = when {
        freq in 2412..2472 -> (freq - 2407) / 5
        freq in 5170..5825 -> (freq - 5000) / 5
        else -> -1
    }

    private fun netTotal(fieldIndex: Int): Double? {
        var sum = 0L
        for (line in readLines("/proc/net/dev").drop(2)) {
            val f = line.trim().fields()
            val iface = f.getOrNull(0)?.removeSuffix(":") ?: continue
            if (iface == "lo") continue
            sum += f.getOrNull(fieldIndex)?.toLongOrNull() ?: 0L
        }
        return rate("net.$fieldIndex", sum)
    }

    private fun netPackets(): Pair<Long, Long>? {
        var rx = 0L
        var tx = 0L
        var hit = false
        for (line in readLines("/proc/net/dev").drop(2)) {
            val f = line.trim().fields()
            val iface = f.getOrNull(0)?.removeSuffix(":") ?: continue
            if (iface == "lo") continue
            rx += f.getOrNull(2)?.toLongOrNull() ?: 0L
            tx += f.getOrNull(10)?.toLongOrNull() ?: 0L
            hit = true
        }
        return if (hit) rx to tx else null
    }

    // ==================================================================
    // 音频 DSP
    // ==================================================================
    private fun audioSection(): com.devperf.monitor.core.DeepSection {
        val b = DeepBuilder(DeepCategory.AUDIO)
        val am = runCatching { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }.getOrNull()
        val active = runCatching { am?.isMusicActive }.getOrNull()
        if (active != null) {
            b.real("audio.active", "音频播放状态", if (active) "活跃" else "空闲")
            b.estimate("audio.dsp_load", "DSP 负载（推算）", if (active) REF_DSP_LOAD_ACTIVE else 0f, "%",
                "按音频活跃状态与参考负载模型推算，DSP 负载计数器未开放")
        } else {
            b.missing("audio.active", "音频播放状态", "AudioManager 不可用")
        }
        b.realOrMissing("audio.codec", "编解码状态", runCatching { am?.getParameters("codec") }.getOrNull(),
            reason = "厂商未通过 AudioManager 暴露编解码状态")
        b.realOrMissing("audio.temp", "音频子系统温度", thermalMax("audio", "codec", "speaker", "amp"))
        return b.build()
    }

    // ==================================================================
    // 温度（分簇 / 分区）
    // ==================================================================
    private fun thermalSection(): com.devperf.monitor.core.DeepSection {
        val b = DeepBuilder(DeepCategory.THERMAL)
        val zones = thermalZones()
        if (zones.isEmpty()) {
            b.missing("thermal.zones", "温区列表", "/sys/class/thermal 不可读")
            return b.build()
        }
        b.real("thermal.count", "温区数量", zones.size)
        val groups = mapOf(
            "大核簇" to listOf("cpu0", "cpuss0", "apc0", "cpu-0", "tsens_tz_sensor0", "big"),
            "中核簇" to listOf("cpu1", "cpuss1", "apc1", "cpu-1", "mid"),
            "小核簇" to listOf("cpu2", "cpuss2", "cpu-2", "little", "cpu_little"),
            "GPU" to listOf("gpu", "gpuss"),
            "电池" to listOf("battery", "batt"),
            "UFS/存储" to listOf("ufs", "storage", "flash"),
            "基带" to listOf("modem", "mdm", "pa", "radio"),
            "屏幕/面板" to listOf("panel", "display", "lcd"),
            "充电/USB" to listOf("usb", "charger", "chg"),
            "机身壳温" to listOf("skin", "case", "board", "chassis"),
        )
        for ((label, keys) in groups) {
            val v = zones.entries.firstOrNull { (name, _) ->
                val n = name.lowercase(Locale.US)
                keys.any { n.contains(it) }
            }?.value
            b.realOrMissing("thermal.$label", "$label 温度", v, "℃")
        }
        val max = zones.values.maxOrNull()
        b.realOrMissing("thermal.max", "整机最高温区", max, "℃")
        return b.build()
    }

    private fun thermalZones(): Map<String, Float> {
        val dir = File("/sys/class/thermal")
        val out = LinkedHashMap<String, Float>()
        val files = dir.listFiles() ?: return emptyMap()
        for (f in files) {
            if (!f.name.startsWith("thermal_zone")) continue
            val type = readText("${f.path}/type")?.trim() ?: continue
            val temp = readLong("${f.path}/temp") ?: continue
            val c = if (temp > 1000) temp / 1000f else temp.toFloat()
            if (c > -50f && c < 150f) out[type] = c
        }
        return out
    }

    private fun thermalMax(vararg keys: String): Float? {
        val zones = thermalZones()
        return zones.entries.filter { (name, _) ->
            val n = name.lowercase(Locale.US)
            keys.any { n.contains(it) }
        }.maxOfOrNull { it.value }
    }

    // ==================================================================
    // 进程系统扩展
    // ==================================================================
    private fun processSection(): com.devperf.monitor.core.DeepSection {
        val b = DeepBuilder(DeepCategory.PROCESS)
        val procs = File("/proc").listFiles()?.filter { it.name.toIntOrNull() != null } ?: emptyList()
        val list = ArrayList<ProcInfo>()
        for (p in procs) {
            val pid = p.name.toIntOrNull() ?: continue
            val st = parseProcStat("${p.path}/stat") ?: continue
            val name = st.name
            val state = st.state
            val utime = st.utime
            val stime = st.stime
            val nice = st.nice
            val policy = st.policy
            val threads = st.threads
            val status = readText("${p.path}/status")
            val uid = status?.lineSequence()?.firstOrNull { it.startsWith("Uid:") }?.fields()?.getOrNull(1)?.toIntOrNull()
            val rssKb = status?.lineSequence()?.firstOrNull { it.startsWith("VmRSS:") }?.fields()?.getOrNull(1)?.toLongOrNull()
            val cgroup = readText("${p.path}/cgroup")?.lineSequence()?.firstOrNull()?.substringAfterLast(':')
            val cpu = cpuDeltaOf(pid, utime + stime)
            list += ProcInfo(pid, name, state, uid, nice, policy, threads, rssKb, cgroup, cpu, (utime + stime) / 100f)
        }
        if (list.isEmpty()) {
            b.missing("proc.list", "进程列表", "/proc 不可读")
            return b.build()
        }
        b.real("proc.count", "进程总数", list.size)
        val top = list.filter { it.cpuPercent != null }.sortedByDescending { it.cpuPercent }.take(5)
        if (top.isNotEmpty()) {
            b.real("proc.top", "CPU 占用 Top5 进程",
                top.joinToString("  ") { "${it.name}(${it.pid}):${fmt1(it.cpuPercent!!)}%" })
            for (t in top) {
                b.real(
                    "proc.${t.pid}", "进程 ${t.name} 详情",
                    "UID=${t.uid ?: "—"} 状态=${stateLabel(t.state)} nice=${t.nice ?: "—"} " +
                        "线程=${t.threads ?: "—"} RSS=${t.rssKb?.div(1024f)?.let { fmt1(it) } ?: "—"}MB " +
                        "CPU累计=${fmt1(t.cpuSeconds)}s 调度策略=${policyLabel(t.policy)}",
                )
                t.cgroup?.let { b.real("proc.${t.pid}.cgroup", "进程 ${t.name} cgroup 分组", it) }
            }
        } else {
            b.missing("proc.top", "进程 CPU 占用", "需要两次采样差分，首次采样后即可得")
        }
        b.missing("proc.pss", "进程 PSS", "需 dumpsys meminfo（DUMP 权限）")
        b.missing("proc.uss_all", "全部进程 USS", "其他进程 smaps 受权限限制")
        return b.build()
    }

    private data class ProcInfo(
        val pid: Int,
        val name: String,
        val state: String?,
        val uid: Int?,
        val nice: Int?,
        val policy: Int?,
        val threads: Long?,
        val rssKb: Long?,
        val cgroup: String?,
        val cpuPercent: Float?,
        val cpuSeconds: Float,
    )

    private fun stateLabel(s: String?): String = when (s) {
        "R" -> "R 运行"; "S" -> "S 睡眠"; "D" -> "D 不可中断"; "Z" -> "Z 僵尸"
        "T" -> "T 停止"; "t" -> "t 跟踪"; "X" -> "X 死亡"; "I" -> "I 空闲内核线程"
        else -> s ?: "—"
    }

    private fun policyLabel(p: Int?): String = when (p) {
        0 -> "SCHED_NORMAL"; 1 -> "SCHED_FIFO"; 2 -> "SCHED_RR"; 3 -> "SCHED_BATCH"
        5 -> "SCHED_IDLE"; 6 -> "SCHED_DEADLINE"; else -> p?.toString() ?: "—"
    }

    private fun cpuDeltaOf(pid: Int, totalJiffies: Long): Float? {
        val delta = diff("proc.cpu.$pid", totalJiffies)?.toFloat() ?: return null
        val dt = intervalSec?.toFloat() ?: return null
        val cores = Runtime.getRuntime().availableProcessors().toFloat()
        return (delta / (dt * 100f) * 100f / cores).coerceIn(0f, 100f * cores)
    }

    // ==================================================================
    // DDR
    // ==================================================================
    private fun ddrSection(): com.devperf.monitor.core.DeepSection {
        val b = DeepBuilder(DeepCategory.DDR)
        val devfreq = File("/sys/class/devfreq").listFiles()?.firstOrNull {
            val n = readText("${it.path}/device/of_node/name")?.trim()?.lowercase(Locale.US)
                ?: it.name.lowercase(Locale.US)
            n.contains("ddr") || n.contains("mem") || n.contains("cci") || n.contains("bci")
        }
        val freq = devfreq?.let { readLong("${it.path}/cur_freq") }
            ?: File("/sys/class/devfreq").listFiles()?.firstNotNullOfOrNull { readLong("${it.path}/cur_freq") }
        b.realOrMissing("ddr.freq", "内存总线频率", freq?.let { round(it / 1000000f) }, "MHz",
            note = devfreq?.let { "读自 ${it.path}" })
        val minF = devfreq?.let { readLong("${it.path}/min_freq") }
        val maxF = devfreq?.let { readLong("${it.path}/max_freq") }
        b.realOrMissing("ddr.freq_max", "总线最高频点", maxF?.let { round(it / 1000000f) }, "MHz")
        b.realOrMissing("ddr.freq_min", "总线最低频点", minF?.let { round(it / 1000000f) }, "MHz")

        // 带宽估算：系统繁忙度 × 总线频率 × 参考位宽
        val busy = lastCpu?.totalBusy
        val ddrMhz = freq?.div(1000000f)
        if (busy != null && ddrMhz != null) {
            b.estimate("ddr.bw", "DDR 带宽占用（推算）", fmt1(ddrMhz * DDR_BUS_BYTES * busy.toFloat() / 1024f), "GB/s",
                "总线频率 ${ddrMhz}MHz × 位宽 ${DDR_BUS_BYTES}B × 系统繁忙度 ${fmt1((busy * 100f).toFloat())}%")
        } else {
            b.missing("ddr.bw", "DDR 带宽占用", "需总线频率与繁忙度，首次采样后即可得")
        }
        return b.build()
    }

    // ==================================================================
    // 基础工具
    // ==================================================================
    private fun readText(path: String): String? = try {
        File(path).readText()
    } catch (_: Throwable) {
        null
    }

    private fun readLines(path: String): List<String> = try {
        File(path).readLines()
    } catch (_: Throwable) {
        emptyList()
    }

    private fun readLong(path: String): Long? = readText(path)?.trim()?.toLongOrNull()

    private fun firstLong(vararg paths: String): Long? {
        for (p in paths) {
            val v = readLong(p)
            if (v != null) return v
        }
        return null
    }

    private fun String.fields(): List<String> = trim().split(Regex("\\s+"))

    private fun meminfo(): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (line in readLines("/proc/meminfo")) {
            val f = line.fields()
            if (f.size < 2) continue
            out[f[0].removeSuffix(":")] = f[1].toLongOrNull() ?: 0L
        }
        return out
    }

    private fun vmstat(): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (line in readLines("/proc/vmstat")) {
            val f = line.fields()
            if (f.size < 2) continue
            out[f[0]] = f[1].toLongOrNull() ?: 0L
        }
        return out
    }

    private fun powerSupply(): Map<String, Map<String, String>> {
        val root = File("/sys/class/power_supply")
        val out = LinkedHashMap<String, Map<String, String>>()
        val dirs = root.listFiles() ?: return emptyMap()
        for (d in dirs) {
            val fieldsDir = File(d.path)
            val map = HashMap<String, String>()
            for (f in fieldsDir.listFiles() ?: emptyArray()) {
                if (!f.isFile) continue
                val v = readText(f.path)?.trim()
                if (v != null) map[f.name] = v
            }
            out[d.name] = map
        }
        return out
    }

    /** 计数器速率（每秒增量）；首次采样返回 null。 */
    private val prevCounters = ConcurrentHashMap<String, Long>()
    private val prevTimesMs = ConcurrentHashMap<String, Long>()

    private fun rate(key: String, value: Long): Double? {
        val now = System.currentTimeMillis()
        val prev = prevCounters[key]
        val pt = prevTimesMs[key]
        prevCounters[key] = value
        prevTimesMs[key] = now
        if (prev == null || pt == null) return null
        val dt = (now - pt) / 1000.0
        if (dt <= 0) return null
        val d = value - prev
        if (d < 0) return null
        return d / dt
    }

    /** 两个计数器的比值增量（用于 CPU 使用率）。 */
    private fun ratioDelta(key: String, total: Long, part: Long): Double? {
        val now = System.currentTimeMillis()
        val pt = prevCounters["$key.total"]
        val pp = prevCounters["$key.part"]
        val ptm = prevTimesMs["$key.total"]
        prevCounters["$key.total"] = total
        prevCounters["$key.part"] = part
        prevTimesMs["$key.total"] = now
        if (pt == null || pp == null || ptm == null) return null
        val dt = total - pt
        val dp = part - pp
        if (dt <= 0 || dp < 0) return null
        return dp.toDouble() / dt
    }

    private fun diff(key: String, value: Long): Long? {
        val prev = prevCounters[key]
        prevCounters[key] = value
        return if (prev == null) null else value - prev
    }

    /** 一次采样内共享的 CPU 占用结果，避免同一 tick 内重复差分造成数值失真。 */
    private data class CpuSample(val totalBusy: Double?, val perCore: Map<Int, Double>)

    @Volatile private var lastCpu: CpuSample? = null

    @Volatile private var lastSampleAt = 0L

    /** 采样间隔（秒）：每次采样只推进一次，供 PMU / 进程 CPU 等需要时长的项使用。 */
    @Volatile private var intervalSec: Double? = null

    private fun beginSample() {
        val now = System.currentTimeMillis()
        intervalSec = if (lastSampleAt == 0L) null else ((now - lastSampleAt) / 1000.0).coerceAtLeast(0.001)
        lastSampleAt = now
    }

    private inline fun safe(tag: String, block: () -> Unit) {
        runCatching { block() }
            .onFailure { android.util.Log.w("DeepCollector", "$tag 采集失败", it) }
    }

    private fun fmt1(v: Float): String = String.format(Locale.US, "%.1f", v)

    private fun fmt2(v: Float): String = String.format(Locale.US, "%.2f", v)

    private companion object {
        const val FRAME_BUFFER = 900
        const val IPC_ASSUMPTION = 1.5
        const val GPU_TDP_W = 3.0f
        const val GPU_RAIL_V = 0.8f
        const val REF_DISPLAY_W = 2.2f
        const val REF_WIFI_W = 0.35f
        const val REF_MODEM_W = 0.6f
        const val REF_GPS_W = 0.15f
        const val REF_DSP_W = 0.12f
        const val REF_DSP_LOAD_ACTIVE = 12f
        const val REF_UFS_W_PER_MB = 0.02f
        const val REF_SENSOR_W = 0.02f
        const val DDR_BUS_BYTES = 16f
    }
}
