package com.servermon.monitor.net

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * In-app update from the public GitHub release (tag `apk`).
 * No token needed: the repository is public.
 */
object UpdateChecker {

    private const val RELEASE_API =
        "https://api.github.com/repos/Sonnik73/Hosts_Monitor/releases/tags/apk"

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Long,
        val apkName: String,
        val apkUrl: String,
    )

    /** Blocking; call from IO. Returns null when the release has no version.json (old layout). */
    fun checkLatest(): UpdateInfo? {
        val request = Request.Builder().url(RELEASE_API).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("GitHub API: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: return null
            val root = json.parseToJsonElement(body).jsonObject
            val assets = root["assets"] as? List<*>
            var versionFile: JsonPrimitive? = null
            var apkUrl: String? = null
            for (a in assets.orEmpty()) {
                val asset = (a as? kotlinx.serialization.json.JsonObject) ?: continue
                val name = (asset["name"] as? JsonPrimitive)?.content ?: continue
                val url = (asset["browser_download_url"] as? JsonPrimitive)?.content
                if (name == "version.json") versionFile = asset["browser_download_url"] as? JsonPrimitive
                if (name.endsWith(".apk")) apkUrl = url
            }
            val vfUrl = versionFile?.content ?: return null
            client.newCall(Request.Builder().url(vfUrl).get().build()).execute().use { vfResp ->
                if (!vfResp.isSuccessful) return null
                val vfBody = vfResp.body?.string() ?: return null
                val info = json.parseToJsonElement(vfBody).jsonObject
                val apkName = (info["apk"] as? JsonPrimitive)?.content
                    ?: throw IllegalStateException("version.json: нет имени APK")
                val url = assets.orEmpty().firstOrNull { a ->
                    ((a as? kotlinx.serialization.json.JsonObject)?.get("name") as? JsonPrimitive)?.content == apkName
                }?.let { ((it as kotlinx.serialization.json.JsonObject)["browser_download_url"] as? JsonPrimitive)?.content }
                    ?: apkUrl
                    ?: throw IllegalStateException("не найден APK $apkName в релизе")
                return UpdateInfo(
                    versionName = (info["versionName"] as? JsonPrimitive)?.content ?: "?",
                    versionCode = (info["versionCode"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0,
                    apkName = apkName,
                    apkUrl = url,
                )
            }
        }
    }

    /**
     * Blocking; downloads APK into cacheDir/updates. Returns the file.
     * [onProgress] receives 0..1 — Content-Length от GitHub редиректа есть, но при нуле прогресс не считается.
     */
    fun downloadApk(
        context: Context,
        url: String,
        name: String,
        onProgress: (Float) -> Unit = {},
    ): java.io.File {
        val dir = java.io.File(context.cacheDir, "updates").apply { mkdirs() }
        // clear previous downloads
        dir.listFiles()?.forEach { it.delete() }
        val target = java.io.File(dir, name)
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("скачивание: HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("скачивание: пустой ответ")
            val total = body.contentLength().takeIf { it > 0 }
            val buf = ByteArray(64 * 1024)
            var done = 0L
            body.byteStream().use { input ->
                java.io.FileOutputStream(target).use { output ->
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        done += read
                        if (total != null) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
        return target
    }
}
