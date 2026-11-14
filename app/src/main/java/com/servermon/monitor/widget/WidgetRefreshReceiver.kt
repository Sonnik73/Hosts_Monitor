package com.servermon.monitor.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.servermon.monitor.work.PollWorker

/** Widget ↻ button: run one poll cycle now (result reaches the widget via PollWorker). */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<PollWorker>().build())
    }
}
