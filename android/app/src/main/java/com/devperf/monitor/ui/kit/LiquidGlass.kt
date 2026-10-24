package com.devperf.monitor.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.devperf.monitor.ui.theme.LocalTokenColors

/**
 * 液态玻璃（Backdrop）在本项目的接入层。
 *
 * 库：io.github.kyant0:backdrop 2.0.0-alpha03 + io.github.kyant0:shapes 1.2.0
 * （Kyant0/AndroidLiquidGlass，Apache-2.0）。用法与参数一律照库文档
 * （kyant.gitbook.io/backdrop）与官方 catalog 示例的既有值，本文件只做
 * 两件「本项目结构适配」的事，不发明任何效果参数：
 *
 * 1. [LocalBackdrop]：glass flavor 在 AppNav 根节点创建 LayerBackdrop 并通过
 *    CompositionLocal 下发；plain flavor 与悬浮窗窗口（独立 window，跨窗口
 *    捕获无意义）下取到 null，调用点自动回落到 MD3 原样外观。
 * 2. [rememberAppBackdrop]：教程标准创建式——先画主题背景色再画内容
 *    （glass-bottom-bar 教程步骤 2），保证玻璃区域外也有内容可折射。
 *
 * 效果生效条件（库文档）：blur 需 Android 12+，lens 需 Android 13+；
 * 更低版本库内部自动跳过效果（只保留 surface 层），不会崩。
 */
val LocalBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * 创建应用级 backdrop 并挂到内容树上（glass flavor 专用）。
 *
 * 用法（AppNav 根）：
 * ```
 * val backdrop = rememberAppBackdrop()
 * Box(Modifier.fillMaxSize()) {
 *     NavHost(Modifier.layerBackdrop(backdrop)) { ... }
 * }
 * ```
 */
@Composable
fun rememberAppBackdrop(): LayerBackdrop {
    val background = LocalTokenColors.current.background
    return rememberLayerBackdrop {
        drawRect(background)
        drawContent()
    }
}

/** 把内容导出为 backdrop 源（glass flavor 专用，null 时原样返回）。 */
fun Modifier.appLayerBackdrop(backdrop: LayerBackdrop?): Modifier =
    if (backdrop != null) this.layerBackdrop(backdrop) else this
