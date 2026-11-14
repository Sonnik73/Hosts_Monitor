package com.servermon.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.servermon.monitor.data.MonitorRepository
import com.servermon.monitor.ui.components.formatLatency
import com.servermon.monitor.ui.components.severityColor

/**
 * Aggregated N×N connectivity matrix: for every server (rows) we show the
 * peer checks reported by its agent (columns). Peer names must match across
 * agent configs (they are the "name" field in the peers array).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectivityScreen(onBack: () -> Unit) {
    val repo = MonitorRepository.get()
    val states by repo.states.collectAsStateWithLifecycle()

    val online = states.values.filter { it.online && it.metrics != null }
    val columns = online
        .flatMap { it.metrics!!.peers.map { p -> p.name } }
        .distinct()
        .sorted()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Матрица связности") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            if (online.isEmpty()) {
                Text(
                    "Нет данных: серверы ещё не опрошены или недоступны. " +
                        "Обновите дашборд и вернитесь сюда.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Card(
                    shape = com.servermon.monitor.ui.theme.CardShape,
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Строка — «откуда» (агент сервера), столбец — «куда» (peer из конфига).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 12.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    Column {
                        Row {
                            Text(
                                "откуда \\ куда",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .width(120.dp)
                                    .padding(6.dp),
                            )
                            for (col in columns) {
                                Text(
                                    col,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier
                                        .width(88.dp)
                                        .padding(6.dp),
                                )
                            }
                        }
                        for (row in online) {
                            Row(Modifier.padding(top = 4.dp)) {
                                Text(
                                    row.config.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .width(120.dp)
                                        .padding(6.dp),
                                )
                                for (col in columns) {
                                    val check = row.metrics!!.peers.firstOrNull { it.name == col }
                                    Cell(check)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(check: com.servermon.monitor.data.PeerCheck?) {
    val (bg, label) = when {
        check == null -> MaterialTheme.colorScheme.surfaceVariant to "–"
        check.ok -> severityColor(0).copy(alpha = 0.2f) to formatLatency(check.latencyMs)
        else -> severityColor(2).copy(alpha = 0.3f) to "✕"
    }
    Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = if (check != null && !check.ok) severityColor(2) else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .width(88.dp)
            .padding(3.dp)
            .background(bg, RoundedCornerShape(6.dp))
            .padding(vertical = 8.dp, horizontal = 6.dp),
        maxLines = 1,
    )
}
