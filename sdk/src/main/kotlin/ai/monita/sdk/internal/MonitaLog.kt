// Copyright RNA Digital PTY LTD
package ai.monita.sdk.internal

import android.util.Log

/**
 * SDK logging. Release default is errors only. Debug logging is explicit
 * (Monita.setDebugLogging(true), the config builder, or the system property
 * "monita.debug" set to "true") and never logs payload bodies unless enabled.
 */
internal object MonitaLog {

    private const val TAG = "Monita"

    @Volatile
    var debugEnabled: Boolean = false

    fun systemPropertyDebug(): Boolean = try {
        System.getProperty("monita.debug") == "true"
    } catch (t: Throwable) {
        false
    }

    fun debug(message: () -> String) {
        if (debugEnabled) {
            try {
                Log.d(TAG, message())
            } catch (t: Throwable) {
                // Never let logging break the host app (for example in plain JVM tests).
            }
        }
    }

    fun error(message: String, throwable: Throwable? = null) {
        try {
            if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
        } catch (t: Throwable) {
        }
    }
}
