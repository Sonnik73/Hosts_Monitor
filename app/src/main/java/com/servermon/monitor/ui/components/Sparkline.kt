package com.servermon.monitor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Minimal sparkline over [values] (0..100 or arbitrary scale).
 * Draws a filled area plus a thin line; handles empty input gracefully.
 */
@Composable
fun Sparkline(
    values: List<Double>,
    color: Color,
    modifier: Modifier = Modifier,
    maxValue: Double = 100.0,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        if (values.size < 2) return@Canvas
        val stepX = size.width / (values.size - 1)
        val scale = if (maxValue <= 0.0) 1.0 else maxValue

        fun yOf(v: Double): Float =
            (size.height * (1.0f - (v / scale).toFloat().coerceIn(0f, 1f)))

        val line = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = yOf(v)
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, color.copy(alpha = 0.15f))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx()))
    }
}
