// Copyright RNA Digital PTY LTD
package ai.monita.sdk.internal

import android.content.SharedPreferences
import java.util.UUID

/**
 * Visitor and session identifiers. The visitor id is an SDK generated UUID
 * persisted for the install; no hardware or advertising identifiers are used.
 * The session id rotates after 30 minutes of inactivity, matching the JS
 * session semantics; a host supplied session id wins until cleared.
 */
internal class SessionManager(
    private val prefs: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
    private val rotationMs: Long = 30L * 60 * 1000,
) {

    @Volatile
    private var hostSessionId: String? = null

    private val lock = Any()

    val visitorId: String by lazy {
        synchronized(lock) {
            prefs.getString(KEY_VISITOR_ID, null) ?: UUID.randomUUID().toString().also {
                prefs.edit().putString(KEY_VISITOR_ID, it).apply()
            }
        }
    }

    fun setSessionId(sessionId: String?) {
        hostSessionId = sessionId
    }

    private var cachedSid: String? = null
    private var lastActivityMs: Long = 0L
    private var lastPersistMs: Long = Long.MIN_VALUE
    private var loaded = false

    /**
     * Returns the current session id, rotating it after 30 minutes of
     * inactivity. Rotation tracks an in-memory activity timestamp; the
     * persisted copy is throttled to at most one write per minute.
     */
    fun sessionId(): String {
        hostSessionId?.let { return it }
        synchronized(lock) {
            val now = clock()
            if (!loaded) {
                cachedSid = prefs.getString(KEY_SESSION_ID, null)
                lastActivityMs = prefs.getLong(KEY_SESSION_AT, 0L)
                loaded = true
            }
            var sid = cachedSid
            if (sid == null || now - lastActivityMs > rotationMs) {
                sid = UUID.randomUUID().toString()
                cachedSid = sid
                prefs.edit().putString(KEY_SESSION_ID, sid).putLong(KEY_SESSION_AT, now).apply()
                lastPersistMs = now
            } else if (now - lastPersistMs >= PERSIST_THROTTLE_MS) {
                prefs.edit().putLong(KEY_SESSION_AT, now).apply()
                lastPersistMs = now
            }
            lastActivityMs = now
            return sid
        }
    }

    private companion object {
        const val KEY_VISITOR_ID = "vid"
        const val KEY_SESSION_ID = "sid"
        const val KEY_SESSION_AT = "sid_at"
        const val PERSIST_THROTTLE_MS = 60_000L
    }
}
