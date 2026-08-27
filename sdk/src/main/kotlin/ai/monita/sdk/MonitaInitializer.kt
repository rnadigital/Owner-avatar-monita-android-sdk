// Copyright RNA Digital PTY LTD
package ai.monita.sdk

import android.content.Context
import androidx.startup.Initializer

/**
 * androidx.startup initializer. Reads the manifest meta-data entry
 * ai.monita.sdk.TOKEN and initializes the SDK automatically when present.
 * Apps that prefer explicit initialization simply omit the meta-data entry,
 * or remove the InitializationProvider entry for this initializer.
 */
class MonitaInitializer : Initializer<Monita> {

    override fun create(context: Context): Monita {
        Monita.initializeFromManifest(context)
        return Monita
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
