// Copyright RNA Digital PTY LTD
package ai.monita.sdk.internal

import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Drains the persistent queue to the collect endpoint. Records are grouped
 * into chunks that share one batch context; a chunk of one ships in the
 * legacy flat shape, larger chunks ship the envelope with an "events" array.
 * Chunk limits: 50 events (the ingest worker's cap) and 60KB serialized.
 * Records are deleted only after an HTTP 2xx. In debug mode every event
 * ships alone, flat, mirroring the JS debug cookie behavior.
 */
internal class Uploader(
    private val client: OkHttpClient,
    private val collectEndpoint: String,
    private val queue: EventQueue,
    private val onEventsDropped: (Int) -> Unit = {},
    private val isDebugUnbatched: () -> Boolean,
) {

    internal companion object {
        const val MAX_EVENTS_PER_POST = 50
        const val MAX_BYTES_PER_POST = 60_000
        val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** HTTP statuses that will never succeed on retry; the chunk is dropped. */
        fun isPermanentFailure(code: Int): Boolean =
            code in 400..499 && code != 408 && code != 429
    }

    internal sealed interface DrainResult {
        data object Drained : DrainResult
        data object RetryLater : DrainResult
    }

    /**
     * Attempts to upload everything queued. Returns [DrainResult.RetryLater]
     * on the first transient failure so the caller can back off. Network
     * round trips run on the IO dispatcher so a slow upload never stalls
     * the SDK's serial pipeline thread.
     */
    suspend fun drainOnce(): DrainResult {
        while (true) {
            val pending = queue.snapshot()
            if (pending.isEmpty()) return DrainResult.Drained
            val chunk = nextChunk(pending)
            val body = serializeChunk(chunk)
            val ids = chunk.map { it.id }
            val code = withContext(Dispatchers.IO) { post(body) }
            when {
                code in 200..299 -> queue.acknowledge(ids)
                code > 0 && isPermanentFailure(code) -> {
                    MonitaLog.error("Collect endpoint rejected a batch with HTTP $code, dropping ${ids.size} event(s)")
                    onEventsDropped(ids.size)
                    queue.acknowledge(ids)
                }
                else -> return DrainResult.RetryLater
            }
        }
    }

    private fun nextChunk(pending: List<EventQueue.Record>): List<EventQueue.Record> {
        val first = pending.first()
        if (isDebugUnbatched()) return listOf(first)
        val sharedKey = JsonValue.encode(first.shared)
        val chunk = mutableListOf(first)
        var size = sharedKey.length + 16 + JsonValue.encode(first.event).length + 1
        for (record in pending.drop(1)) {
            if (chunk.size >= MAX_EVENTS_PER_POST) break
            if (JsonValue.encode(record.shared) != sharedKey) break
            val eventSize = JsonValue.encode(record.event).length + 1
            if (size + eventSize > MAX_BYTES_PER_POST) break
            chunk.add(record)
            size += eventSize
        }
        return chunk
    }

    internal fun serializeChunk(chunk: List<EventQueue.Record>): String {
        return if (chunk.size == 1) {
            // Single event: legacy flat shape, shared fields merged with the event.
            val flat = LinkedHashMap<String, Any?>(chunk[0].shared)
            flat.putAll(chunk[0].event)
            JsonValue.encode(flat)
        } else {
            val envelope = LinkedHashMap<String, Any?>(chunk[0].shared)
            envelope["events"] = chunk.map { it.event }
            JsonValue.encode(envelope)
        }
    }

    /** Returns the HTTP status code, or -1 on a network failure. */
    private fun post(body: String): Int = try {
        val request = Request.Builder()
            .url(collectEndpoint)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { it.code }
    } catch (t: Throwable) {
        MonitaLog.debug { "Collect upload failed: ${t.message}" }
        -1
    }
}

/** Exponential backoff with jitter: base 5s doubling to a 10 minute cap. */
internal class Backoff(
    private val baseMs: Long = 5_000,
    private val capMs: Long = 10L * 60 * 1000,
) {
    private var attempt = 0

    fun reset() {
        attempt = 0
    }

    fun nextDelayMs(): Long {
        val exp = (baseMs * (1L shl minOf(attempt, 20))).coerceAtMost(capMs)
        attempt = minOf(attempt + 1, 20)
        val jitter = (exp * 0.2 * Random.nextDouble()).toLong()
        return exp + jitter
    }
}
