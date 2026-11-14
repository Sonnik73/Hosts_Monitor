package com.servermon.monitor.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

// ---------------------------------------------------------------------------
// DTOs mirroring the agent's /metrics.json payload (agent/agent.py)
// ---------------------------------------------------------------------------

@Serializable
data class Metrics(
    @SerialName("agent_version") val agentVersion: Int = 1,
    val timestamp: Long = 0,
    val hostname: String = "",
    @SerialName("uptime_sec") val uptimeSec: Long = 0,
    val cpu: CpuInfo = CpuInfo(),
    val memory: MemoryInfo? = null,
    val disks: List<DiskInfo> = emptyList(),
    val network: NetworkInfo = NetworkInfo(),
    val services: Map<String, String> = emptyMap(),
    @SerialName("watched_processes") val watchedProcesses: Map<String, Int> = emptyMap(),
    val peers: List<PeerCheck> = emptyList(),
    @SerialName("top_processes") val topProcesses: List<ProcessInfo> = emptyList(),
    @SerialName("failed_ssh_logins_24h") val failedSshLogins24h: Int? = null,
)

@Serializable
data class CpuInfo(
    val cores: Int = 0,
    @SerialName("usage_pct") val usagePct: Double = 0.0,
    @Serializable(with = LoadAvgSerializer::class) val loadavg: LoadAvg = LoadAvg(),
    @SerialName("temp_c") val tempC: Double? = null,
)

@Serializable
data class LoadAvg(val one: Double = 0.0, val five: Double = 0.0, val fifteen: Double = 0.0) {
    val max: Double get() = maxOf(one, five, fifteen)
}

/** The agent emits loadavg as {"1": .., "5": .., "15": ..} — not valid Kotlin identifiers. */
object LoadAvgSerializer : KSerializer<LoadAvg> {
    private val delegate = MapSerializer(String.serializer(), Double.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): LoadAvg {
        val obj = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        fun v(key: String) = (obj[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        return LoadAvg(one = v("1"), five = v("5"), fifteen = v("15"))
    }

    override fun serialize(encoder: Encoder, value: LoadAvg) {
        encoder.encodeSerializableValue(
            delegate,
            mapOf("1" to value.one, "5" to value.five, "15" to value.fifteen),
        )
    }
}

@Serializable
data class MemoryInfo(
    @SerialName("total_mb") val totalMb: Long = 0,
    @SerialName("used_mb") val usedMb: Long = 0,
    @SerialName("used_pct") val usedPct: Double = 0.0,
    @SerialName("swap_total_mb") val swapTotalMb: Long = 0,
    @SerialName("swap_used_mb") val swapUsedMb: Long = 0,
)

@Serializable
data class DiskInfo(
    val mount: String = "",
    @SerialName("total_gb") val totalGb: Double = 0.0,
    @SerialName("used_gb") val usedGb: Double = 0.0,
    @SerialName("used_pct") val usedPct: Double = 0.0,
    val error: String? = null,
)

@Serializable
data class NetworkInfo(
    @SerialName("rx_bps") val rxBps: Long = 0,
    @SerialName("tx_bps") val txBps: Long = 0,
    val established: Int = 0,
)

@Serializable
data class PeerCheck(
    val name: String = "",
    val ok: Boolean = false,
    @SerialName("latency_ms") val latencyMs: Double? = null,
    val error: String? = null,
    /** Relayed summary of the peer, present when the agent could fetch it over HTTP. */
    val state: PeerState? = null,
)

@Serializable
data class PeerState(
    val hostname: String = "",
    @SerialName("uptime_sec") val uptimeSec: Long = 0,
    @SerialName("cpu_pct") val cpuPct: Double = 0.0,
    @SerialName("mem_pct") val memPct: Double = 0.0,
)

/** Payload of GET /discovery.json — what the user can pick to watch. */
@Serializable
data class Discovery(
    val hostname: String = "",
    val ips: List<String> = emptyList(),
    val services: List<ServiceUnit> = emptyList(),
    val processes: List<ProcStat> = emptyList(),
)

@Serializable
data class ServiceUnit(
    val unit: String = "",
    val active: Boolean = false,
)

@Serializable
data class ProcStat(
    val name: String = "",
    val count: Int = 0,
    @SerialName("cpu_pct") val cpuPct: Double = 0.0,
    @SerialName("mem_pct") val memPct: Double = 0.0,
)

@Serializable
data class ProcessInfo(
    @SerialName("cpu_pct") val cpuPct: Double = 0.0,
    @SerialName("mem_pct") val memPct: Double = 0.0,
    val name: String = "",
)

// ---------------------------------------------------------------------------
// App-side configuration and runtime state
// ---------------------------------------------------------------------------

@Serializable
data class ServerConfig(
    val id: String,
    val name: String,
    /** Agent base URL, e.g. http://10.0.0.10:8443 or https://matrix.example.com/agent */
    val baseUrl: String,
    val token: String,
    /** Services the user picked to watch (pushed to the agent's watch.json). */
    val watchedServices: List<String> = emptyList(),
    /** Process names the user picked to watch. */
    val watchedProcesses: List<String> = emptyList(),
)

/** One poll result for a server, kept in the repository's state flow. */
data class ServerState(
    val config: ServerConfig,
    val metrics: Metrics? = null,
    val error: String? = null,
    val lastUpdate: Long = 0,
) {
    val online: Boolean get() = metrics != null
}

/** Ring-buffer sample for sparkline history. */
data class Sample(
    val t: Long,
    val cpuPct: Double,
    val memPct: Double,
    val rxBps: Long,
    val txBps: Long,
)

/** Persisted alert state used to de-duplicate notifications between worker runs. */
@Serializable
data class AlertState(
    val online: Boolean = true,
    val badServices: Set<String> = emptySet(),
)

/** Availability sample: state 0 = down, 1 = up, 2 = up (via relay). */
@Serializable
data class AvailSample(val t: Long, val state: Int)
