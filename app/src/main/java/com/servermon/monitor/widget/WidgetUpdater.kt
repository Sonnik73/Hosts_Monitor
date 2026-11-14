package com.servermon.monitor.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.servermon.monitor.MainActivity
import com.servermon.monitor.R
import com.servermon.monitor.data.MonitorRepository

/** Renders the home-screen widget from the repository's latest poll results. */
object WidgetUpdater {

    private const val MAX_ROWS = 5

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, WidgetHosts::class.java))
        if (ids.isEmpty()) return

        val repo = MonitorRepository.get()
        val servers = repo.servers.value
        val states = repo.states.value

        val views = RemoteViews(context.packageName, R.layout.widget_hosts)
        views.setTextViewText(R.id.widget_title, "Hosts Monitor")
        views.setTextViewText(
            R.id.widget_summary,
            "${states.values.count { it.online }} из ${servers.size} онлайн"
        )

        views.removeAllViews(R.id.widget_list)
        for (server in servers.take(MAX_ROWS)) {
            val state = states[server.id]
            val m = state?.metrics
            // 0 = down, 1 = up, 2 = up via relay
            val status = when {
                state == null -> 0
                state.online -> 1
                states.values.any { o ->
                    o.online && o.config.id != server.id &&
                        o.metrics?.peers?.any { it.name == server.name && it.ok } == true
                } -> 2
                else -> 0
            }
            val row = RemoteViews(context.packageName, R.layout.widget_row)
            row.setTextViewText(R.id.row_name, server.name)
            row.setTextViewText(R.id.row_dot, when (status) {
                1 -> "●"
                2 -> "◐"
                else -> "●"
            })
            row.setTextColor(R.id.row_dot, dotColor(status))
            views.addView(R.id.widget_list, row)
        }
        if (servers.isEmpty()) {
            val row = RemoteViews(context.packageName, R.layout.widget_row)
            row.setTextViewText(R.id.row_name, "Добавьте серверы в приложении")
            row.setTextColor(R.id.row_dot, 0xFF9AA5AD.toInt())
            views.addView(R.id.widget_list, row)
        }

        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_root, openApp)

        val refresh = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, WidgetRefreshReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_refresh, refresh)

        manager.updateAppWidget(ids, views)
    }

    private fun dotColor(status: Int): Int = when (status) {
        1 -> 0xFF43A047.toInt() // green
        2 -> 0xFFFFB300.toInt() // amber
        else -> 0xFFE53935.toInt() // red
    }
}
