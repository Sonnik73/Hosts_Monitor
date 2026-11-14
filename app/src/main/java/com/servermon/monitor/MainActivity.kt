package com.servermon.monitor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.servermon.monitor.data.AppSettings
import com.servermon.monitor.data.SettingsStore
import com.servermon.monitor.ui.ConnectivityScreen
import com.servermon.monitor.ui.DashboardScreen
import com.servermon.monitor.ui.ServerDetailScreen
import com.servermon.monitor.ui.SettingsScreen
import com.servermon.monitor.ui.theme.HostsMonitorTheme
import com.servermon.monitor.work.PollWorker

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            val context = LocalContext.current
            val settings by remember { SettingsStore(context).settings }
                .collectAsStateWithLifecycle(initialValue = AppSettings())

            LaunchedEffect(Unit) {
                PollWorker.schedule(context, settings.pollIntervalMinutes.toLong())
            }

            HostsMonitorTheme(themeMode = settings.theme) {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "dashboard") {
                    composable("dashboard") {
                        DashboardScreen(
                            onOpenServer = { id -> nav.navigate("server/$id") },
                            onOpenConnectivity = { nav.navigate("connectivity") },
                            onOpenSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("server/{id}") { backStack ->
                        val id = backStack.arguments?.getString("id") ?: ""
                        ServerDetailScreen(serverId = id, onBack = { nav.popBackStack() })
                    }
                    composable("connectivity") {
                        ConnectivityScreen(onBack = { nav.popBackStack() })
                    }
                    composable("settings") {
                        SettingsScreen(onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
