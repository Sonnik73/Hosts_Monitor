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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import com.servermon.monitor.data.MonitorRepository
import com.servermon.monitor.net.AgentClient
import com.servermon.monitor.ui.components.severityColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pick services and processes to watch on a server (checkboxes over agent
 * discovery), then save locally and push the selection to the agent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchSelectScreen(serverId: String, onBack: () -> Unit) {
    val repo = MonitorRepository.get()
    val scope = rememberCoroutineScope()
    val server = repo.getServer(serverId)

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var discovery by remember { mutableStateOf<com.servermon.monitor.data.Discovery?>(null) }
    var selectedServices by remember { mutableStateOf(setOf<String>()) }
    var selectedProcesses by remember { mutableStateOf(setOf<String>()) }
    var saving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverId) {
        val s = server
        if (s == null) {
            error = "Сервер не найден"
            loading = false
            return@LaunchedEffect
        }
        selectedServices = s.watchedServices.toSet()
        selectedProcesses = s.watchedProcesses.toSet()
        try {
            val d = withContext(Dispatchers.IO) { AgentClient.discovery(s.baseUrl, s.token) }
            discovery = d
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Отслеживание: ${server?.name ?: ""}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        when {
            loading -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Запрашиваю списки сервисов и процессов…", style = MaterialTheme.typography.bodyMedium)
            }

            discovery == null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Не удалось получить списки", style = MaterialTheme.typography.titleMedium)
                Text(
                    error ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    "Discovery требует агента версии 2+ и прямой доступности сервера с телефона.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                val d = discovery!!
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item {
                        Text(
                            "Сервисы (systemd, ${d.services.size} включённых)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    items(d.services.size) { i ->
                        val svc = d.services[i]
                        CheckRow(
                            checked = svc.unit in selectedServices,
                            label = svc.unit,
                            hint = if (svc.active) "запущен" else "не запущен",
                            hintColor = severityColor(if (svc.active) 0 else 2),
                            onToggle = { on ->
                                selectedServices = if (on) selectedServices + svc.unit else selectedServices - svc.unit
                            }
                        )
                    }

                    item {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Процессы (${d.processes.size} найдено)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    items(d.processes.size) { i ->
                        val p = d.processes[i]
                        CheckRow(
                            checked = p.name in selectedProcesses,
                            label = p.name,
                            hint = "×${p.count} · CPU %.0f%%".format(p.cpuPct),
                            hintColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            onToggle = { on ->
                                selectedProcesses = if (on) selectedProcesses + p.name else selectedProcesses - p.name
                            }
                        )
                    }

                    item {
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                val s = server ?: return@Button
                                saving = true
                                saveMessage = null
                                scope.launch {
                                    try {
                                        repo.updateWatch(s, selectedServices.toList(), selectedProcesses.toList())
                                        val updated = repo.getServer(s.id) ?: s
                                        withContext(Dispatchers.IO) { repo.pushWatchTo(updated) }
                                        saveMessage = "Сохранено и отправлено на агент"
                                        onBack()
                                    } catch (e: Exception) {
                                        saveMessage = "Сохранено локально, но push не удался: ${e.message}"
                                    }
                                    saving = false
                                }
                            },
                            enabled = !saving,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (saving) "Сохраняю…" else "Сохранить (${selectedServices.size} сервисов, ${selectedProcesses.size} процессов)")
                        }
                        saveMessage?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(
    checked: Boolean,
    label: String,
    hint: String,
    hintColor: androidx.compose.ui.graphics.Color,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onToggle)
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = hintColor)
    }
}
