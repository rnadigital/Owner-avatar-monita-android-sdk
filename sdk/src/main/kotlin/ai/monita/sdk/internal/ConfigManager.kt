// Copyright RNA Digital PTY LTD
package ai.monita.sdk.internal

import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Remote config lifecycle: cached start, ETag conditional fetches, periodic
 * refresh, explicit refresh with a cache busting version parameter, and the
 * kill switch (HTTP 404/410 or a monitoringStatus of "paused" or "removed").
 *
 * Routine fetches are plain GETs with If-None-Match so they stay CDN
 * cacheable; only an explicit refresh call appends ?v={configVersion}.
 */
internal class ConfigManager(
    directory: File,
    private val client: OkHttpClient,
    /** Full config JSON URL, e.g. https://cdn.monita.ai/custom-config/{token}.json */
    private val configUrl: String,
) {

    internal sealed interface Result {
        data class Applied(val config: RemoteConfig) : Result
        data object NotModified : Result
        data class Killed(val removed: Boolean) : Result
        data object Failed : Result
    }

    private val configFile = File(directory, "config.json")
    private val etagFile = File(directory, "config.etag")

    @Volatile
    var current: RemoteConfig? = null
        private set

    @Volatile
    var killed: Boolean = false
        private set

    /** Loads the last good cached config, if any. Fast, no network. */
    fun loadCache(): RemoteConfig? {
        return try {
            if (!configFile.exists()) return null
            val config = decodeRemoteConfig(configFile.readText())
            when (config.monitoringStatus) {
                "paused", "removed" -> {
                    killed = true
                    null
                }
                else -> {
                    current = config
                    config
                }
            }
        } catch (t: Throwable) {
            MonitaLog.error("Failed to load cached config", t)
            null
        }
    }

    fun fetchUrl(explicitRefresh: Boolean): String {
        if (!explicitRefresh) return configUrl
        val version = current?.monitoringVersion
        if (version.isNullOrEmpty()) return configUrl
        val separator = if (configUrl.contains('?')) "&" else "?"
        return "$configUrl${separator}v=$version"
    }

    /** Blocking fetch; call from the SDK's own background dispatcher only. */
    fun fetch(explicitRefresh: Boolean = false): Result {
        return try {
            val builder = Request.Builder().url(fetchUrl(explicitRefresh)).get()
            val etag = try {
                if (etagFile.exists()) etagFile.readText().trim() else null
            } catch (t: Throwable) {
                null
            }
            if (!explicitRefresh && !etag.isNullOrEmpty()) {
                builder.header("If-None-Match", etag)
            }
            client.newCall(builder.build()).execute().use { response ->
                when {
                    response.code == 304 -> Result.NotModified
                    response.code == 404 || response.code == 410 -> {
                        killed = true
                        current = null
                        wipeCache()
                        Result.Killed(removed = true)
                    }
                    response.isSuccessful -> {
                        val body = response.body?.string() ?: return Result.Failed
                        val config = decodeRemoteConfig(body)
                        when (config.monitoringStatus) {
                            "paused" -> {
                                killed = true
                                Result.Killed(removed = false)
                            }
                            "removed" -> {
                                killed = true
                                current = null
                                wipeCache()
                                Result.Killed(removed = true)
                            }
                            else -> {
                                killed = false
                                current = config
                                persist(body, response.header("ETag"))
                                Result.Applied(config)
                            }
                        }
                    }
                    else -> Result.Failed
                }
            }
        } catch (t: Throwable) {
            MonitaLog.debug { "Config fetch failed: ${t.message}" }
            Result.Failed
        }
    }

    private fun persist(body: String, etag: String?) {
        try {
            configFile.parentFile?.mkdirs()
            configFile.writeText(body)
            if (etag != null) etagFile.writeText(etag) else etagFile.delete()
        } catch (t: Throwable) {
            MonitaLog.error("Failed to persist config cache", t)
        }
    }

    private fun wipeCache() {
        try {
            configFile.delete()
            etagFile.delete()
        } catch (t: Throwable) {
        }
    }
}
