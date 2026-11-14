package com.servermon.monitor.net

import com.servermon.monitor.data.Discovery
import com.servermon.monitor.data.Metrics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Fetches /metrics.json, /discovery.json and pushes watch lists to a ServerMon agent. */
object AgentClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private fun responseOrError(response: okhttp3.Response, body: String): String {
        if (response.isSuccessful) return body
        throw IllegalStateException(
            when (response.code) {
                403 -> "неверный токен агента (HTTP 403)"
                404 -> "агент отвечает, но эндпоинт не найден (HTTP 404) — обновите агента на сервере"
                else -> "HTTP ${response.code}"
            }
        )
    }

    /** Blocking call — invoke from a worker thread. */
    fun fetch(baseUrl: String, token: String): Metrics {
        if (token.isBlank()) {
            throw IllegalStateException(
                "токен не задан — задайте его в настройках сервера или через генератор агента"
            )
        }
        val url = baseUrl.trimEnd('/') + "/metrics.json"
        val request = Request.Builder()
            .url(url)
            .header("X-Auth-Token", token)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
                ?: throw IllegalStateException("empty response (HTTP ${response.code})")
            return json.decodeFromString(
                Metrics.serializer(),
                responseOrError(response, body),
            )
        }
    }

    /** Blocking call — service/process discovery for the watch-selection screen. */
    fun discovery(baseUrl: String, token: String): Discovery {
        val url = baseUrl.trimEnd('/') + "/discovery.json"
        val request = Request.Builder()
            .url(url)
            .header("X-Auth-Token", token)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
                ?: throw IllegalStateException("empty response (HTTP ${response.code})")
            return json.decodeFromString(
                Discovery.serializer(),
                responseOrError(response, body),
            )
        }
    }

    /** Blocking call — persists the watch selection on the agent (POST /watch). */
    fun pushWatch(baseUrl: String, token: String, services: List<String>, processes: List<String>, mounts: List<String>) {
        val url = baseUrl.trimEnd('/') + "/watch"
        val payload = buildJsonObject {
            put("services", kotlinx.serialization.json.JsonArray(services.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("processes", kotlinx.serialization.json.JsonArray(processes.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("mounts", kotlinx.serialization.json.JsonArray(mounts.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }.toString()
        val request = Request.Builder()
            .url(url)
            .header("X-Auth-Token", token)
            .post(payload.toRequestBody(jsonMediaType))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            responseOrError(response, body)
        }
    }
}
