package com.devperf.monitor.ui

import android.graphics.RuntimeShader
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 探针：确认 Robolectric NATIVE 图形模式下 AGSL 编译能力，
 * 用于判断 InteractiveHighlight 中 layout(color) uniform 是否为测试环境限制。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RuntimeShaderProbeTest {

    @Test
    fun probe_withLayoutColor() {
        val result = runCatching {
            RuntimeShader(
                """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;

half4 main(float2 coord) {
    return color;
}"""
            )
        }
        println("PROBE withLayoutColor: ${result.exceptionOrNull()?.let { it::class.simpleName + ": " + it.message } ?: "OK"}")
    }

    @Test
    fun probe_withoutLayout() {
        val result = runCatching {
            RuntimeShader(
                """
uniform float2 size;
uniform half4 color;
uniform float radius;
uniform float2 position;

half4 main(float2 coord) {
    return color;
}"""
            )
        }
        println("PROBE withoutLayout: ${result.exceptionOrNull()?.let { it::class.simpleName + ": " + it.message } ?: "OK"}")
    }

    @Test
    fun probe_blurOnly() {
        // backdrop 背景层实际使用的 blur 场景：只有 plain uniform + math
        val result = runCatching {
            RuntimeShader(
                """
uniform float2 size;
uniform float radius;

half4 main(float2 coord) {
    float dist = distance(coord, size * 0.5);
    float a = smoothstep(radius, radius * 0.5, dist);
    return half4(1.0) * a;
}"""
            )
        }
        println("PROBE blurOnly: ${result.exceptionOrNull()?.let { it::class.simpleName + ": " + it.message } ?: "OK"}")
    }

    @Test
    fun probe_buildFingerprint() {
        println("PROBE Build.FINGERPRINT='${android.os.Build.FINGERPRINT}'")
        println("PROBE Build.MODEL='${android.os.Build.MODEL}' MANUFACTURER='${android.os.Build.MANUFACTURER}'")
    }
}
