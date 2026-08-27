// Copyright RNA Digital PTY LTD
package ai.monita.sdk.internal

import ai.monita.sdk.MonitaConfig
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * The SDK engine: one instance per process, created by Monita.initialize.
 * All pipeline work runs on a single background dispatcher; capture hot
 * paths only snapshot the request and hand off.
 */
internal class MonitaCore(
    val appContext: Context,
    val settings: MonitaConfig,
) {

    internal companion object {
        const val SDK_VERSION = "2.0.1"
        const val SOURCE = "android-sdk"
        const val DEPLOYMENT_METHOD = "app"
        const val DEFAULT_COLLECT_ENDPOINT = "https://collect.monita.ai/api/v1"
        const val DEFAULT_CONFIG_HOST = "https://cdn.monita.ai"
        const val BATCH_MAX_EVENTS = 10
        const val PENDING_BUFFER_MAX = 50
        const val PENDING_BUFFER_TTL_MS = 30_000L
        const val CONFIG_REFRESH_INTERVAL_MS = 6L * 60 * 60 * 1000

        fun defaultConfigUrl(token: String): String =
            "$DEFAULT_CONFIG_HOST/custom-config/$token.json"

        // Timing overrides applied at construction; tests set these before
        // initializing so no production delay ever races a test assertion.
        internal var testFlushDelayMsOverride: Long? = null
        internal var testEnvDelayMsOverride: Long? = null
    }

    internal data class CapturedCall(
        val url: String,
        val method: String,
        val body: String?,
        val contentType: String?,
        val tm: Double,
        val statusCode: Int?,
        val failed: Boolean,
        val capturedAtMs: Long,
    )

    val collectEndpoint: String = settings.collectEndpoint ?: DEFAULT_COLLECT_ENDPOINT
    val configUrl: String = settings.configEndpoint ?: defaultConfigUrl(settings.token)

    // Test knobs; production values by default.
    internal var flushDelayMs: Long = testFlushDelayMsOverride ?: 2_000
    internal var envDelayMs: Long = testEnvDelayMsOverride ?: 2_500
    internal var backoff: Backoff = Backoff()

    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "monita-sdk").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    internal val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // Everything that touches disk is lazy: the constructor stays on field
    // wiring only, and first access happens on the SDK dispatcher.
    private val storageDir: File by lazy {
        // noBackupFilesDir: captured vendor payloads and cached config must
        // never ride Auto Backup to the cloud or restore onto new devices.
        File(appContext.noBackupFilesDir, "monita")
    }

    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences("ai.monita.sdk", Context.MODE_PRIVATE)
    }

    private val httpClient = OkHttpClient.Builder().build()

    internal val queue: EventQueue by lazy { EventQueue(storageDir) }
    internal val configManager: ConfigManager by lazy { ConfigManager(storageDir, httpClient, configUrl) }
    private val uploader: Uploader by lazy {
        Uploader(httpClient, collectEndpoint, queue, ::recordDroppedEvents, ::isDebugUnbatched)
    }
    private val sessions: SessionManager by lazy { SessionManager(prefs) }
    private val consent: ConsentManager by lazy { ConsentManager(appContext) { triggerUpload() } }
    private val envDetector: EnvDetector by lazy { EnvDetector(appContext, SDK_VERSION) { consent.hasTcfString() } }
    private val circuitBreaker = CircuitBreaker()

    @Volatile
    private var matcher: VendorMatcher? = null

    @Volatile
    private var optedOut: Boolean = false

    @Volatile
    private var screenName: String? = null

    @Volatile
    private var customerId: String? = null

    @Volatile
    private var eventFilter: ((Map<String, Any?>) -> Boolean)? = null

    @Volatile
    private var eventExtractor: ((String, Map<String, Any?>) -> Any?)? = null

    private val pendingCalls = ArrayDeque<CapturedCall>()
    private val uploadWake = Channel<Unit>(Channel.CONFLATED)
    private var flushJob: Job? = null
    private var refreshJob: Job? = null
    private var envReportScheduled = false

    init {
        MonitaLog.debugEnabled = settings.debugLogging || MonitaLog.systemPropertyDebug()
        scope.launch {
            optedOut = prefs.getBoolean("opted_out", optedOut)
            configManager.loadCache()?.let { applyConfig(it) }
            fetchConfig(explicitRefresh = false)
        }
        scope.launch {
            // Ship anything left over from a previous launch once we are up.
            delay(flushDelayMs)
            triggerUpload()
        }
        startUploadLoop()
        registerNetworkCallback()
        registerLifecycleObserver()
    }

    // -- public API surface (thread safe) ------------------------------------

    fun setCustomerId(id: String?) {
        customerId = id
    }

    fun setSessionId(id: String?) {
        sessions.setSessionId(id)
    }

    fun setConsent(value: String?) {
        consent.setConsent(value)
    }

    fun setConsentProvider(provider: (() -> String?)?) {
        consent.setConsentProvider(provider)
    }

    fun setScreen(name: String?) {
        screenName = name?.takeIf { it.isNotBlank() }
    }

    fun setEventFilter(filter: ((Map<String, Any?>) -> Boolean)?) {
        eventFilter = filter
    }

    fun setEventExtractor(extractor: ((String, Map<String, Any?>) -> Any?)?) {
        eventExtractor = extractor
    }

    fun setDebugLogging(enabled: Boolean) {
        MonitaLog.debugEnabled = enabled
    }

    fun optOut() {
        optedOut = true
        prefs.edit().putBoolean("opted_out", true).apply()
        scope.launch { queue.clear() }
    }

    fun optIn() {
        optedOut = false
        prefs.edit().putBoolean("opted_out", false).apply()
    }

    fun flush() {
        triggerUpload()
    }

    fun refreshConfig() {
        scope.launch { fetchConfig(explicitRefresh = true) }
    }

    fun manualSend(vendor: String, event: String, extraData: Map<String, Any?>?) {
        if (optedOut) return
        val capturedAt = nowSeconds()
        scope.launch {
            if (configManager.killed) return@launch
            val config = configManager.current ?: return@launch
            if (!config.allowManualMonitoring) {
                MonitaLog.debug { "Manual monitoring is not enabled for this property" }
                return@launch
            }
            val data = LinkedHashMap<String, Any?>()
            data["vendorName"] = vendor
            data["event"] = event
            if (extraData != null) {
                DataParser.fillDataFromBody(data, normalizeValue(extraData))
            }
            data["__mon_url"] = collectEndpoint
            data["__mon_host"] = hostOf(collectEndpoint)
            data["__mon_method"] = "POST"
            val vendorName = data["vendorName"] as? String ?: vendor
            val vendorConfig = config.vendors.firstOrNull { it.vendorName == vendorName } ?: run {
                MonitaLog.debug { "Manual event for unknown vendor dropped" }
                return@launch
            }
            if (!passesFilters(vendorConfig, data)) return@launch
            sendData(config, vendorConfig, data, tm = capturedAt, status = null, failed = false, includeStatus = false)
        }
    }

    val isKilled: Boolean
        get() = configManager.killed

    val isOptedOut: Boolean
        get() = optedOut

    // -- capture -------------------------------------------------------------

    fun isInternalEndpoint(url: String): Boolean =
        url.contains(collectEndpoint) || url.contains(configUrl)

    /** Hot path entry from the interceptor: snapshot in hand, hand off async. */
    fun onNetworkCall(call: CapturedCall) {
        if (optedOut || configManager.killed || circuitBreaker.tripped) return
        if (isInternalEndpoint(call.url)) return
        scope.launch {
            try {
                val config = configManager.current
                if (config == null) {
                    bufferPending(call)
                } else {
                    evaluate(config, call)
                }
            } catch (t: Throwable) {
                MonitaLog.error("Capture pipeline error", t)
            }
        }
    }

    private fun bufferPending(call: CapturedCall) {
        if (configManager.killed) return
        pendingCalls.addLast(call)
        while (pendingCalls.size > PENDING_BUFFER_MAX) {
            pendingCalls.removeFirst()
        }
    }

    private fun drainPending(config: RemoteConfig) {
        val now = System.currentTimeMillis()
        while (pendingCalls.isNotEmpty()) {
            val call = pendingCalls.removeFirst()
            if (now - call.capturedAtMs <= PENDING_BUFFER_TTL_MS) {
                try {
                    evaluate(config, call)
                } catch (t: Throwable) {
                    MonitaLog.error("Capture pipeline error", t)
                }
            }
        }
    }

    private fun evaluate(config: RemoteConfig, call: CapturedCall) {
        val vendorName = matcher?.match(call.url) ?: return
        val vendorConfig = config.vendors.firstOrNull { it.vendorName == vendorName } ?: return
        val data = LinkedHashMap<String, Any?>()
        try {
            DataParser.getBodyJson(call.url, call.body, data, call.contentType)
        } catch (t: Throwable) {
            MonitaLog.debug { "Body parse failed for ${call.url}: ${t.message}" }
        }
        data["__mon_url"] = call.url
        data["__mon_host"] = hostOf(call.url)
        data["__mon_method"] = call.method
        data["vendorName"] = vendorName
        if (!passesFilters(vendorConfig, data)) {
            MonitaLog.debug { "Filtered out ${vendorName} event for ${call.url}" }
            return
        }
        sendData(config, vendorConfig, data, call.tm, call.statusCode, call.failed, includeStatus = true)
    }

    private fun passesFilters(vendorConfig: VendorConfig, data: Map<String, Any?>): Boolean =
        if (vendorConfig.filterGroups.isNotEmpty()) {
            checkPassOnFilterGroups(data, vendorConfig.filterGroups)
        } else {
            checkPassOnFilters(data, vendorConfig.filters)
        }

    private fun sendData(
        config: RemoteConfig,
        vendorConfig: VendorConfig,
        data: LinkedHashMap<String, Any?>,
        tm: Double,
        status: Int?,
        failed: Boolean,
        includeStatus: Boolean,
    ) {
        if (!circuitBreaker.allow()) return
        val vendorName = vendorConfig.vendorName
        val event: Any? = firstUsable(
            { eventExtractor?.invoke(vendorName, data) },
            { vendorConfig.eventParameter?.let { fillParamsFromData(it, data) } },
            { data["event"] },
            { data["ev"] },
        ) ?: ""
        val updatedData = filterData(vendorName, data, vendorConfig.excludeParameters)
        val shared = buildSharedContext(config)
        val eventBase = LinkedHashMap<String, Any?>()
        eventBase["tm"] = tm
        eventBase["e"] = if (jsTruthy(event)) event else null
        eventBase["vn"] = vendorName
        if (includeStatus && (status != null || failed)) {
            eventBase["st"] = if (failed || (status != null && status >= 400)) "failure" else "success"
        }
        eventBase["m"] = data["__mon_method"] ?: ""
        eventBase["vu"] = data["__mon_url"] ?: ""
        eventBase["dt"] = listOf(updatedData)
        eventBase["np"] = emptyList<Any?>()
        if (event is List<*>) {
            for (element in event) {
                val eventCopy = LinkedHashMap(eventBase)
                eventCopy["e"] = element
                eventCopy["dt"] = listOf(LinkedHashMap(updatedData))
                enqueueIfAllowed(shared, eventCopy)
            }
        } else {
            enqueueIfAllowed(shared, eventBase)
        }
    }

    private fun enqueueIfAllowed(shared: Map<String, Any?>, event: Map<String, Any?>) {
        val filter = eventFilter
        if (filter != null) {
            val payload = LinkedHashMap<String, Any?>(shared)
            payload.putAll(event)
            val allowed = try {
                filter(payload)
            } catch (t: Throwable) {
                true
            }
            if (!allowed) return
        }
        queue.append(shared, event)
        MonitaLog.debug { "Queued event ${event["e"]} for ${event["vn"]}" }
        if (isDebugUnbatched() || queue.size >= BATCH_MAX_EVENTS) {
            triggerUpload()
        } else {
            scheduleDelayedFlush()
        }
    }

    // The av envelope value: versionName plus version code joined with "+",
    // e.g. "1.4.2+387". When only one of the two exists, the name ships alone
    // and a lone code ships as "+code". Null when the package reports neither
    // (bare test environments); the field is then omitted from the envelope.
    internal val appVersion: String? by lazy { detectAppVersion() }

    private fun detectAppVersion(): String? = try {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val name = info.versionName?.takeIf { it.isNotBlank() }
        val code = (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        ).takeIf { it != 0L }?.toString()
        when {
            name != null && code != null -> "$name+$code"
            name != null -> name
            code != null -> "+$code"
            else -> null
        }
    } catch (t: Throwable) {
        null
    }

    internal fun buildSharedContext(config: RemoteConfig): LinkedHashMap<String, Any?> {
        val packageName = appContext.packageName
        val screen = screenName
        val shared = LinkedHashMap<String, Any?>()
        shared["t"] = settings.token
        shared["dm"] = DEPLOYMENT_METHOD
        shared["mv"] = SDK_VERSION
        shared["sv"] = config.monitoringVersion
        shared["u"] = if (screen != null) "app://$packageName/$screen" else "app://$packageName"
        shared["p"] = screen ?: ""
        shared["vid"] = sessions.visitorId
        shared["sid"] = sessions.sessionId()
        shared["s"] = SOURCE
        shared["do"] = packageName
        appVersion?.let { shared["av"] = it }
        shared["rl"] = "android ${Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()}"
        shared["env"] = "production"
        shared["et"] = ""
        shared["cn"] = consent.currentConsent()
        customerId?.let { shared["cid"] = it }
        return shared
    }

    private fun firstUsable(vararg candidates: () -> Any?): Any? {
        for (candidate in candidates) {
            val value = try {
                candidate()
            } catch (t: Throwable) {
                null
            }
            if (!jsLooselyFalse(value)) return value
        }
        return null
    }

    private fun jsTruthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is String -> value.isNotEmpty()
        is Long -> value != 0L
        is Int -> value != 0
        is Double -> value != 0.0
        else -> true
    }

    // -- environment event ---------------------------------------------------

    private fun scheduleEnvReport() {
        if (envReportScheduled) return
        envReportScheduled = true
        scope.launch {
            delay(envDelayMs)
            reportEnvironment()
        }
    }

    private fun reportEnvironment() {
        try {
            val config = configManager.current ?: return
            if (optedOut || configManager.killed) return
            val env = envDetector.detect()
            val snapshot = JsonValue.encode(env)
            val sid = sessions.sessionId()
            val lastSnapshot = prefs.getString("env_snap", null)
            val lastSid = prefs.getString("env_sid", null)
            if (snapshot == lastSnapshot && sid == lastSid) return
            prefs.edit().putString("env_snap", snapshot).putString("env_sid", sid).apply()
            val shared = buildSharedContext(config)
            val event = LinkedHashMap<String, Any?>()
            event["tm"] = nowSeconds()
            event["e"] = "monita_env"
            event["vn"] = "Monita"
            event["dt"] = listOf(env)
            enqueueIfAllowed(shared, event)
        } catch (t: Throwable) {
            MonitaLog.error("Environment report failed", t)
        }
    }

    // -- config --------------------------------------------------------------

    private suspend fun fetchConfig(explicitRefresh: Boolean) {
        // The network round trip runs on the IO pool; only state application
        // returns to the serial dispatcher.
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            configManager.fetch(explicitRefresh)
        }
        when (result) {
            is ConfigManager.Result.Applied -> applyConfig(result.config)
            is ConfigManager.Result.Killed -> {
                MonitaLog.debug { "Monitoring disabled by kill switch (removed=${result.removed})" }
                matcher = null
                pendingCalls.clear()
                queue.clear()
            }
            ConfigManager.Result.NotModified, ConfigManager.Result.Failed -> {}
        }
    }

    private fun applyConfig(config: RemoteConfig) {
        matcher = VendorMatcher(config)
        MonitaLog.debug { "Config ${config.monitoringVersion} applied with ${config.vendors.size} vendors" }
        drainPending(config)
        scheduleEnvReport()
    }

    // -- delivery ------------------------------------------------------------

    internal fun isDebugUnbatched(): Boolean = MonitaLog.debugEnabled

    internal fun triggerUpload() {
        uploadWake.trySend(Unit)
    }

    /** Persistently counts events dropped on permanent collect rejections. */
    private fun recordDroppedEvents(count: Int) {
        try {
            val total = prefs.getLong("dropped_total", 0L) + count
            prefs.edit().putLong("dropped_total", total).apply()
            MonitaLog.error("Dropped $count undeliverable event(s); running total $total")
        } catch (t: Throwable) {
            MonitaLog.error("Dropped $count undeliverable event(s)")
        }
    }

    private fun scheduleDelayedFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(flushDelayMs)
            triggerUpload()
        }
    }

    private fun startUploadLoop() {
        scope.launch {
            for (unused in uploadWake) {
                while (true) {
                    if (optedOut || configManager.killed) break
                    if (queue.size == 0) break
                    if (!isNetworkReachable()) break
                    when (uploader.drainOnce()) {
                        Uploader.DrainResult.Drained -> {
                            backoff.reset()
                            break
                        }
                        Uploader.DrainResult.RetryLater -> {
                            delay(backoff.nextDelayMs())
                        }
                    }
                }
            }
        }
    }

    private fun isNetworkReachable(): Boolean = try {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            true
        } else {
            val active = cm.activeNetwork
            if (active == null) {
                false
            } else {
                val caps = cm.getNetworkCapabilities(active)
                caps == null || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }
    } catch (t: Throwable) {
        true
    }

    private fun registerNetworkCallback() {
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    triggerUpload()
                }
            })
        } catch (t: Throwable) {
            MonitaLog.debug { "Network callback registration failed: ${t.message}" }
        }
    }

    private fun registerLifecycleObserver() {
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                        override fun onStart(owner: LifecycleOwner) {
                            startConfigRefreshTicker()
                        }

                        override fun onStop(owner: LifecycleOwner) {
                            refreshJob?.cancel()
                            refreshJob = null
                            triggerUpload()
                        }
                    })
                } catch (t: Throwable) {
                    MonitaLog.debug { "Lifecycle observer registration failed: ${t.message}" }
                }
            }
        } catch (t: Throwable) {
            MonitaLog.debug { "Lifecycle observer registration failed: ${t.message}" }
        }
    }

    private fun startConfigRefreshTicker() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            while (true) {
                delay(CONFIG_REFRESH_INTERVAL_MS)
                fetchConfig(explicitRefresh = false)
            }
        }
    }

    /** Stops all background work and listeners; used when tearing down in tests. */
    internal fun shutdown() {
        try {
            scope.cancel()
        } catch (t: Throwable) {
        }
        consent.close()
    }

    // -- helpers -------------------------------------------------------------

    private fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0

    internal fun hostOf(url: String): String = try {
        var s = url.substringBefore('#').substringBefore('?')
        val schemeIdx = s.indexOf("://")
        if (schemeIdx >= 0) s = s.substring(schemeIdx + 3)
        s.substringBefore('/')
    } catch (t: Throwable) {
        ""
    }

    private fun normalizeValue(value: Any?): Any? = when (value) {
        null, is String, is Boolean, is Long, is Double -> value
        is Int -> value.toLong()
        is Float -> value.toDouble()
        is Number -> value.toDouble()
        is Map<*, *> -> {
            val map = LinkedHashMap<String, Any?>()
            for ((k, v) in value) {
                map[k.toString()] = normalizeValue(v)
            }
            map
        }
        is List<*> -> value.map { normalizeValue(it) }
        is Array<*> -> value.map { normalizeValue(it) }
        else -> value.toString()
    }
}
