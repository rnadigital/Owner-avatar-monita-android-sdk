// Copyright RNA Digital PTY LTD
package ai.monita.sdk

import ai.monita.sdk.internal.EventQueue
import ai.monita.sdk.internal.Uploader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UploaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var queue: EventQueue
    private var debugUnbatched = false

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        queue = EventQueue(tmp.newFolder())
        debugUnbatched = false
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun uploader() = Uploader(
        OkHttpClient(),
        server.url("/api/v1").toString(),
        queue,
    ) { debugUnbatched }

    private fun shared(key: String = "a"): Map<String, Any?> =
        linkedMapOf("t" to "dom_uploadtoken12345678901", "sv" to "46", "ctx" to key)

    private fun event(n: Int, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val e = linkedMapOf<String, Any?>("tm" to 1000.5, "e" to "ev_$n", "vn" to "V")
        e.putAll(extra)
        return e
    }

    private fun parseBody(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    @Test
    fun eventsAreDeletedOnlyAfterATwoHundred() {
        queue.append(shared(), event(1))
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(Uploader.DrainResult.RetryLater, runBlocking { uploader().drainOnce() })
        assertEquals(1, queue.size)

        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(Uploader.DrainResult.Drained, runBlocking { uploader().drainOnce() })
        assertEquals(0, queue.size)
    }

    @Test
    fun networkFailureKeepsEverythingQueued() {
        queue.append(shared(), event(1))
        val dead = Uploader(OkHttpClient(), "http://127.0.0.1:2/api/v1", queue) { false }
        assertEquals(Uploader.DrainResult.RetryLater, runBlocking { dead.drainOnce() })
        assertEquals(1, queue.size)
    }

    @Test
    fun singleEventShipsInTheLegacyFlatShape() {
        queue.append(shared(), event(7))
        server.enqueue(MockResponse().setResponseCode(204))
        runBlocking { uploader().drainOnce() }
        val body = parseBody(server.takeRequest().body.readUtf8())
        assertFalse(body.containsKey("events"))
        assertEquals("ev_7", body["e"]!!.jsonPrimitive.content)
        assertEquals("dom_uploadtoken12345678901", body["t"]!!.jsonPrimitive.content)
    }

    @Test
    fun multipleEventsShipAsOneEnvelopeWithSharedContextHoisted() {
        for (i in 1..3) {
            queue.append(shared(), event(i))
        }
        server.enqueue(MockResponse().setResponseCode(204))
        runBlocking { uploader().drainOnce() }
        val body = parseBody(server.takeRequest().body.readUtf8())
        assertEquals("dom_uploadtoken12345678901", body["t"]!!.jsonPrimitive.content)
        val events = body["events"]!!.jsonArray
        assertEquals(3, events.size)
        assertEquals("ev_1", events[0].jsonObject["e"]!!.jsonPrimitive.content)
        // Shared fields never leak into the per event entries.
        assertFalse(events[0].jsonObject.containsKey("t"))
        assertFalse(events[0].jsonObject.containsKey("sv"))
        // Per event fields never leak into the envelope.
        assertFalse(body.containsKey("e"))
        assertFalse(body.containsKey("tm"))
    }

    @Test
    fun chunksNeverExceedFiftyEvents() {
        for (i in 1..60) {
            queue.append(shared(), event(i))
        }
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        runBlocking { uploader().drainOnce() }
        assertEquals(2, server.requestCount)
        val first = parseBody(server.takeRequest().body.readUtf8())
        val second = parseBody(server.takeRequest().body.readUtf8())
        assertEquals(50, first["events"]!!.jsonArray.size)
        assertEquals(10, second["events"]!!.jsonArray.size)
    }

    @Test
    fun chunksSplitToStayUnderTheByteLimit() {
        for (i in 1..3) {
            queue.append(shared(), event(i, mapOf("blob" to "a".repeat(25_000))))
        }
        repeat(3) { server.enqueue(MockResponse().setResponseCode(204)) }
        runBlocking { uploader().drainOnce() }
        assertTrue(server.requestCount >= 2)
        var total = 0
        repeat(server.requestCount) {
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.length < 64_000)
            val parsed = parseBody(body)
            total += if (parsed.containsKey("events")) parsed["events"]!!.jsonArray.size else 1
        }
        assertEquals(3, total)
    }

    @Test
    fun aSharedContextChangeSplitsTheEnvelope() {
        queue.append(shared("before"), event(1))
        queue.append(shared("after"), event(2))
        repeat(2) { server.enqueue(MockResponse().setResponseCode(204)) }
        runBlocking { uploader().drainOnce() }
        assertEquals(2, server.requestCount)
        assertEquals("before", parseBody(server.takeRequest().body.readUtf8())["ctx"]!!.jsonPrimitive.content)
        assertEquals("after", parseBody(server.takeRequest().body.readUtf8())["ctx"]!!.jsonPrimitive.content)
    }

    @Test
    fun permanentClientErrorsDropTheChunkInsteadOfWedgingTheQueue() {
        queue.append(shared(), event(1))
        queue.append(shared(), event(2))
        server.enqueue(MockResponse().setResponseCode(401))
        var dropped = 0
        val u = Uploader(
            OkHttpClient(),
            server.url("/api/v1").toString(),
            queue,
            onEventsDropped = { dropped += it },
        ) { false }
        assertEquals(Uploader.DrainResult.Drained, runBlocking { u.drainOnce() })
        assertEquals(0, queue.size)
        // Dropped events are counted, never lost silently.
        assertEquals(2, dropped)
    }

    @Test
    fun debugModeShipsOneFlatPostPerEvent() {
        debugUnbatched = true
        for (i in 1..3) {
            queue.append(shared(), event(i))
        }
        repeat(3) { server.enqueue(MockResponse().setResponseCode(204)) }
        runBlocking { uploader().drainOnce() }
        assertEquals(3, server.requestCount)
        repeat(3) {
            val body = parseBody(server.takeRequest().body.readUtf8())
            assertFalse(body.containsKey("events"))
        }
    }

    @Test
    fun uploadBodyIsPostedAsJson() {
        queue.append(shared(), event(1))
        server.enqueue(MockResponse().setResponseCode(204))
        runBlocking { uploader().drainOnce() }
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        // Round trips through the queue encoding without loss.
        val body = parseBody(request.body.readUtf8())
        assertEquals(1000.5, body["tm"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }
}
