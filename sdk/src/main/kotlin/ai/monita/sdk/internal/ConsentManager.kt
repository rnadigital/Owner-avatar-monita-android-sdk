// Copyright RNA Digital PTY LTD
package ai.monita.sdk.internal

import android.content.Context
import android.content.SharedPreferences

/**
 * Consent string resolution. By default the IAB strings are read from the
 * app's default SharedPreferences in priority order: IABTCF_TCString, then
 * IABGPP_HDR_GppString, then IABUSPrivacy_String. The value is re-read on
 * every batch build so CMP updates propagate; a change listener triggers a
 * flush so an envelope never mixes consent states. A host override set via
 * setConsent wins over auto detection; a consent provider closure wins over
 * both when registered.
 */
internal class ConsentManager(
    context: Context,
    private val onConsentChanged: () -> Unit,
) {

    private val defaultPrefs: SharedPreferences =
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

    @Volatile
    private var overrideConsent: String? = null

    @Volatile
    private var overrideSet: Boolean = false

    @Volatile
    private var provider: (() -> String?)? = null

    // Held strongly by the SDK singleton so it is never garbage collected.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_TCF || key == KEY_GPP || key == KEY_USP) {
            onConsentChanged()
        }
    }

    init {
        try {
            defaultPrefs.registerOnSharedPreferenceChangeListener(listener)
        } catch (t: Throwable) {
            MonitaLog.error("Failed to register consent listener", t)
        }
    }

    fun setConsent(consent: String?) {
        overrideConsent = consent
        overrideSet = consent != null
        onConsentChanged()
    }

    fun setConsentProvider(newProvider: (() -> String?)?) {
        provider = newProvider
        onConsentChanged()
    }

    /** Current consent string: provider, then explicit override, then IAB auto read. */
    fun currentConsent(): String? {
        provider?.let { p ->
            return try {
                p()
            } catch (t: Throwable) {
                null
            }
        }
        if (overrideSet) return overrideConsent
        return autoRead()
    }

    private fun autoRead(): String? = try {
        defaultPrefs.getString(KEY_TCF, null)
            ?: defaultPrefs.getString(KEY_GPP, null)
            ?: defaultPrefs.getString(KEY_USP, null)
    } catch (t: Throwable) {
        null
    }

    /** Unregisters the preference listener; used when tearing the SDK down in tests. */
    fun close() {
        try {
            defaultPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        } catch (t: Throwable) {
        }
    }

    fun hasTcfString(): Boolean = try {
        defaultPrefs.getString(KEY_TCF, null) != null
    } catch (t: Throwable) {
        false
    }

    private companion object {
        const val KEY_TCF = "IABTCF_TCString"
        const val KEY_GPP = "IABGPP_HDR_GppString"
        const val KEY_USP = "IABUSPrivacy_String"
    }
}
