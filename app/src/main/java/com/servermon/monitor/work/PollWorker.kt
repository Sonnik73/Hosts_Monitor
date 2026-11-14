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
            val states = repo.states.value
            for ((id, state) in states) {
                // Relay view: unreachable from the phone but alive from a peer agent
                val relayOnline = !state.online && states.values.any { other ->
                    other.online && other.config.id != id &&
                        other.metrics?.peers?.any { it.name == state.config.name && it.ok } == true
                }
                val watched = state.config.watchedServices
                val badServices = if (state.online) {
                    state.metrics?.services
                        ?.filter { (name, status) ->
                            (watched.isEmpty() || name in watched) && status != "active"
                        }
                        ?.keys
                        .orEmpty()
                } else {
                    emptySet()
                }
                AlertNotifier.onPollResult(
                    context = context,
                    serverId = id,
                    serverName = state.config.name,
                    online = state.online || relayOnline,
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
