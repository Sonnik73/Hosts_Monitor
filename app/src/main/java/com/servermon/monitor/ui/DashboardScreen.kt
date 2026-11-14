package com.servermon.monitor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.servermon.monitor.data.AppSettings
import com.servermon.monitor.data.MonitorRepository
import com.servermon.monitor.data.ServerState
import com.servermon.monitor.data.SettingsStore
import com.servermon.monitor.ui.components.MetricBar
import com.servermon.monitor.ui.components.StatusDot
import com.servermon.monitor.ui.components.formatUptime
import com.servermon.monitor.ui.components.severityColor
import com.servermon.monitor.ui.theme.HostsMonitorTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenServer: (String) -> Unit,
    onOpenConnectivity: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val repo = MonitorRepository.get()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val servers by repo.servers.collectAsStateWithLifecycle()
    val states by repo.states.collectAsStateWithLifecycle()
    val settings by remember { SettingsStore(context).settings }
        .collectAsStateWithLifecycle(initialValue = AppSettings())

    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        refreshing = true
        repo.pollAll()
        refreshing = false
        com.servermon.monitor.widget.WidgetUpdater.updateAll(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hosts Monitor") },
                actions = {
                    IconButton(onClick = onOpenConnectivity) {
                        Icon(Icons.Filled.DeviceHub, contentDescription = "Матрица связности")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                    }
                    IconButton(
                        onClick = {
                            refreshing = true
                            scope.launch {
                                repo.pollAll()
                                refreshing = false
                                com.servermon.monitor.widget.WidgetUpdater.updateAll(context)
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
                    }
                }
            )
        }
    ) { padding ->
        if (servers.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Серверов пока нет", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Откройте настройки и добавьте сервер с установленным агентом.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${states.values.count { it.online }} из ${servers.size} онлайн",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (refreshing) {
                            Spacer(Modifier.padding(start = 8.dp))
                            CircularProgressIndicator(modifier = Modifier.height(16.dp).padding(0.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                items(servers, key = { it.id }) { server ->
                    ServerCard(
                        state = states[server.id],
                        states = states,
                        settings = settings,
                        uptime = repo.uptimeOf(server.id),
                        onClick = { onOpenServer(server.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerCard(
    state: ServerState?,
    states: Map<String, ServerState>,
    settings: AppSettings,
    uptime: List<com.servermon.monitor.data.AvailSample>,
    onClick: () -> Unit,
) {
    val server = state?.config ?: return
    val m = state.metrics

    // Relay view: direct poll failed, but a peer agent sees this server alive
    val relay = if (!state.online) {
        states.values.firstOrNull { o ->
            o.online && o.config.id != server.id &&
                o.metrics?.peers?.any { it.name == server.name && it.ok } == true
        }?.let { o ->
            o.config.name to o.metrics!!.peers.first { it.name == server.name }
        }
    } else null
    val viaRelay = relay != null && relay.second.ok

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(online = state.online, colorOverride = if (viaRelay) severityColor(1) else null)
                    Spacer(Modifier.padding(start = 8.dp))
                    Column {
                        Text(server.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                m != null -> m.hostname
                                viaRelay -> "через ${relay!!.first}"
                                else -> state.error ?: ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (m != null) {
                    Text(
                        "↑ " + formatUptime(m.uptimeSec),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            if (m == null) {
                if (viaRelay) {
                    val st = relay!!.second.state
                    Text(
                        buildString {
                            append("Доступен через ${relay.first}")
                            if (st != null) {
                                append("  •  ↑ ${formatUptime(st.uptimeSec)}")
                                append("  •  CPU %.0f%%".format(st.cpuPct))
                                append("  •  RAM %.0f%%".format(st.memPct))
                            } else {
                                append("  •  отвечает на ping")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = severityColor(1),
                    )
                }
                Text(
                    state.error ?: "Нет данных",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                MetricBar(
                    label = "CPU",
                    pct = m.cpu.usagePct,
                    warn = settings.cpuWarnPct,
                    crit = settings.cpuCritPct,
                    detail = "%.0f%%  load %.2f".format(m.cpu.usagePct, m.cpu.loadavg.five),
                )
                Spacer(Modifier.height(8.dp))
                MetricBar(
                    label = "Память",
                    pct = m.memory?.usedPct,
                    warn = settings.memWarnPct,
                    crit = settings.memCritPct,
                    detail = m.memory?.let { "${it.usedMb} / ${it.totalMb} МБ" },
                )
                Spacer(Modifier.height(8.dp))
                val worstDisk = m.disks.maxByOrNull { it.usedPct }
                MetricBar(
                    label = "Диск",
                    pct = worstDisk?.usedPct,
                    warn = settings.diskWarnPct,
                    crit = settings.diskCritPct,
                    detail = worstDisk?.let { "${it.mount}: ${it.usedGb}/${it.totalGb} ГБ" } ?: "—",
                )
            }

            if (uptime.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                com.servermon.monitor.ui.components.UptimeBar(uptime, barHeight = 10.dp)
            }
        }
    }
}
