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

private val DarkColorScheme = darkColorScheme(
    primary = BrandBlue,
    secondary = BrandAmber,
    background = Color(0xFF0F1115),
    surface = Color(0xFF1A1D24),
    onBackground = Color(0xFFEDEFF5),
    onSurface = Color(0xFFEDEFF5),
)

private val LightColorScheme = lightColorScheme(
    primary = BrandBlue,
    secondary = BrandAmber,
    background = Color(0xFFF5F7FB),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1D24),
    onSurface = Color(0xFF1A1D24),
)

@Composable
fun ManagerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColorScheme else LightColorScheme,
        content = content,
    )
}
