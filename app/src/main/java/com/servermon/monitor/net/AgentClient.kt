package com.servermon.monitor.net

import com.servermon.monitor.data.Metrics
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Fetches /metrics.json from a ServerMon agent. */
object AgentClient {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Blocking call — invoke from a worker thread. */
    fun fetch(baseUrl: String, token: String): Metrics {
        val url = baseUrl.trimEnd('/') + "/metrics.json"
        val request = Request.Builder()
            .url(url)
            .header("X-Auth-Token", token)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
                ?: throw IllegalStateException("empty response (HTTP ${response.code})")
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    when (response.code) {
                        403 -> "неверный токен агента (HTTP 403)"
                        404 -> "агент отвечает, но /metrics.json не найден (HTTP 404)"
                        else -> "HTTP ${response.code}"
                    }
                )
            }
            return json.decodeFromString(Metrics.serializer(), body)
        }
    }
}
