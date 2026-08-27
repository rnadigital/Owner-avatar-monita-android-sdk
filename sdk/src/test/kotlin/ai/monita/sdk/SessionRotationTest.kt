// Copyright RNA Digital PTY LTD
package ai.monita.sdk

import ai.monita.sdk.internal.SessionManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionRotationTest {

    private fun prefs(name: String) =
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test
    fun visitorIdIsStableForTheInstall() {
        val p = prefs("t1")
        val first = SessionManager(p).visitorId
        val second = SessionManager(p).visitorId
        assertEquals(first, second)
    }

    @Test
    fun sessionPersistsWithinThirtyMinutesOfActivity() {
        var now = 1_000_000L
        val manager = SessionManager(prefs("t2"), clock = { now })
        val sid = manager.sessionId()
        now += 29 * 60 * 1000L
        assertEquals(sid, manager.sessionId())
    }

    @Test
    fun sessionRotatesAfterThirtyMinutesOfInactivity() {
        var now = 1_000_000L
        val manager = SessionManager(prefs("t3"), clock = { now })
        val sid = manager.sessionId()
        now += 31 * 60 * 1000L
        assertNotEquals(sid, manager.sessionId())
    }

    @Test
    fun activityKeepsExtendingTheSession() {
        var now = 1_000_000L
        val manager = SessionManager(prefs("t4"), clock = { now })
        val sid = manager.sessionId()
        repeat(5) {
            now += 20 * 60 * 1000L
            assertEquals(sid, manager.sessionId())
        }
    }

    @Test
    fun activityTimestampWritesAreThrottledToOncePerMinute() {
        var now = 1_000_000L
        val p = prefs("t6")
        val manager = SessionManager(p, clock = { now })
        manager.sessionId() // rotation write persists the timestamp
        val persistedAtCreation = p.getLong("sid_at", -1L)
        now += 30_000L
        manager.sessionId() // within the throttle window: no write
        assertEquals(persistedAtCreation, p.getLong("sid_at", -1L))
        now += 31_000L
        manager.sessionId() // past one minute since the last write
        assertEquals(now, p.getLong("sid_at", -1L))
    }

    @Test
    fun hostOverrideWinsUntilCleared() {
        var now = 1_000_000L
        val manager = SessionManager(prefs("t5"), clock = { now })
        val auto = manager.sessionId()
        manager.setSessionId("host-session")
        assertEquals("host-session", manager.sessionId())
        manager.setSessionId(null)
        assertEquals(auto, manager.sessionId())
    }
}
