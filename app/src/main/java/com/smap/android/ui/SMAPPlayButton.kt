package com.smap.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp

/** 与桌面版 CircleBtn / IconPlay 相同的渐变圆形播放按钮。 */
@Composable
fun SMAPPlayButton(
    playing: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Canvas(modifier = modifier.size(size).clickable(onClick = onClick)) {
        drawCircle(
            brush = Brush.linearGradient(
                listOf(Color(0xFF1C1C3E), Color(0xFF2A2352), Color(0xFF241D47))
            )
        )

        if (playing) {
            val barWidth = this.size.width * 0.105f
            val barHeight = this.size.height * 0.38f
            val top = (this.size.height - barHeight) / 2f
            val gap = this.size.width * 0.10f
            val left = this.size.width / 2f - gap / 2f - barWidth
            drawRoundRect(Color.White, Offset(left, top), androidx.compose.ui.geometry.Size(barWidth, barHeight))
            drawRoundRect(Color.White, Offset(left + barWidth + gap, top), androidx.compose.ui.geometry.Size(barWidth, barHeight))
        } else {
            // 对齐桌面版 M6 4 L6 20 L20 12，并向右补偿视觉重心。
            val iconSize = this.size.width * (22f / 52f)
            val left = (this.size.width - iconSize) / 2f + this.size.width * (2.5f / 52f)
            val top = (this.size.height - iconSize) / 2f
            val path = Path().apply {
                moveTo(left + iconSize * 0.12f, top)
                lineTo(left + iconSize * 0.12f, top + iconSize)
                lineTo(left + iconSize, top + iconSize * 0.5f)
                close()
            }
            drawPath(path, Color.White)
        }
    }
}
