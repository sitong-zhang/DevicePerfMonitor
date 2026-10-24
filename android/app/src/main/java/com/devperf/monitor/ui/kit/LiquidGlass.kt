package com.devperf.monitor.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

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
 * 2. [rememberAppBackdrop]：录制节点只导出「背景层」画下的内容（AppNav 根的
 *    主题渐变）；玻璃元素经 [LocalBackdrop] 在录制子树之外取用。
 *
 * 效果生效条件（库文档）：blur 需 Android 12+，lens 需 Android 13+；
 * 更低版本库内部自动跳过效果（只保留 surface 层），不会崩。
 */
val LocalBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * 创建应用级 backdrop（glass flavor 专用）。
 *
 * 用法（AppNav 根，Kyant0 catalog `BackdropDemoScaffold` 标准结构）：
 * ```
 * val backdrop = rememberAppBackdrop()
 * Box(Modifier.fillMaxSize()) {
 *     // 背景层：layerBackdrop 只挂这个纯背景节点，录下主题渐变
 *     Box(Modifier.matchParentSize().layerBackdrop(backdrop).background(brush))
 *     // 内容与玻璃元素全部是背景层的兄弟——绝不能进录制子树
 *     NavHost { ... }
 * }
 * ```
 *
 * 为什么背景层只放渐变：玻璃折射的是「玻璃后面看到的画面」，本项目的画面就是
 * 主题渐变，录制它即可。1.0.13 曾把 layerBackdrop 挂到 NavHost 上，玻璃卡片
 * 因此进入录制子树，录制重放时采样正在录音的同一个 RenderNode（自指采样），
 * 真机一进首页就闪退——详见 AppNav 内注释。
 */
@Composable
fun rememberAppBackdrop(): LayerBackdrop = rememberLayerBackdrop { drawContent() }

/** 把内容导出为 backdrop 源（glass flavor 专用，null 时原样返回）。 */
fun Modifier.appLayerBackdrop(backdrop: LayerBackdrop?): Modifier =
    if (backdrop != null) this.layerBackdrop(backdrop) else this
