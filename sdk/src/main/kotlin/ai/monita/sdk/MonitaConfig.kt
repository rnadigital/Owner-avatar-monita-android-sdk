// Copyright RNA Digital PTY LTD
package ai.monita.sdk

/**
 * Initialization options for the Monita SDK.
 *
 * ```kotlin
 * Monita.initialize(context, MonitaConfig.Builder("dom_...").debugLogging(false).build())
 * ```
 */
class MonitaConfig private constructor(
    val token: String,
    /** Full collect URL override; defaults to https://collect.monita.ai/api/v1 */
    val collectEndpoint: String?,
    /** Full config JSON URL override; defaults to the URL derived from the token. */
    val configEndpoint: String?,
    val debugLogging: Boolean,
) {

    class Builder(private val token: String) {
        private var collectEndpoint: String? = null
        private var configEndpoint: String? = null
        private var debugLogging: Boolean = false

        fun collectEndpoint(url: String?): Builder = apply { collectEndpoint = url }

        fun configEndpoint(url: String?): Builder = apply { configEndpoint = url }

        fun debugLogging(enabled: Boolean): Builder = apply { debugLogging = enabled }

        fun build(): MonitaConfig = MonitaConfig(token, collectEndpoint, configEndpoint, debugLogging)
    }
}
