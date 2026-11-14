package com.servermon.monitor.ui

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.servermon.monitor.data.AppSettings
import com.servermon.monitor.data.MonitorRepository
import com.servermon.monitor.data.ServerConfig
import com.servermon.monitor.data.SettingsStore
import com.servermon.monitor.work.PollWorker
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenInstaller: () -> Unit = {}) {
    val repo = MonitorRepository.get()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }
    val settings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val servers by repo.servers.collectAsStateWithLifecycle()

    var showServerDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<ServerConfig?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editingServer = null; showServerDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Добавить сервер")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Установка агента", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Сгенерировать скрипт-установщик с токеном и отправить на сервер через Termius",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onOpenInstaller) {
                            Text("Генератор агента")
                        }
                    }
                }
            }

            item {
                Text("Серверы", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            items(servers, key = { it.id }) { server ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(server.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                server.baseUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { editingServer = server; showServerDialog = true }) {
                            Text("Изм.")
                        }
                        IconButton(onClick = { repo.deleteServer(server.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Удалить")
                        }
                    }
                }
            }

            item {
                ThresholdsSection(settings, settingsStore, scope, context)
            }

            item {
                ThemeSection(settings, settingsStore, scope, context)
            }
        }
    }

    if (showServerDialog) {
        ServerDialog(
            initial = editingServer,
            onDismiss = { showServerDialog = false },
            onSave = { name, url, token ->
                if (editingServer != null) {
                    repo.updateServer(editingServer!!.copy(name = name, baseUrl = url, token = token))
                } else {
                    repo.addServer(name, url, token)
                }
                showServerDialog = false
                scope.launch { repo.pollAll() }
            }
        )
    }
}

@Composable
private fun ThresholdsSection(
    settings: AppSettings,
    settingsStore: SettingsStore,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
) {
    var interval by remember(settings.pollIntervalMinutes) { mutableStateOf(settings.pollIntervalMinutes.toString()) }
    var cpuWarn by remember(settings.cpuWarnPct) { mutableStateOf(settings.cpuWarnPct.toString()) }
    var cpuCrit by remember(settings.cpuCritPct) { mutableStateOf(settings.cpuCritPct.toString()) }
    var memWarn by remember(settings.memWarnPct) { mutableStateOf(settings.memWarnPct.toString()) }
    var memCrit by remember(settings.memCritPct) { mutableStateOf(settings.memCritPct.toString()) }
    var diskWarn by remember(settings.diskWarnPct) { mutableStateOf(settings.diskWarnPct.toString()) }
    var diskCrit by remember(settings.diskCritPct) { mutableStateOf(settings.diskCritPct.toString()) }

    Column {
        Text("Опрос и пороги", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = interval,
            onValueChange = { interval = it.filter(Char::isDigit) },
            label = { Text("Интервал фонового опроса, мин (мин. 15)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("CPU warn, %", cpuWarn, Modifier.weight(1f)) { cpuWarn = it }
            NumberField("CPU crit, %", cpuCrit, Modifier.weight(1f)) { cpuCrit = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("RAM warn, %", memWarn, Modifier.weight(1f)) { memWarn = it }
            NumberField("RAM crit, %", memCrit, Modifier.weight(1f)) { memCrit = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Диск warn, %", diskWarn, Modifier.weight(1f)) { diskWarn = it }
            NumberField("Диск crit, %", diskCrit, Modifier.weight(1f)) { diskCrit = it }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val updated = settings.copy(
                    pollIntervalMinutes = (interval.toIntOrNull() ?: 15).coerceAtLeast(15),
                    cpuWarnPct = (cpuWarn.toIntOrNull() ?: 80).coerceIn(1, 100),
                    cpuCritPct = (cpuCrit.toIntOrNull() ?: 92).coerceIn(1, 100),
                    memWarnPct = (memWarn.toIntOrNull() ?: 85).coerceIn(1, 100),
                    memCritPct = (memCrit.toIntOrNull() ?: 95).coerceIn(1, 100),
                    diskWarnPct = (diskWarn.toIntOrNull() ?: 85).coerceIn(1, 100),
                    diskCritPct = (diskCrit.toIntOrNull() ?: 95).coerceIn(1, 100),
                )
                scope.launch {
                    settingsStore.save(updated)
                    PollWorker.schedule(context, updated.pollIntervalMinutes.toLong())
                }
            }
        ) {
            Text("Сохранить")
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter(Char::isDigit)) },
        label = { Text(label) },
        modifier = modifier,
        singleLine = true,
    )
}

@Composable
private fun ThemeSection(
    settings: AppSettings,
    settingsStore: SettingsStore,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Тема", style = MaterialTheme.typography.titleSmall)
            for ((label, value) in listOf("Системная" to "system", "Светлая" to "light", "Тёмная" to "dark")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = settings.theme == value,
                        onClick = {
                            scope.launch {
                                settingsStore.save(settings.copy(theme = value))
                                PollWorker.schedule(context, settings.pollIntervalMinutes.toLong())
                            }
                        }
                    )
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun ServerDialog(
    initial: ServerConfig?,
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, token: String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var token by remember { mutableStateOf(initial?.token ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Добавить сервер" else "Изменить сервер") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Имя (например: matrix)") }, singleLine = true,
                )
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("URL агента (http://10.0.0.10:8443)") }, singleLine = true,
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text("Токен (можно оставить пустым — задать позже)") },
                    singleLine = true,
                )
                if (initial == null) {
                    Text(
                        "Токен общий для всех серверов — он появится в генераторе агента, " +
                            "и его можно будет применить ко всем серверам одной кнопкой.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank() && url.isNotBlank()) onSave(name, url, token) }
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
