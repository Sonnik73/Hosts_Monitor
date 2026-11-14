package com.servermon.monitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.servermon.monitor.data.AppSettings

// ---------------------------------------------------------------------------
// Formatters
// ---------------------------------------------------------------------------

fun formatUptime(seconds: Long): String {
    val d = seconds / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    return when {
        d > 0 -> "%dd %dh".format(d, h)
        h > 0 -> "%dh %02dm".format(h, m)
        else -> "%dm".format(m)
    }
}

fun formatBps(bps: Long): String = when {
    bps >= 1_000_000 -> "%.1f МБ/с".format(bps / 1_000_000.0)
    bps >= 1_000 -> "%.0f КБ/с".format(bps / 1_000.0)
    else -> "%d Б/с".format(bps)
}

fun formatLatency(ms: Double?): String = if (ms == null) "—" else "%.0f мс".format(ms)

// ---------------------------------------------------------------------------
// Severity
// ---------------------------------------------------------------------------

fun severityColor(sev: Int): Color = when (sev) {
    2 -> Color(0xFFE53935)
    1 -> Color(0xFFFFB300)
    else -> Color(0xFF43A047)
}

// ---------------------------------------------------------------------------
// Reusable widgets
// ---------------------------------------------------------------------------

@Composable
fun StatusDot(online: Boolean, size: Dp = 12.dp, colorOverride: Color? = null) {
    val color = colorOverride ?: if (online) severityColor(0) else severityColor(2)
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(size)
            .background(color, CircleShape)
    )
}

@Composable
fun MetricBar(
    label: String,
    pct: Double?,
    warn: Int,
    crit: Int,
    detail: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = detail ?: (if (pct == null) "—" else "%.0f%%".format(pct)),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        val sev = if (pct == null) 0 else AppSettings.severity(pct, warn, crit)
        LinearProgressIndicator(
            progress = { ((pct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f) },
            color = severityColor(sev),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(6.dp),
        )
    }
}
