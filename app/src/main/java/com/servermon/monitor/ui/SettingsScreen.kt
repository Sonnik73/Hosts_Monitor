package com.servermon.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.OutlinedButton
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
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenInstaller: () -> Unit = {}) {
    val repo = MonitorRepository.get()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }
    val settings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val servers by repo.servers.collectAsStateWithLifecycle()
    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

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
                Text(
                    "Hosts Monitor v$appVersion",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                UpdateSection(appVersion)
            }
            item {
                Card(
        Modifier.fillMaxWidth(),
        shape = com.servermon.monitor.ui.theme.CardShape,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
    ) {
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
                Card(
        Modifier.fillMaxWidth(),
        shape = com.servermon.monitor.ui.theme.CardShape,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
    ) {
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
                AppearanceSection(settings, settingsStore, scope, context)
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
private fun UpdateSection(currentVersion: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Сборка публикуется в GitHub Releases при каждом изменении.") }
    var updateInfo by remember { mutableStateOf<com.servermon.monitor.net.UpdateChecker.UpdateInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }

    Card(
        Modifier.fillMaxWidth(),
        shape = com.servermon.monitor.ui.theme.CardShape,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Обновление", style = MaterialTheme.typography.titleMedium)
            Text(
                "Текущая версия: v$currentVersion",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            updateInfo?.let { info ->
                Text(
                    "Доступна версия v${info.versionName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val latest = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    com.servermon.monitor.net.UpdateChecker.checkLatest()
                                }
                                val currentCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(
                                    context.packageManager.getPackageInfo(context.packageName, 0)
                                )
                                when {
                                    latest == null -> status = "В релизе нет version.json — обновитесь вручную один раз"
                                    latest.versionCode > currentCode -> {
                                        updateInfo = latest
                                        status = "Найдено обновление до v${latest.versionName}"
                                    }
                                    else -> {
                                        updateInfo = null
                                        status = "У вас последняя версия (v$currentVersion)"
                                    }
                                }
                            } catch (e: Exception) {
                                status = "Ошибка проверки: ${e.message}"
                            }
                            busy = false
                        }
                    }
                ) {
                    Text("Проверить")
                }
                Button(
                    enabled = !busy && updateInfo != null,
                    onClick = {
                        val info = updateInfo ?: return@Button
                        busy = true
                        downloadProgress = 0f
                        status = "Скачиваю ${info.apkName}…"
                        scope.launch {
                            try {
                                val file = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    com.servermon.monitor.net.UpdateChecker.downloadApk(
                                        context, info.apkUrl, info.apkName,
                                    ) { fraction -> downloadProgress = fraction }
                                }
                                downloadProgress = null
                                status = "Запускаю установку через Android…"
                                installApk(context, file)
                                status = "Установщик открыт — подтвердите установку"
                            } catch (e: Exception) {
                                downloadProgress = null
                                status = "Ошибка: ${e.message}"
                            }
                            busy = false
                        }
                    }
                ) {
                    Text(if (busy) "…" else "Установить")
                }
            }

            downloadProgress?.let { p ->
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Загружено: %.0f%%".format(p * 100),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun installApk(context: android.content.Context, file: java.io.File) {
    if (!context.packageManager.canRequestPackageInstalls()) {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:com.servermon.monitor"),
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return
    }
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, "com.servermon.monitor.fileprovider", file
    )
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        )
    context.startActivity(intent)
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
private fun AppearanceSection(
    settings: AppSettings,
    settingsStore: SettingsStore,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = com.servermon.monitor.ui.theme.CardShape,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Оформление", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("Тема", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            for ((label, value) in listOf("Как в системе" to "system", "Светлая" to "light", "Тёмная" to "dark")) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 0.dp)) {
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

            Spacer(Modifier.height(12.dp))
            Text("Палитра", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (palette in com.servermon.monitor.ui.theme.ThemePalette.entries) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()) {
                    RadioButton(
                        selected = settings.palette == palette.name,
                        onClick = {
                            scope.launch {
                                settingsStore.save(settings.copy(palette = palette.name))
                                PollWorker.schedule(context, settings.pollIntervalMinutes.toLong())
                            }
                        }
                    )
                    Text(palette.title)
                    Spacer(Modifier.weight(1f))
                    PaletteDot(palette)
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Чёрный AMOLED-фон", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.Switch(
                    checked = settings.pureBlack,
                    onCheckedChange = { on ->
                        scope.launch {
                            settingsStore.save(settings.copy(pureBlack = on))
                            PollWorker.schedule(context, settings.pollIntervalMinutes.toLong())
                        }
                    }
                )
            }
            Text(
                "Только для тёмной темы: фон полностью чёрный, экран экономит заряд на OLED.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Кружок-превью палитры: два кружка — акцент тёмной и светлой темы. */
@Composable
private fun PaletteDot(palette: com.servermon.monitor.ui.theme.ThemePalette) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(18.dp)
                .background(palette.darkPrimary, CircleShape)
        )
        Spacer(Modifier.padding(start = 4.dp))
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(18.dp)
                .background(palette.lightPrimary, CircleShape)
        )
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
