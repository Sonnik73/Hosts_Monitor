package com.servermon.monitor

import android.app.Application
import com.servermon.monitor.data.MonitorRepository
import com.servermon.monitor.work.AlertNotifier

class MonitorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MonitorRepository.init(this)
        AlertNotifier.ensureChannel(this)
    }
}
