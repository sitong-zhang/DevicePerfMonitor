package com.devperf.monitor.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.devperf.monitor.ui.theme.DesignTokens

/**
 * 玻璃风文本输入框。BasicTextField + 与 GlassPanel 同款描边/底色/圆角，
 * 空态显示 placeholder。反馈页等输入场景共用（此前内联在设置页，1.0.27 起抽出）。
 */
@Composable
internal fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(cs.surfaceContainer)
            .border(
                width = DesignTokens.Surface.BORDER_WIDTH.dp,
                color = cs.outline.copy(alpha = DesignTokens.Surface.BORDER_ALPHA),
                shape = shape,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
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
            minLines = minLines,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
