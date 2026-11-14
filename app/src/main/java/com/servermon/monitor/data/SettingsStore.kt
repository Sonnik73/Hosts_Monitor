package com.servermon.monitor.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val pollIntervalMinutes: Int = 15,
    val cpuWarnPct: Int = 80,
    val cpuCritPct: Int = 92,
    val memWarnPct: Int = 85,
    val memCritPct: Int = 95,
    val diskWarnPct: Int = 85,
    val diskCritPct: Int = 95,
    val theme: String = "system", // "system" | "light" | "dark"
) {
    companion object {
        /** Severity bucket for a percentage metric: 0 = ok, 1 = warn, 2 = critical. */
        fun severity(pct: Double, warn: Int, crit: Int): Int = when {
            pct >= crit -> 2
            pct >= warn -> 1
            else -> 0
        }
    }
}

/** Non-secret settings in DataStore. Server URLs + tokens live in [SecureStore]. */
class SettingsStore(private val context: Context) {

    private object Keys {
        val POLL_INTERVAL = intPreferencesKey("poll_interval_minutes")
        val CPU_WARN = intPreferencesKey("cpu_warn_pct")
        val CPU_CRIT = intPreferencesKey("cpu_crit_pct")
        val MEM_WARN = intPreferencesKey("mem_warn_pct")
        val MEM_CRIT = intPreferencesKey("mem_crit_pct")
        val DISK_WARN = intPreferencesKey("disk_warn_pct")
        val DISK_CRIT = intPreferencesKey("disk_crit_pct")
        val THEME = stringPreferencesKey("theme")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            pollIntervalMinutes = p[Keys.POLL_INTERVAL] ?: 15,
            cpuWarnPct = p[Keys.CPU_WARN] ?: 80,
            cpuCritPct = p[Keys.CPU_CRIT] ?: 92,
            memWarnPct = p[Keys.MEM_WARN] ?: 85,
            memCritPct = p[Keys.MEM_CRIT] ?: 95,
            diskWarnPct = p[Keys.DISK_WARN] ?: 85,
            diskCritPct = p[Keys.DISK_CRIT] ?: 95,
            theme = p[Keys.THEME] ?: "system",
        )
    }

    suspend fun save(s: AppSettings) {
        context.dataStore.edit { p ->
            p[Keys.POLL_INTERVAL] = s.pollIntervalMinutes
            p[Keys.CPU_WARN] = s.cpuWarnPct
            p[Keys.CPU_CRIT] = s.cpuCritPct
            p[Keys.MEM_WARN] = s.memWarnPct
            p[Keys.MEM_CRIT] = s.memCritPct
            p[Keys.DISK_WARN] = s.diskWarnPct
            p[Keys.DISK_CRIT] = s.diskCritPct
            p[Keys.THEME] = s.theme
        }
    }
}
