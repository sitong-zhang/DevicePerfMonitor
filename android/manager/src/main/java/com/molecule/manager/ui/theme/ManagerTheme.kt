package com.molecule.manager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 品牌色：分子智能科技（蓝 + 黄分子元素）
val BrandBlue = Color(0xFF2D5BFF)
val BrandAmber = Color(0xFFFFC53D)

// 深色壁纸上的品牌强调色：BrandBlue(#2D5BFF) 在深底上对比度只有 ~2.4:1，
// 标题几乎看不见（用户实测反馈）。深色主题一律用这个亮蓝，对比度 > 7:1。
val BrandBright = Color(0xFF9DB9FF)

/** 深浅主题下都可读的品牌强调色：深色用亮蓝，浅色用品牌蓝。 */
@Composable
fun brandAccent(): Color = if (isSystemInDarkTheme()) BrandBright else BrandBlue

// 状态色：正常 / 受限 / 禁用 的按钮与标签配色
val StatusNormal = Color(0xFF34C77B)
val StatusRestricted = Color(0xFFF5A623)
val StatusDisabled = Color(0xFFE5484D)

private val DarkColorScheme = darkColorScheme(
    primary = BrandBright,
    secondary = BrandAmber,
    background = Color(0xFF0F1115),
    surface = Color(0xFF1A1D24),
    onBackground = Color(0xFFF2F4F9),
    onSurface = Color(0xFFF2F4F9),
    // 次级文字用亮银灰：默认 #938F99 在深蓝壁纸上几乎不可读（用户实测反馈）
    onSurfaceVariant = Color(0xFFC3CBDD),
    outline = Color(0xFF8A93A8),
)

private val LightColorScheme = lightColorScheme(
    primary = BrandBlue,
    secondary = BrandAmber,
    background = Color(0xFFF5F7FB),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1D24),
    onSurface = Color(0xFF1A1D24),
    onSurfaceVariant = Color(0xFF4A5568),
)

@Composable
fun ManagerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColorScheme else LightColorScheme,
        content = content,
    )
}
