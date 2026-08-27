// Copyright RNA Digital PTY LTD
package ai.monita.sdk

import ai.monita.sdk.internal.MonitaCore
import ai.monita.sdk.internal.MonitaLog
import android.content.Context
import android.content.pm.PackageManager

/**
 * Monita Android SDK: on-device vendor network call monitoring.
 *
 * ```kotlin
 * import ai.monita.sdk.Monita
 *
 * Monita.initialize(context, token = "dom_xxxxxxxxxxxxxxxxxxxxxxxx")
 * ```
 *
 * All methods are safe to call from any thread. Every method is a no-op
 * before initialization; nothing here ever throws into the host app.
 */
object Monita {

    /** SDK version string. */
    const val version: String = MonitaCore.SDK_VERSION

    /** Manifest meta-data key read by the androidx.startup auto initializer. */
    const val TOKEN_META_DATA_KEY: String = "ai.monita.sdk.TOKEN"

    @Volatile
    private var core: MonitaCore? = null

    internal fun coreOrNull(): MonitaCore? = core

    /** Initializes the SDK with a token and default options. Idempotent. */
    @JvmStatic
    fun initialize(context: Context, token: String) {
        initialize(context, MonitaConfig.Builder(token).build())
    }

    /** Initializes the SDK with explicit options. Idempotent. */
    @JvmStatic
    fun initialize(context: Context, config: MonitaConfig) {
        try {
            if (config.token.length < 20) {
                MonitaLog.error("Monita token looks invalid, SDK not started")
                return
            }
            if (!isDefaultProcess(context)) {
                // The persistent queue is single-writer; only the app's
                // default process runs the SDK. Other processes are not
                // monitored (documented in the README).
                MonitaLog.debug { "Monita skipped initialization in a non-default process" }
                return
            }
            synchronized(this) {
                if (core != null) {
                    MonitaLog.debug { "Monita already initialized, ignoring repeat call" }
                    return
                }
                core = MonitaCore(context.applicationContext, config)
            }
        } catch (t: Throwable) {
            MonitaLog.error("Monita initialization failed", t)
        }
    }

    /**
     * Initializes from the manifest meta-data entry ai.monita.sdk.TOKEN.
     * Called by the androidx.startup initializer; also usable directly for a
     * ContentProvider-free manual setup.
     */
    @JvmStatic
    fun initializeFromManifest(context: Context): Boolean {
        return try {
            val app = context.applicationContext
            val info = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)
            val token = info.metaData?.getString(TOKEN_META_DATA_KEY)
            if (token.isNullOrBlank()) {
                false
            } else {
                initialize(app, token)
                true
            }
        } catch (t: Throwable) {
            MonitaLog.error("Manifest initialization failed", t)
            false
        }
    }

    /** Associates events with a host-supplied customer id; null clears it. */
    @JvmStatic
    fun setCustomerId(customerId: String?) {
        safely { it.setCustomerId(customerId) }
    }

    /** Overrides the SDK-generated session id; null returns to automatic rotation. */
    @JvmStatic
    fun setSessionId(sessionId: String?) {
        safely { it.setSessionId(sessionId) }
    }

    /** Overrides consent auto-detection with an explicit consent string; null clears the override. */
    @JvmStatic
    fun setConsent(consent: String?) {
        safely { it.setConsent(consent) }
    }

    /** Registers a closure consulted for the consent string on every batch; wins over setConsent. */
    @JvmStatic
    fun setConsentProvider(provider: (() -> String?)?) {
        safely { it.setConsentProvider(provider) }
    }

    /** Sets the current screen name used in the u and p payload fields; null clears it. */
    @JvmStatic
    fun setScreen(name: String?) {
        safely { it.setScreen(name) }
    }

    /**
     * Registers a payload gate. The closure receives the full event payload;
     * returning false drops the event. Use this to wire a CMP decision in.
     */
    @JvmStatic
    fun setEventFilter(filter: ((Map<String, Any?>) -> Boolean)?) {
        safely { it.setEventFilter(filter) }
    }

    /**
     * Registers a host event extractor consulted before the config template:
     * (vendorName, parsedData) to an event value, or null to fall through.
     */
    @JvmStatic
    fun setEventExtractor(extractor: ((String, Map<String, Any?>) -> Any?)?) {
        safely { it.setEventExtractor(extractor) }
    }

    /** Sends a manual vendor event. Requires allowManualMonitoring in the remote config. */
    @JvmStatic
    @JvmOverloads
    fun send(vendor: String, event: String, data: Map<String, Any?>? = null) {
        safely { it.manualSend(vendor, event, data) }
    }

    /** Stops capture and clears queued events. Persisted across launches. */
    @JvmStatic
    fun optOut() {
        safely { it.optOut() }
    }

    /** Re-enables capture after optOut. Persisted across launches. */
    @JvmStatic
    fun optIn() {
        safely { it.optIn() }
    }

    /** Attempts to upload everything queued right away. */
    @JvmStatic
    fun flush() {
        safely { it.flush() }
    }

    /** Fetches the remote config immediately, bypassing the CDN cache. */
    @JvmStatic
    fun refreshConfig() {
        safely { it.refreshConfig() }
    }

    /** Verbose logging plus unbatched delivery, one POST per event. Default off. */
    @JvmStatic
    fun setDebugLogging(enabled: Boolean) {
        MonitaLog.debugEnabled = enabled
        safely { it.setDebugLogging(enabled) }
    }

    /**
     * True when running in the app's default process (name equals the
     * package name). Unknown process names fail open as default so a
     * platform oddity never silently disables monitoring everywhere.
     */
    private fun isDefaultProcess(context: Context): Boolean = try {
        val processName: String? = if (android.os.Build.VERSION.SDK_INT >= 28) {
            android.app.Application.getProcessName()
        } else {
            val activityThread = Class.forName("android.app.ActivityThread")
            activityThread.getDeclaredMethod("currentProcessName").invoke(null) as? String
        }
        processName == null || processName == context.packageName
    } catch (t: Throwable) {
        true
    }

    /** Tears the SDK down so tests can re-initialize with fresh endpoints. */
    internal fun shutdownForTesting() {
        synchronized(this) {
            core?.shutdown()
            core = null
        }
    }

    private inline fun safely(block: (MonitaCore) -> Unit) {
        try {
            core?.let(block)
        } catch (t: Throwable) {
            MonitaLog.error("Monita API call failed", t)
        }
    }
}
