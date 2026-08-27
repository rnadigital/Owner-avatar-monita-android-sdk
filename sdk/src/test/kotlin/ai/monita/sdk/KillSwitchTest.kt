// Copyright RNA Digital PTY LTD
package ai.monita.sdk

import ai.monita.sdk.internal.ConfigManager
import ai.monita.sdk.internal.EventQueue
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KillSwitchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = tmp.newFolder()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun manager() = ConfigManager(dir, OkHttpClient(), server.url("/custom-config/t.json").toString())

    @Test
    fun aSuccessfulFetchPersistsConfigAndEtag() {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"")
                .setBody("""{"monitoringVersion":"3","vendors":[]}""")
        )
        val m = manager()
        val result = m.fetch()
        assertTrue(result is ConfigManager.Result.Applied)
        assertEquals("3", m.current!!.monitoringVersion)
        // A fresh manager starts instantly from cache.
        val reloaded = manager()
        assertNotNull(reloaded.loadCache())
        assertEquals("3", reloaded.current!!.monitoringVersion)
    }

    @Test
    fun routineFetchesSendIfNoneMatchAndAcceptNotModified() {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"")
                .setBody("""{"monitoringVersion":"3","vendors":[]}""")
        )
        server.enqueue(MockResponse().setResponseCode(304))
        val m = manager()
        m.fetch()
        assertEquals(ConfigManager.Result.NotModified, m.fetch())
        server.takeRequest()
        val second = server.takeRequest()
        assertEquals("\"v1\"", second.getHeader("If-None-Match"))
        assertNull(second.requestUrl!!.queryParameter("v"))
    }

    @Test
    fun explicitRefreshAppendsTheVersionParameter() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"monitoringVersion":"3","vendors":[]}""")
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"monitoringVersion":"4","vendors":[]}""")
        )
        val m = manager()
        m.fetch()
        m.fetch(explicitRefresh = true)
        server.takeRequest()
        assertEquals("3", server.takeRequest().requestUrl!!.queryParameter("v"))
    }

    @Test
    fun http404DisablesMonitoringAndWipesTheCache() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"monitoringVersion":"3","vendors":[]}""")
        )
        server.enqueue(MockResponse().setResponseCode(404))
        val m = manager()
        m.fetch()
        val result = m.fetch()
        assertEquals(ConfigManager.Result.Killed(removed = true), result)
        assertTrue(m.killed)
        assertNull(m.current)
        assertNull(manager().loadCache())
    }

    @Test
    fun pausedStatusDisablesCaptureButKeepsTheCache() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"monitoringVersion":"3","vendors":[]}""")
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"monitoringVersion":"3","monitoringStatus":"paused","vendors":[]}""")
        )
        val m = manager()
        m.fetch()
        assertEquals(ConfigManager.Result.Killed(removed = false), m.fetch())
        assertTrue(m.killed)
        // The previous good cache file is still on disk.
        assertTrue(File(dir, "config.json").exists())
    }

    @Test
    fun removedStatusWipesEverything() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"monitoringVersion":"3","vendors":[]}""")
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"monitoringVersion":"3","monitoringStatus":"removed","vendors":[]}""")
        )
        val m = manager()
        m.fetch()
        assertEquals(ConfigManager.Result.Killed(removed = true), m.fetch())
        assertFalse(File(dir, "config.json").exists())
    }

    @Test
    fun aCachedPausedConfigKeepsMonitoringOff() {
        File(dir, "config.json")
            .writeText("""{"monitoringVersion":"3","monitoringStatus":"paused","vendors":[]}""")
        val m = manager()
        assertNull(m.loadCache())
        assertTrue(m.killed)
    }

    @Test
    fun killSwitchClearsQueuedEvents() {
        // The queue clear on kill lives in MonitaCore; this verifies the queue
        // primitive it relies on.
        val queue = EventQueue(dir)
        queue.append(mapOf("t" to "x"), mapOf("e" to "1"))
        queue.clear()
        assertEquals(0, queue.size)
        assertEquals(0, EventQueue(dir).size)
    }
}
