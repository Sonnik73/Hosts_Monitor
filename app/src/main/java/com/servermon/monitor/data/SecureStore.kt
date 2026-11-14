package com.servermon.monitor.data

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Server list (including auth tokens) in EncryptedSharedPreferences.
 * Falls back to an empty list if decryption fails (e.g. after a backup restore).
 */
class SecureStore(context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "servermon_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun loadServers(): List<ServerConfig> = try {
        val raw = prefs.getString(KEY_SERVERS, null)
        if (raw.isNullOrBlank()) emptyList()
        else json.decodeFromString(ListSerializer(ServerConfig.serializer()), raw)
    } catch (e: Exception) {
        Log.e(TAG, "failed to read servers", e)
        emptyList()
    }

    fun saveServers(servers: List<ServerConfig>) {
        val raw = json.encodeToString(ListSerializer(ServerConfig.serializer()), servers)
        prefs.edit().putString(KEY_SERVERS, raw).apply()
    }

    private companion object {
        const val TAG = "SecureStore"
        const val KEY_SERVERS = "servers_json"
    }
}
