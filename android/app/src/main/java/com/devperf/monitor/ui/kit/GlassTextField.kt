package com.devperf.monitor.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.devperf.monitor.BuildConfig

/**
 * 玻璃风文本输入框 —— 与 GlassButton / GlassSwitch / GlassCard 共用同一套
 * Kyant0 Backdrop 玻璃面（[glassSurface]：vibrancy + lens），不再自绘渐变。
 *
 * 登录 / 注册 / 反馈等输入场景共用。玻璃面折射背后的品牌壁纸，文字对比度由
 * surface 层 40% 透明保证（与卡片一致）。聚焦态叠加主色描边，输入位置一目了然。
 *
 * 回落规则与 Controls.kt 各玻璃件一致：glass flavor 且处于主窗口（[LocalBackdrop]
 * 非 null）走玻璃面；悬浮窗 / 无玻璃回落 MD3 原样外观。
 */
@Composable
internal fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minLines: Int = if (singleLine) 1 else 3,
    enabled: Boolean = true,
) {
    val backdrop = if (BuildConfig.LIQUID_GLASS) LocalBackdrop.current else null
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    // 表面 tint：聚焦时偏主色（glassSurface 内 onDrawSurface 会再压到 40% 透明，
    // 所以是淡淡的一抹玻璃着色，不是死板纯色块）；非聚焦用主题 surfaceContainer。
    val tint = if (focused) cs.primary else cs.surfaceContainer

    Box(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 4.dp, bottom = 4.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (label != null) Modifier.padding(top = 22.dp) else Modifier)
                .then(
                    if (backdrop != null) {
                        Modifier
                            .glassSurface(backdrop, shape, tint)
                            .clip(shape)
                            .then(
                                if (focused) {
                                    Modifier.border(
                                        width = 1.5.dp,
                                        color = cs.primary.copy(alpha = 0.9f),
                                        shape = shape,
                                    )
                                } else {
                                    Modifier
                                },
                            )
                    } else {
                        Modifier
                            .clip(shape)
                            .background(cs.surfaceContainer, shape)
                            .border(
                                width = 1.dp,
                                color = cs.outline.copy(alpha = 0.5f),
                                shape = shape,
                            )
                    },
                )
                .padding(horizontal = 14.dp, vertical = if (singleLine) 13.dp else 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                singleLine = singleLine,
                minLines = minLines,
                enabled = enabled,
                visualTransformation = if (isPassword) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                interactionSource = interactionSource,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
