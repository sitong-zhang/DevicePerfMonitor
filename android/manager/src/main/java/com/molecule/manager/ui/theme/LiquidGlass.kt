package com.molecule.manager.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidButton
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule

/**
 * 管理端液态玻璃（Backdrop）接入层。
 *
 * 库：io.github.kyant0:backdrop + io.github.kyant0:shapes（Kyant0/AndroidLiquidGlass，
 * Apache-2.0），与用户端同源，参数直接照抄库 catalog 示例既有值。
 *
 * 用法（MainScreen 根）：
 * ```
 * val backdrop = rememberAppBackdrop()
 * Box(Modifier.fillMaxSize()) {
 *     // 背景层：只录品牌壁纸，玻璃折射的就是它
 *     Box(Modifier.matchParentSize().layerBackdrop(backdrop).background(brandBrush))
 *     // 内容与玻璃元素都是背景层的兄弟，绝不能进录制子树
 *     Content(backdrop)
 * }
 * ```
 */
val LocalBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

@Composable
fun rememberAppBackdrop(): LayerBackdrop = rememberLayerBackdrop { drawContent() }

/** 管理端品牌壁纸渐变（深色基底 + 蓝/黄分子光晕，给玻璃足够折射内容）。 */
val BrandBackdropBrush: Brush
    get() = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF0B1020),
            Color(0xFF122046),
            Color(0xFF0F1830),
            Color(0xFF1A1405),
        ),
    )

/**
 * 玻璃表面修饰：Kyant0 Backdrop 的 drawBackdrop，照抄 catalog 的 vibrancy + lens。
 * [tint] 为表面色（40% 透明，玻璃透出来、文字对比度不塌）。
 */
fun Modifier.glassSurface(
    backdrop: Backdrop,
    shape: Shape,
    tint: Color,
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        vibrancy()
        lens(24f.dp.toPx(), 48f.dp.toPx(), depthEffect = true)
    },
    onDrawSurface = {
        drawRect(color = tint, alpha = 0.4f)
    },
)

/** 玻璃卡片容器：MD3 Card + 玻璃表面。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    tint: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    val backdrop = LocalBackdrop.current
    Card(
        modifier = modifier.fillMaxWidth().then(
            if (backdrop != null) {
                Modifier.glassSurface(backdrop, shape, tint)
            } else {
                Modifier
            },
        ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (backdrop != null) Color.Transparent else tint,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(16.dp),
            content = content,
        )
    }
}

/** 玻璃按钮：封装 Kyant0 LiquidButton（按压有液态高光/形变）。 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = BrandBlue,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val backdrop = LocalBackdrop.current
    if (backdrop != null) {
        LiquidButton(
            onClick = onClick,
            backdrop = backdrop,
            modifier = modifier,
            surfaceColor = tint.copy(alpha = 0.9f),
            content = content,
        )
    } else {
        Card(
            onClick = onClick,
            modifier = modifier.height(48f.dp),
            shape = Capsule(),
            colors = CardDefaults.cardColors(containerColor = tint),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16f.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement
                    .spacedBy(8f.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/**
 * 玻璃输入框：BasicTextField 包在玻璃表面容器里（不用 Material3 OutlinedTextField，
 * 避免原始组件观感）。[label] 为浮动标签。
 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    minLines: Int = 1,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val backdrop = LocalBackdrop.current
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (backdrop != null) {
                    Modifier.glassSurface(backdrop, shape, MaterialTheme.colorScheme.surfaceVariant)
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surfaceVariant, shape)
                },
            )
            .clip(shape)
            .padding(14.dp),
    ) {
        androidx.compose.foundation.layout.Column(
            verticalArrangement = androidx.compose.foundation.layout.Arrangement
                .spacedBy(4.dp),
        ) {
            if (label.isNotBlank()) {
                androidx.compose.material3.Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = minLines,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                ),
                cursorBrush = SolidColor(BrandBlue),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = if (isPassword) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                decorationBox = { inner ->
                    if (value.isEmpty() && label.isNotBlank()) {
                        androidx.compose.material3.Text(
                            text = " ",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    inner()
                },
            )
        }
    }
}
