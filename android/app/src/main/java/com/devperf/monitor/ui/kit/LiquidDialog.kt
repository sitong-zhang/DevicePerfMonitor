package com.devperf.monitor.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.devperf.monitor.BuildConfig
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.RoundedRectangle

/**
 * 统一液态玻璃弹窗（1.0.51）：形态 1:1 对齐上游 Kyant0/AndroidLiquidGlass
 * catalog 的 DialogContent（demo 第 4 页，用户截图确认的那个弹窗）。
 *
 * 上游参数原样搬：
 * - 形状 RoundedRectangle(48dp)，效果 colorControls(brightness 0.2, saturation 1.5)
 *   + blur(16dp) + lens(24dp, 48dp, depthEffect = true)，高光 Highlight.Plain；
 * - 容器色 浅色 #FAFAFA 60% / 深色 #121212 40%，文字 墨黑 / 纯白；
 * - 按钮：取消 = 半透明胶囊（容器色 20%），确认 = 系统蓝 #0088FF 胶囊白字，48dp 高；
 * - 弹窗背后整屏压一层暗色罩（浅 #29293A 23% / 深 #121212 56%），关闭系统默认 dim。
 *
 * 无玻璃环境（plain flavor / 悬浮窗）回落实底面板，布局与按钮不变。
 */
@Composable
fun LiquidDialog(
    title: String,
    confirmText: String,
    onConfirm: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    dismissText: String? = null,
    onDismiss: (() -> Unit)? = null,
    dismissible: Boolean = false,
    confirmEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val backdrop = if (BuildConfig.LIQUID_GLASS) LocalBackdrop.current else null
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f

    // 上游 DialogContent 配色（浅 / 深两套）
    val contentColor = if (dark) Color.White else Color.Black
    val accentColor = if (dark) Color(0xFF0091FF) else Color(0xFF0088FF)
    val containerColor = if (dark) Color(0xFF121212).copy(0.4f) else Color(0xFFFAFAFA).copy(0.6f)
    val dimColor = if (dark) Color(0xFF121212).copy(0.56f) else Color(0xFF29293A).copy(0.23f)

    Dialog(
        onDismissRequest = { if (dismissible) onDismiss?.invoke() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
            // 关掉系统 dim，用上游同款暗色罩：色相/浓度与玻璃容器配套
            dimEnabled = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { if (dismissible) onDismiss?.invoke() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            // 背景暗罩（点罩即等价 dismiss 区域）
            Box(
                Modifier
                    .fillMaxSize()
                    .background(dimColor),
            )
            Column(
                modifier = modifier
                    .padding(horizontal = 32.dp)
                    .fillMaxWidth()
                    .then(
                        if (backdrop != null) {
                            Modifier.drawBackdrop(
                                backdrop = backdrop,
                                shape = { RoundedRectangle(48f.dp) },
                                effects = {
                                    colorControls(
                                        brightness = if (dark) 0f else 0.2f,
                                        saturation = 1.5f,
                                    )
                                    blur(if (dark) 8f.dp.toPx() else 16f.dp.toPx())
                                    lens(24f.dp.toPx(), 48f.dp.toPx(), depthEffect = true)
                                },
                                highlight = { Highlight.Plain },
                                onDrawSurface = { drawRect(containerColor) },
                            )
                        } else {
                            Modifier.background(
                                if (dark) Color(0xFF1C1C1E) else Color(0xFFF6F6F8),
                                RoundedRectangle(48f.dp),
                            )
                        },
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                icon?.let { ic ->
                    Icon(
                        imageVector = ic,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier
                            .padding(start = 28.dp, top = 24.dp)
                            .size(30.dp),
                    )
                }
                Text(
                    text = title,
                    style = TextStyle(contentColor, 23.sp, FontWeight.Medium),
                    modifier = Modifier.padding(28.dp, 24.dp, 28.dp, if (subtitle != null) 8.dp else 4.dp),
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.68f),
                        modifier = Modifier.padding(28.dp, 0.dp, 28.dp, 4.dp),
                    )
                }
                content()
                Spacer(Modifier.height(if (confirmText.isNotBlank() || dismissText != null) 4.dp else 12.dp))
                if (confirmText.isNotBlank() || dismissText != null) {
                    Row(
                        Modifier
                            .padding(24.dp, 8.dp, 24.dp, 24.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (dismissText != null && onDismiss != null) {
                            DialogCapsuleButton(
                                text = dismissText,
                                contentColor = contentColor,
                                container = containerColor.copy(alpha = 0.2f),
                                enabled = true,
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (confirmText.isNotBlank() && onConfirm != null) {
                            DialogCapsuleButton(
                                text = confirmText,
                                contentColor = Color.White,
                                container = accentColor,
                                enabled = confirmEnabled,
                                onClick = onConfirm,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 弹窗胶囊按钮（上游 DialogContent 同款）：半透明 / 纯色两态。 */
@Composable
private fun DialogCapsuleButton(
    text: String,
    contentColor: Color,
    container: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .background(container, com.kyant.shapes.Capsule())
            .clickable(enabled = enabled, onClick = onClick)
            .height(48.dp)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, style = TextStyle(contentColor, 16.sp))
    }
}
