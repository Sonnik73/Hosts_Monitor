package com.servermon.monitor.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.servermon.monitor.data.MonitorRepository
import java.util.concurrent.TimeUnit

/** Periodic background poll of all agents + alert notifications. */
class PollWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = MonitorRepository.get()
        val context = applicationContext
        return try {
            repo.pollAll()
            for ((id, state) in repo.states.value) {
                val badServices = state.metrics?.services
                    ?.filterValues { it != "active" }
                    ?.keys
                    .orEmpty()
                AlertNotifier.onPollResult(
                    context = context,
                    serverId = id,
                    serverName = state.config.name,
                    online = state.online,
                    badServices = badServices,
                )
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "servermon_poll"

        /** (Re)schedules polling; call on app start and whenever the interval changes. */
        fun schedule(context: Context, intervalMinutes: Long) {
            val request = PeriodicWorkRequestBuilder<PollWorker>(
                maxOf(15, intervalMinutes), TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
