package com.servermon.monitor.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.servermon.monitor.data.MonitorRepository
import com.servermon.monitor.util.AgentSpec
import com.servermon.monitor.util.InstallerGenerator
import com.servermon.monitor.util.PeerSpec

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentInstallerScreen(
    onBack: () -> Unit,
    onAddServer: (name: String, url: String, token: String) -> Unit,
) {
    val repo = MonitorRepository.get()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var name by remember { mutableStateOf("") }
    var listenPort by remember { mutableStateOf("8443") }
    var token by remember { mutableStateOf(InstallerGenerator.randomToken()) }
    var servicesText by remember { mutableStateOf("nginx, ssh") }
    var mountsText by remember { mutableStateOf("/, /var") }
    var appUrl by remember { mutableStateOf("") }
    var peersText by remember {
        val initial = repo.servers.value.joinToString("\n") { s ->
            val host = runCatching { java.net.URI(s.baseUrl).host }.getOrNull() ?: ""
            "${s.name}, ${host}:8443"
        }
        mutableStateOf(initial)
    }
    var script by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-shellscript")
    ) { uri ->
        val s = script
        if (uri != null && s != null) {
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(s.toByteArray(Charsets.UTF_8))
            }
        }
    }

    fun parseSpec(): AgentSpec? {
        val n = name.trim()
        if (n.isBlank()) {
            error = "Введите имя сервера (например: matrix)"
            return null
        }
        val services = servicesText.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val mounts = mountsText.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val peers = peersText.lines().mapNotNull { line ->
            val parts = line.split(',', limit = 2)
            if (parts.size < 2) return@mapNotNull null
            val peerName = parts[0].trim()
            val hostPort = parts[1].trim()
            val idx = hostPort.lastIndexOf(':')
            if (peerName.isEmpty() || idx <= 0) return@mapNotNull null
            PeerSpec(peerName, hostPort.substring(0, idx), hostPort.substring(idx + 1).toIntOrNull() ?: 8443)
        }
        error = null
        return AgentSpec(
            listenPort = listenPort.toIntOrNull() ?: 8443,
            token = token,
            mounts = mounts,
            services = services,
            peers = peers,
        )
    }

    fun shareScript() {
        val s = script ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "servermon-install.sh")
            putExtra(Intent.EXTRA_SUBJECT, "ServerMon agent installer")
            putExtra(Intent.EXTRA_TEXT, s)
        }
        context.startActivity(Intent.createChooser(intent, "Поделиться скриптом установки"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Генератор агента") },
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
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Скрипт-установщик в один файл: агент зашит внутрь вместе с вашим конфигом. " +
                    "Отправьте его на сервер через Termius и запустите.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Имя сервера (matrix, vpn, relay…)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = listenPort,
                onValueChange = { listenPort = it.filter(Char::isDigit) },
                label = { Text("Порт агента") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it.trim() },
                label = { Text("Токен (уже вписан в скрипт)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { token = InstallerGenerator.randomToken() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Новый токен")
                    }
                },
            )
            OutlinedTextField(
                value = servicesText,
                onValueChange = { servicesText = it },
                label = { Text("Сервисы для мониторинга (через запятую)") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = mountsText,
                onValueChange = { mountsText = it },
                label = { Text("Точки монтирования (через запятую)") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = peersText,
                onValueChange = { peersText = it },
                label = { Text("Пиры (по одному в строке: имя, host:port)") },
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text("Имя пира = имя сервера в приложении. Предзаполнено из ваших серверов.")
                },
                minLines = 2,
            )

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = { script = parseSpec()?.let { InstallerGenerator.buildScript(context, it) } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Сгенерировать скрипт")
            }

            if (script != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Как поставить через Termius:", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "1. «Поделиться» → Termius (станет сниппетом) — или «Сохранить файл» и залейте по SFTP в /root.\n" +
                                "2. В терминале сервера:  sudo bash servermon-install.sh\n" +
                                "3. Нажмите «Добавить в приложение» с адресом агента и тем же токеном.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::shareScript, modifier = Modifier.weight(1f)) {
                        Text("Поделиться")
                    }
                    OutlinedButton(
                        onClick = { saveLauncher.launch("servermon-install.sh") },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Сохранить файл")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(script!!)) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Скопировать")
                    }
                    OutlinedButton(
                        onClick = {
                            val url = appUrl.trim()
                            if (url.startsWith("http")) onAddServer(name.trim(), url, token.trim())
                        },
                        enabled = appUrl.trim().startsWith("http"),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Добавить в приложение")
                    }
                }

                OutlinedTextField(
                    value = appUrl,
                    onValueChange = { appUrl = it },
                    label = { Text("URL агента для приложения") },
                    placeholder = { Text("http://10.0.0.10:8443") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        }
    }
}
