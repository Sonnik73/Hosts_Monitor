package com.servermon.monitor.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.servermon.monitor.R
import com.servermon.monitor.data.AlertState

/** Builds and posts alert notifications; stores per-server state to avoid spam. */
object AlertNotifier {

    private const val CHANNEL_ID = "servermon_alerts"
    private const val PREFS = "servermon_alerts"

    /** Called after every successful pollAll with fresh states. */
    fun onPollResult(
        context: Context,
        serverId: String,
        serverName: String,
        online: Boolean,
        badServices: Set<String>,
    ) {
        ensureChannel(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = readState(prefs, serverId)
        val current = AlertState(online = online, badServices = badServices)

        if (previous != null) {
            if (previous.online && !current.online) {
                notify(context, serverId.hashCode(), "$serverName недоступен", "Сервер не отвечает на опрос")
            } else if (!previous.online && current.online) {
                notify(context, serverId.hashCode(), "$serverName снова в сети", "Агент отвечает")
            }
            val newDown = current.badServices - previous.badServices
            for (svc in newDown) {
                notify(context, (serverId + svc).hashCode(), "$serverName: сервис упал", "$svc не активен")
            }
            val recovered = previous.badServices - current.badServices
            for (svc in recovered) {
                notify(context, (serverId + svc).hashCode(), "$serverName: сервис восстановлен", "$svc снова активен")
            }
        }

        prefs.edit().putString(KEY_STATE_PREFIX + serverId, encode(current)).apply()
    }

    private const val KEY_STATE_PREFIX = "state_"

    private fun readState(prefs: android.content.SharedPreferences, serverId: String): AlertState? {
        val raw = prefs.getString(KEY_STATE_PREFIX + serverId, null) ?: return null
        return runCatching { decode(raw) }.getOrNull()
    }

    private fun encode(state: AlertState): String =
        kotlinx.serialization.json.Json.encodeToString(AlertState.serializer(), state)

    private fun decode(raw: String): AlertState =
        kotlinx.serialization.json.Json.decodeFromString(AlertState.serializer(), raw)

    private fun notify(context: Context, id: Int, title: String, text: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_status_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_alerts),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_channel_alerts_desc)
        }
        manager.createNotificationChannel(channel)
    }
}
