// Copyright RNA Digital PTY LTD
package ai.monita.sdk

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Safety contract of the interceptor's body snapshot: only bounded,
 * replayable, textual bodies are ever buffered; streaming, one-shot,
 * duplex, oversized, and binary bodies pass through untouched.
 */
class BodySnapshotTest {

    private val json: MediaType = "application/json".toMediaType()

    private fun request(body: RequestBody?): Request {
        val builder = Request.Builder().url("https://vendor.example.com/collect")
        return (if (body != null) builder.post(body) else builder.get()).build()
    }

    private class CountingBody(
        private val mediaType: MediaType?,
        private val length: Long,
        private val oneShot: Boolean = false,
        private val duplex: Boolean = false,
        private val payload: String = "ev=Purchase",
    ) : RequestBody() {
        var writeCount = 0

        override fun contentType(): MediaType? = mediaType

        override fun contentLength(): Long = length

        override fun isOneShot(): Boolean = oneShot

        override fun isDuplex(): Boolean = duplex

        override fun writeTo(sink: BufferedSink) {
            writeCount++
            sink.writeUtf8(payload)
        }
    }

    @Test
    fun boundedTextualBodiesAreSnapshotted() {
        val (text, contentType) = MonitaInterceptor.snapshotBody(
            request("""{"ev":"Purchase"}""".toRequestBody(json))
        )
        assertEquals("""{"ev":"Purchase"}""", text)
        assertEquals("application/json; charset=utf-8", contentType)
    }

    @Test
    fun unknownLengthStreamingBodiesAreNeverBuffered() {
        val body = CountingBody(json, length = -1)
        val (text, _) = MonitaInterceptor.snapshotBody(request(body))
        assertNull(text)
        assertEquals(0, body.writeCount)
    }

    @Test
    fun oneShotBodiesAreNeverConsumed() {
        val body = CountingBody(json, length = 10, oneShot = true)
        val (text, _) = MonitaInterceptor.snapshotBody(request(body))
        assertNull(text)
        assertEquals(0, body.writeCount)
    }

    @Test
    fun duplexBodiesAreNeverConsumed() {
        val body = CountingBody(json, length = 10, duplex = true)
        val (text, _) = MonitaInterceptor.snapshotBody(request(body))
        assertNull(text)
        assertEquals(0, body.writeCount)
    }

    @Test
    fun oversizedBodiesAreSkipped() {
        val big = "a".repeat(65 * 1024)
        val (text, _) = MonitaInterceptor.snapshotBody(request(big.toRequestBody(json)))
        assertNull(text)
    }

    @Test
    fun binaryContentTypesAreSkipped() {
        val body = CountingBody("application/x-protobuf".toMediaType(), length = 10)
        val (text, contentType) = MonitaInterceptor.snapshotBody(request(body))
        assertNull(text)
        assertEquals("application/x-protobuf", contentType)
        assertEquals(0, body.writeCount)
    }

    @Test
    fun nullContentTypeIsTextualOnlyForBoundedBodies() {
        val bounded = CountingBody(null, length = 11)
        val (boundedText, _) = MonitaInterceptor.snapshotBody(request(bounded))
        assertEquals("ev=Purchase", boundedText)

        val streaming = CountingBody(null, length = -1)
        val (streamingText, _) = MonitaInterceptor.snapshotBody(request(streaming))
        assertNull(streamingText)
        assertEquals(0, streaming.writeCount)
    }

    @Test
    fun requestsWithoutBodiesSnapshotNothing() {
        val (text, contentType) = MonitaInterceptor.snapshotBody(request(null))
        assertNull(text)
        assertNull(contentType)
    }
}
