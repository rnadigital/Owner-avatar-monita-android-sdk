// Copyright RNA Digital PTY LTD
package ai.monita.sdk

import okhttp3.OkHttpClient

/**
 * Static entry point for the Monita build time instrumentation plugin
 * (ai.monita:monita-instrumentation). Woven advice on
 * okhttp3.OkHttpClient.Builder.build() delegates here. This method is
 * exception contained and a no-op when the SDK is not initialized.
 * Not intended to be called by application code.
 */
object MonitaInstrumentation {

    @JvmStatic
    fun appendInterceptor(builder: Any?) {
        try {
            if (Monita.coreOrNull() == null) return
            val clientBuilder = builder as? OkHttpClient.Builder ?: return
            if (clientBuilder.interceptors().none { it is MonitaInterceptor }) {
                clientBuilder.addInterceptor(MonitaInterceptor())
            }
        } catch (t: Throwable) {
            // Never break a host app build() call.
        }
    }
}
