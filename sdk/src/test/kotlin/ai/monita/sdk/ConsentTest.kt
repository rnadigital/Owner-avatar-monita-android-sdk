// Copyright RNA Digital PTY LTD
package ai.monita.sdk

import ai.monita.sdk.internal.ConsentManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConsentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val defaultPrefs =
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
    private var changes = 0
    private lateinit var manager: ConsentManager

    @Before
    fun setUp() {
        defaultPrefs.edit().clear().commit()
        changes = 0
        manager = ConsentManager(context) { changes++ }
    }

    @After
    fun tearDown() {
        manager.close()
        defaultPrefs.edit().clear().commit()
    }

    @Test
    fun autoReadPrefersTcfThenGppThenUsp() {
        assertNull(manager.currentConsent())
        defaultPrefs.edit().putString("IABUSPrivacy_String", "1YNN").commit()
        assertEquals("1YNN", manager.currentConsent())
        defaultPrefs.edit().putString("IABGPP_HDR_GppString", "DBABMA~gpp").commit()
        assertEquals("DBABMA~gpp", manager.currentConsent())
        defaultPrefs.edit().putString("IABTCF_TCString", "CPtcf.consent").commit()
        assertEquals("CPtcf.consent", manager.currentConsent())
    }

    @Test
    fun explicitOverrideWinsOverAutoDetection() {
        defaultPrefs.edit().putString("IABTCF_TCString", "auto-value").commit()
        manager.setConsent("host-value")
        assertEquals("host-value", manager.currentConsent())
        manager.setConsent(null)
        assertEquals("auto-value", manager.currentConsent())
    }

    @Test
    fun providerWinsOverEverything() {
        defaultPrefs.edit().putString("IABTCF_TCString", "auto-value").commit()
        manager.setConsent("host-value")
        manager.setConsentProvider { "provider-value" }
        assertEquals("provider-value", manager.currentConsent())
        manager.setConsentProvider(null)
        assertEquals("host-value", manager.currentConsent())
    }

    @Test
    fun aThrowingProviderNeverBreaksTheBatchBuild() {
        manager.setConsentProvider { error("cmp broke") }
        assertNull(manager.currentConsent())
    }

    @Test
    fun cmpPreferenceChangesNotifyTheSdk() {
        val before = changes
        defaultPrefs.edit().putString("IABTCF_TCString", "new-consent").commit()
        assertTrue(changes > before)
    }

    @Test
    fun unrelatedPreferenceChangesDoNotNotify() {
        val before = changes
        defaultPrefs.edit().putString("some_app_setting", "x").commit()
        assertEquals(before, changes)
    }

    @Test
    fun tcfPresenceIsExposedForTheEnvironmentEvent() {
        assertFalse(manager.hasTcfString())
        defaultPrefs.edit().putString("IABTCF_TCString", "abc").commit()
        assertTrue(manager.hasTcfString())
    }
}
