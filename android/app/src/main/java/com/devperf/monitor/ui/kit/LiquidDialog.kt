package com.devperf.monitor.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devperf.monitor.BuildConfig
import com.devperf.monitor.ui.kit.LocalBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.RoundedRectangle

/**
 * 统一液态玻璃弹窗（1.0.53）：一次性封装上游 Kyant0/AndroidLiquidGlass
 * catalog 的 [DialogContent] 同窗口叠层范式，不再使用 Compose `Dialog()` 独立窗口。
 *
 * 上游范式（DialogContent.kt）：
 *   - 不另开窗口，而是在同一窗口里用 `Box` 叠层：先整屏压一层暗化层（dimColor），
 *     再在中心放一张 `drawBackdrop` 玻璃卡（采样应用级 backdrop，真液态折射）；
 *   - 玻璃卡参数原样照搬：RoundedRectangle(48dp) + colorControls(brightness 0.2,
 *     saturation 1.5) + blur(16dp / 浅, 8dp / 深) + lens(24dp, 48dp, depthEffect) +
 *     Highlight.Plain + onDrawSurface 画容器色；
 *   - 按钮走液态玻璃：本端用 GlassButton（内部即 catalog 的 LiquidButton），
 *     与上游「弹窗按钮也要用液态玻璃」一致。
 *
 * 无玻璃环境（plain / 悬浮窗）回落实底面板 + MD3 按钮，布局与交互不变。
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
    val isLightTheme = !isSystemInDarkTheme()

    // 上游 DialogContent 配色（浅 / 深两套）
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val containerColor =
        if (isLightTheme) Color(0xFFFAFAFA).copy(0.6f) else Color(0xFF121212).copy(0.4f)
    val dimColor =
        if (isLightTheme) Color(0xFF29293A).copy(0.23f) else Color(0xFF121212).copy(0.56f)

    // 同窗口叠层：覆盖整屏，中心对齐玻璃卡（不另开 Dialog 窗口）
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // 暗化层：点罩即等价 dismiss（仅 dismissible 时）
        Box(
            Modifier
                .fillMaxSize()
                .background(dimColor)
                .clickable(enabled = dismissible, onClick = { onDismiss?.invoke() }),
        )

        // 玻璃卡：drawBackdrop 采样应用级 backdrop（与上游 DialogContent 一致）
        Column(
            modifier = modifier
                .padding(horizontal = 28.dp, vertical = 24.dp)
                .fillMaxWidth()
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedRectangle(48f.dp) },
                            effects = {
                                colorControls(
                                    brightness = if (isLightTheme) 0.2f else 0f,
                                    saturation = 1.5f,
                                )
                                blur(if (isLightTheme) 16f.dp.toPx() else 8f.dp.toPx())
                                lens(24f.dp.toPx(), 48f.dp.toPx(), depthEffect = true)
                            },
                            highlight = { Highlight.Plain },
                            onDrawSurface = { drawRect(containerColor) },
                        )
                    } else {
                        Modifier.background(
                            if (isLightTheme) Color(0xFFF6F6F8) else Color(0xFF1C1C1E),
                            RoundedRectangle(48f.dp),
                        )
                    },
                )
                // 卡片本身拦截点击，避免穿透到暗化层触发 dismiss
                .clickable(enabled = true, onClick = {}),
        ) {
            icon?.let { ic ->
                Icon(
                    imageVector = ic,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier
                        .padding(start = 4.dp, top = 4.dp)
                        .size(30.dp),
                )
            }
            Text(
                text = title,
                style = TextStyle(contentColor, 23.sp, FontWeight.Medium),
                modifier = Modifier.padding(4.dp, 16.dp, 4.dp, if (subtitle != null) 8.dp else 4.dp),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = TextStyle(contentColor.copy(alpha = 0.68f), 15.sp),
                    modifier = Modifier.padding(4.dp, 0.dp, 4.dp, 4.dp),
                )
            }
            content()
            Spacer(Modifier.height(if (confirmText.isNotBlank() || dismissText != null) 4.dp else 12.dp))
            if (confirmText.isNotBlank() || dismissText != null) {
                Row(
                    Modifier
                        .padding(4.dp, 8.dp, 4.dp, 8.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (dismissText != null && onDismiss != null) {
                        GlassButton(
                            text = dismissText,
                            onClick = onDismiss,
                            variant = GlassButtonVariant.SECONDARY,
                            enabled = true,
                            modifier = Modifier
                                .height(48.dp)
                                .weight(1f),
                        )
                    }
                    if (confirmText.isNotBlank() && onConfirm != null) {
                        GlassButton(
                            text = confirmText,
                            onClick = onConfirm,
                            variant = GlassButtonVariant.PRIMARY,
                            enabled = confirmEnabled,
                            modifier = Modifier
                                .height(48.dp)
                                .weight(1f),
                        )
                    }
                }
            }
        }
    }
}
