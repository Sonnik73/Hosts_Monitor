package com.servermon.monitor.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Single source of truth for server states and metric history.
 * UI screens and the background worker both talk to this object.
 */
class MonitorRepository private constructor(context: Context) {

    private val secureStore = SecureStore(context.applicationContext)

    private val _servers = MutableStateFlow(secureStore.loadServers())
    val servers: StateFlow<List<ServerConfig>> = _servers

    private val _states = MutableStateFlow<Map<String, ServerState>>(emptyMap())
    val states: StateFlow<Map<String, ServerState>> = _states

    private val history = ConcurrentHashMap<String, ArrayDeque<Sample>>()

    private val lock = ReentrantLock()

    fun getServer(id: String): ServerConfig? = _servers.value.firstOrNull { it.id == id }

    fun getState(id: String): ServerState? = _states.value[id]

    fun historyOf(id: String): List<Sample> =
        history[id]?.toList() ?: emptyList()

    fun addServer(name: String, baseUrl: String, token: String) {
        lock.withLock {
            val current = _servers.value
            val server = ServerConfig(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                baseUrl = baseUrl.trim(),
                token = token.trim(),
            )
            _servers.value = current + server
            secureStore.saveServers(_servers.value)
        }
    }

    fun updateServer(server: ServerConfig) {
        lock.withLock {
            _servers.value = _servers.value.map { if (it.id == server.id) server else it }
            secureStore.saveServers(_servers.value)
        }
    }

    fun deleteServer(id: String) {
        lock.withLock {
            _servers.value = _servers.value.filterNot { it.id == id }
            secureStore.saveServers(_servers.value)
            history.remove(id)
        }
    }

    /** Poll every configured server in parallel; returns the number of online servers. */
    suspend fun pollAll(): Int {
        val targets = _servers.value
        if (targets.isEmpty()) return 0
        val results = coroutineScope {
            targets.map { server -> async { pollOne(server) } }.awaitAll()
        }
        var online = 0
        val map = mutableMapOf<String, ServerState>()
        for (state in results) {
            map[state.config.id] = state
            if (state.online) online++
        }
        _states.value = map
        return online
    }

    /** Poll one server (blocking IO) and update its state + history. Returns new state. */
    suspend fun pollOne(server: ServerConfig): ServerState = withContext(Dispatchers.IO) {
        val updated = try {
            val metrics = AgentClient.fetch(server.baseUrl, server.token)
            recordHistory(server.id, metrics)
            ServerState(config = server, metrics = metrics, error = null, lastUpdate = System.currentTimeMillis())
        } catch (e: Exception) {
            val prev = _states.value[server.id]
            ServerState(config = server, metrics = prev?.metrics, error = e.message ?: e.javaClass.simpleName, lastUpdate = System.currentTimeMillis())
        }
        // publish immediately so UI reflects partial progress
        _states.value = _states.value.toMutableMap().apply { put(server.id, updated) }
        updated
    }

    private fun recordHistory(serverId: String, m: Metrics) {
        val deque = history.getOrPut(serverId) { ArrayDeque() }
        lock.withLock {
            deque.addLast(
                Sample(
                    t = m.timestamp * 1000,
                    cpuPct = m.cpu.usagePct,
                    memPct = m.memory?.usedPct ?: 0.0,
                    rxBps = m.network.rxBps,
                    txBps = m.network.txBps,
                )
            )
            while (deque.size > HISTORY_LIMIT) deque.removeFirst()
        }
    }

    companion object {
        private const val HISTORY_LIMIT = 180 // 180 samples ≈ 3 h of data at 1-min refresh

        @Volatile private var instance: MonitorRepository? = null

        fun init(context: Context): MonitorRepository =
            instance ?: synchronized(this) {
                instance ?: MonitorRepository(context.applicationContext).also { instance = it }
            }

        fun get(): MonitorRepository = instance
            ?: throw IllegalStateException("MonitorRepository.init(context) was not called")
    }
}
