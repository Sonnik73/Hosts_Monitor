package com.servermon.monitor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.servermon.monitor.data.AvailSample

/**
 * Uptime timeline: one rounded block per poll sample.
 * Green = up, amber = up via relay, red = down.
 */
@Composable
fun UptimeBar(
    samples: List<AvailSample>,
    modifier: Modifier = Modifier,
    barHeight: Dp = 28.dp,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
    ) {
        if (samples.isEmpty()) return@Canvas
        val slot = size.width / samples.size
        val blockWidth = (slot - 2f).coerceAtLeast(1f)
        val blockHeight = size.height - 8f
        val radius = CornerRadius(2.dp.toPx())
        samples.forEachIndexed { i, sample ->
            val color = when (sample.state) {
                1 -> severityColor(0)
                2 -> severityColor(1)
                else -> severityColor(2)
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(i * slot + 1f, 4f),
                size = Size(blockWidth, blockHeight),
                cornerRadius = radius,
            )
        }
    }
}
