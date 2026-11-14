package com.servermon.monitor.data

import android.content.Context
import com.servermon.monitor.net.AgentClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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
    private val uptimeFile = java.io.File(context.applicationContext.filesDir, "uptime_history.json")
    private val uptimeJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val uptime = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<AvailSample>>()

    init {
        loadUptime()
    }

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

    fun uptimeOf(id: String): List<AvailSample> =
        uptime[id]?.toList() ?: emptyList()

    /**
     * Record availability for every server after a poll cycle.
     * 0 = down, 1 = up, 2 = up via relay (phone can't reach it, a peer can).
     */
    suspend fun recordAvailability(states: Map<String, ServerState>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        for ((id, state) in states) {
            val relay = states.values.any { o ->
                o.online && o.config.id != id &&
                    o.metrics?.peers?.any { it.name == state.config.name && it.ok } == true
            }
            val st = when {
                state.online -> 1
                relay -> 2
                else -> 0
            }
            val deque = uptime.getOrPut(id) { ArrayDeque() }
            lock.withLock {
                deque.addLast(AvailSample(now, st))
                while (deque.size > UPTIME_LIMIT) deque.removeFirst()
            }
        }
        saveUptime()
    }

    private fun saveUptime() {
        runCatching {
            val serializer = MapSerializer(String.serializer(), ListSerializer(AvailSample.serializer()))
            val map = uptime.entries.associate { it.key to it.value.toList() }
            uptimeFile.writeText(uptimeJson.encodeToString(serializer, map))
        }
    }

    private fun loadUptime() {
        runCatching {
            val raw = uptimeFile.readText()
            val serializer = MapSerializer(String.serializer(), ListSerializer(AvailSample.serializer()))
            val map = uptimeJson.decodeFromString(serializer, raw)
            for ((id, list) in map) {
                val deque = ArrayDeque<AvailSample>()
                list.takeLast(UPTIME_LIMIT).forEach { deque.addLast(it) }
                uptime[id] = deque
            }
        }
    }

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

    /** Persist the watch selection for a server (UI state). Push to agent separately. */
    fun updateWatch(server: ServerConfig, services: List<String>, processes: List<String>) {
        updateServer(server.copy(watchedServices = services, watchedProcesses = processes))
    }

    /** Blocking POST /watch to the agent — call from IO. */
    suspend fun pushWatchTo(server: ServerConfig) = withContext(Dispatchers.IO) {
        AgentClient.pushWatch(
            baseUrl = server.baseUrl,
            token = server.token,
            services = server.watchedServices,
            processes = server.watchedProcesses,
            mounts = emptyList(),
        )
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
        recordAvailability(map)
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
        private const val UPTIME_LIMIT = 720  // ≈ 7.5 days at the 15-min background interval

        @Volatile private var instance: MonitorRepository? = null

        fun init(context: Context): MonitorRepository =
            instance ?: synchronized(this) {
                instance ?: MonitorRepository(context.applicationContext).also { instance = it }
            }

        fun get(): MonitorRepository = instance
            ?: throw IllegalStateException("MonitorRepository.init(context) was not called")
    }
}
