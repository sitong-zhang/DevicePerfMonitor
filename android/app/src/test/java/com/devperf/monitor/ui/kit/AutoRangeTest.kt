package com.devperf.monitor.ui.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * autoRange 单测：钉住「抗离群点 + 最小可范围」两条规则。
 *
 * 背景（1.0.9 用户反馈「所有数据都偏上」）：Y 轴若用 min/max，
 * 一个离群低点会把密集点全压到图表顶部。
 */
class AutoRangeTest {

    @Test
    fun `离群低点不绑架量程`() {
        // 59 个点在 79.5~80.0，1 个离群低点 78.0（真实场景：内存偶跌）
        val values = List(59) { 79.5f + (it % 6) * 0.1f } + 78.0f
        val r = autoRange(values)
        // 量程下界应远高于离群点（78.0 不参与量程）
        assertTrue("lo=${r.start} 不应被离群点拉低", r.start > 78.5f)
        // 主体数据应落在量程内可见区域（不是贴顶）
        val body = values.filter { it >= 79.5f }
        val t = body.map { (it - r.start) / (r.endInclusive - r.start) }
        assertTrue("主体点不应全部贴顶: $t", t.any { it < 0.7f })
        assertTrue("主体点应大部分在量程内", t.count { it in 0f..1f } >= body.size - 1)
    }

    @Test
    fun `近恒定数据居中且波动可见`() {
        // 60Hz 刷新率全程恒定（旧实现：range 兜底成 1，线贴底/贴顶）
        val values = List(60) { 60f }
        val r = autoRange(values)
        assertEquals("范围应以 60 为中心", 60f, (r.start + r.endInclusive) / 2f, 0.001f)
        assertTrue("范围应有可感知宽度", r.endInclusive - r.start > 0.5f)
        // 微弱波动（±0.05）应产生可见位移（超过半幅）
        val t = (60.05f - r.start) / (r.endInclusive - r.start)
        assertTrue("微弱波动应可见: t=$t", t > 0.5f)
    }

    @Test
    fun `正常波动接近minmax`() {
        // 0~100 均匀分布：分位数应贴近真实 min/max
        val values = (0..100).map { it.toFloat() }
        val r = autoRange(values)
        assertTrue("lo 应接近 min", r.start in 0f..4f)
        assertTrue("hi 应接近 max", r.endInclusive in 96f..100f)
    }

    @Test
    fun `空与单点兜底`() {
        val empty = autoRange(emptyList())
        assertTrue(empty.start == 0f && empty.endInclusive == 1f)
        val single = autoRange(listOf(42f))
        assertEquals("单点以该值为中心", 42f, (single.start + single.endInclusive) / 2f, 0.001f)
        assertTrue("单点范围有宽度", single.endInclusive - single.start > 0.5f)
    }

    @Test
    fun `量级决定最小范围`() {
        // 内存 80 与帧率 60 的最小可范围不同（2% 相对量程）
        val mem = autoRange(List(30) { 80f })
        val fps = autoRange(List(30) { 60f })
        assertTrue("80 量级的最小范围应大于 60 量级",
            (mem.endInclusive - mem.start) > (fps.endInclusive - fps.start))
    }
}
