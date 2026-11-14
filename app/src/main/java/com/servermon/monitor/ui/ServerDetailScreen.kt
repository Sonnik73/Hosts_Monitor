package com.servermon.monitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.servermon.monitor.data.AppSettings
import com.servermon.monitor.data.MonitorRepository
import com.servermon.monitor.data.SettingsStore
import com.servermon.monitor.ui.components.MetricBar
import com.servermon.monitor.ui.components.Sparkline
import com.servermon.monitor.ui.components.formatBps
import com.servermon.monitor.ui.components.formatLatency
import com.servermon.monitor.ui.components.formatUptime
import com.servermon.monitor.ui.components.severityColor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDetailScreen(
    serverId: String,
    onBack: () -> Unit,
    onOpenWatch: () -> Unit = {},
) {
    val repo = MonitorRepository.get()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val state by repo.states.collectAsStateWithLifecycle()
    val serverState = state[serverId]
    val settings by androidx.compose.runtime.remember {
        SettingsStore(context).settings
    }.collectAsStateWithLifecycle(initialValue = AppSettings())

    val server = serverState?.config ?: repo.getServer(serverId)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(server?.name ?: "Сервер") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (server != null) {
                        IconButton(onClick = onOpenWatch) {
                            Icon(Icons.Filled.Tune, contentDescription = "Настроить отслеживание")
                        }
                        IconButton(onClick = { scope.launch { repo.pollOne(server) } }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
                        }
                    }
                }
            )
        }
    ) { padding ->
        // Relay view: if the phone can't reach this server but a peer agent can,
        // show the peer's report instead of a raw connection error.
        val relay = if (serverState == null || !serverState.online) {
            val name = server?.name ?: ""
            repo.states.value.values.firstOrNull { o ->
                o.online && o.config.id != serverId &&
                    o.metrics?.peers?.any { it.name == name && it.ok } == true
            }?.let { o -> o.config.name to o.metrics!!.peers.first { it.name == name } }
        } else null

        when {
            serverState != null && serverState.online -> DetailContent(
                serverState = serverState,
                settings = settings,
                history = repo.historyOf(serverId),
                modifier = Modifier.padding(padding),
            )
            relay != null && relay.second.ok -> RelayCard(
                viaName = relay.first,
                state = relay.second.state,
                rawError = serverState?.error,
                modifier = Modifier.padding(padding),
            )
            serverState != null -> Text(
                "Последняя ошибка: ${serverState.error}",
                modifier = Modifier.padding(padding).padding(16.dp),
                color = MaterialTheme.colorScheme.error,
            )
            else -> Text(
                "Нет данных. Нажмите «Обновить».",
                modifier = Modifier.padding(padding).padding(16.dp),
            )
        }
    }
}

@Composable
private fun RelayCard(
    viaName: String,
    state: com.servermon.monitor.data.PeerState?,
    rawError: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Доступен через $viaName",
                        style = MaterialTheme.typography.titleMedium,
                        color = severityColor(1),
                    )
                }
                if (state != null) {
                    KeyValue("Hostname", state.hostname)
                    KeyValue("Uptime", formatUptime(state.uptimeSec))
                    KeyValue("CPU", "%.0f%%".format(state.cpuPct))
                    KeyValue("Память", "%.0f%%".format(state.memPct))
                } else {
                    Text(
                        "Сосед видит этот сервер (TCP), но метрики передать не смог.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "Прямой опрос с телефона сейчас не проходит — состояние передаёт сосед. " +
                        "Это сетевая особенность вашего пути к серверу, не проблема агента.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (rawError != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Последняя ошибка прямого опроса: $rawError",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun DetailContent(
    serverState: com.servermon.monitor.data.ServerState,
    settings: AppSettings,
    history: List<com.servermon.monitor.data.Sample>,
    modifier: Modifier = Modifier,
) {
    val m = serverState.metrics
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (serverState.error != null) {
            item {
                Text(
                    "Последняя ошибка: ${serverState.error}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (m == null) return@LazyColumn

        item {
            Section("Обзор") {
                KeyValue("Hostname", m.hostname)
                KeyValue("Uptime", formatUptime(m.uptimeSec))
                KeyValue("Ядер CPU", m.cpu.cores.toString())
                KeyValue("Соединений (ESTABLISHED)", m.network.established.toString())
                m.cpu.tempC?.let { KeyValue("Температура CPU", "%.0f °C".format(it)) }
            }
        }

        item {
            Section("История (спарклайны)") {
                if (history.size < 2) {
                    Text(
                        "Собираем данные… история появится после нескольких опросов.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("CPU %", style = MaterialTheme.typography.bodySmall)
                    Sparkline(history.map { it.cpuPct }, severityColor(0))
                    Spacer(Modifier.height(4.dp))
                    Text("Память %", style = MaterialTheme.typography.bodySmall)
                    Sparkline(history.map { it.memPct }, severityColor(1))
                    Spacer(Modifier.height(4.dp))
                    Text("Сеть (КБ/с)", style = MaterialTheme.typography.bodySmall)
                    val maxNet = maxOf(
                        history.maxOf { it.rxBps },
                        history.maxOf { it.txBps },
                        1L,
                    ).toDouble()
                    Sparkline(
                        history.map { it.rxBps / 1000.0 },
                        severityColor(0),
                        maxValue = maxNet / 1000.0,
                    )
                    Sparkline(
                        history.map { it.txBps / 1000.0 },
                        severityColor(1),
                        maxValue = maxNet / 1000.0,
                    )
                    KeyValue("RX сейчас", formatBps(m.network.rxBps))
                    KeyValue("TX сейчас", formatBps(m.network.txBps))
                }
            }
        }

        item {
            Section("CPU / Память") {
                MetricBar("CPU", m.cpu.usagePct, settings.cpuWarnPct, settings.cpuCritPct,
                    detail = "%.0f%%  load 1/5/15: %.2f / %.2f / %.2f".format(
                        m.cpu.usagePct, m.cpu.loadavg.one, m.cpu.loadavg.five, m.cpu.loadavg.fifteen))
                MetricBar("Память", m.memory?.usedPct, settings.memWarnPct, settings.memCritPct,
                    detail = m.memory?.let { "${it.usedMb} / ${it.totalMb} МБ" })
                if ((m.memory?.swapTotalMb ?: 0) > 0) {
                    KeyValue("Swap", "${m.memory!!.swapUsedMb} / ${m.memory.swapTotalMb} МБ")
                }
            }
        }

        item {
            Section("Диски") {
                if (m.disks.isEmpty()) {
                    Text("Точки монтирования не настроены в конфиге агента",
                        style = MaterialTheme.typography.bodySmall)
                }
                for (d in m.disks) {
                    MetricBar(d.mount, d.usedPct.takeIf { d.error == null },
                        settings.diskWarnPct, settings.diskCritPct,
                        detail = d.error ?: "%.1f / %.1f ГБ".format(d.usedGb, d.totalGb))
                }
            }
        }

        item {
            Section("Сервисы") {
                if (m.services.isEmpty()) {
                    Text("Список сервисов пуст (настройте services в конфиге агента)",
                        style = MaterialTheme.typography.bodySmall)
                }
                for ((name, status) in m.services) {
                    val ok = status == "active"
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, fontFamily = FontFamily.Monospace)
                        AssistChip(
                            onClick = {},
                            label = { Text(status) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = severityColor(if (ok) 0 else 2).copy(alpha = 0.15f),
                            ),
                        )
                    }
                }
            }
        }

        item {
            Section("Связность (с этого сервера)") {
                if (m.peers.isEmpty()) {
                    Text("Пиры не настроены в конфиге агента", style = MaterialTheme.typography.bodySmall)
                }
                for (p in m.peers) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.foundation.layout.Box(
                                Modifier
                                    .size(10.dp)
                                    .background(severityColor(if (p.ok) 0 else 2), RoundedCornerShape(5.dp))
                            )
                            Spacer(Modifier.padding(start = 8.dp))
                            Text(p.name)
                        }
                        Text(
                            if (p.ok) formatLatency(p.latencyMs) else (p.error ?: "недоступен"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = if (p.ok) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        item {
            Section("Топ процессов") {
                for (p in m.topProcesses) {
                    KeyValue(
                        p.name,
                        "CPU %.1f%% · MEM %.1f%%".format(p.cpuPct, p.memPct),
                    )
                }
            }
        }

        item {
            Section("Отслеживаемые процессы") {
                if (m.watchedProcesses.isEmpty()) {
                    Text(
                        "Не выбраны. Иконка ⚙︎ сверху — выбрать сервисы и процессы.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for ((name, count) in m.watchedProcesses) {
                    KeyValue(
                        name,
                        when {
                            count < 0 -> "неизвестно"
                            count == 0 -> "НЕ ЗАПУЩЕН"
                            else -> "запущен ×$count"
                        },
                    )
                }
            }
        }

        item {
            Section("Безопасность") {
                KeyValue(
                    "Failed SSH (24 ч)",
                    m.failedSshLogins24h?.toString() ?: "journalctl недоступен",
                )
            }
        }
    }
}
