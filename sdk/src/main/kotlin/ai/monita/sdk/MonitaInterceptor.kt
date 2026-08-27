// Copyright RNA Digital PTY LTD
package ai.monita.sdk

import ai.monita.sdk.internal.DataParser
import ai.monita.sdk.internal.MonitaCore
import ai.monita.sdk.internal.MonitaLog
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

/**
 * OkHttp application interceptor that observes requests for vendor
 * monitoring. It never mutates, re-issues, retries, or consumes a request
 * or response: request bodies are snapshotted only when the body is not
 * one-shot and at most 64KB, and response bodies are never read. The
 * request proceeds identically whether or not the SDK is initialized.
 *
 * ```kotlin
 * OkHttpClient.Builder().addInterceptor(MonitaInterceptor()).build()
 * ```
 */
class MonitaInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val snapshot: Snapshot? = try {
            snapshot(request)
        } catch (t: Throwable) {
            null
        }
        val tm = System.currentTimeMillis() / 1000.0
        val capturedAtMs = System.currentTimeMillis()
        try {
            val response = chain.proceed(request)
            if (snapshot != null) {
                report(snapshot, tm, capturedAtMs, response.code, failed = false)
            }
            return response
        } catch (e: IOException) {
            if (snapshot != null) {
                report(snapshot, tm, capturedAtMs, statusCode = null, failed = true)
            }
            throw e
        }
    }

    private class Snapshot(
        val url: String,
        val method: String,
        val body: String?,
        val contentType: String?,
    )

    private fun snapshot(request: Request): Snapshot? {
        val core = Monita.coreOrNull() ?: return null
        // HttpUrl.toString() lowercases the scheme and host. Matching stays
        // case-sensitive (JS parity), and this normalization is acceptable:
        // hosts are case-insensitive by definition and the config builder
        // emits lowercase host patterns; paths and queries are preserved.
        val url = request.url.toString()
        if (core.isInternalEndpoint(url)) return null
        if (core.isOptedOut || core.isKilled) return null
        val (bodyText, contentType) = snapshotBody(request)
        return Snapshot(url, request.method.uppercase(), bodyText, contentType)
    }

    private fun report(snapshot: Snapshot, tm: Double, capturedAtMs: Long, statusCode: Int?, failed: Boolean) {
        try {
            Monita.coreOrNull()?.onNetworkCall(
                MonitaCore.CapturedCall(
                    url = snapshot.url,
                    method = snapshot.method,
                    body = snapshot.body,
                    contentType = snapshot.contentType,
                    tm = tm,
                    statusCode = statusCode,
                    failed = failed,
                    capturedAtMs = capturedAtMs,
                )
            )
        } catch (t: Throwable) {
            MonitaLog.error("Capture handoff failed", t)
        }
    }

    override fun equals(other: Any?): Boolean = other is MonitaInterceptor

    override fun hashCode(): Int = MonitaInterceptor::class.java.hashCode()

    internal companion object {

        /**
         * Snapshots the request body when, and only when, it is safe:
         * the body must be replayable (not one-shot, not duplex) and must
         * declare a bounded length of at most 64KB. A negative content
         * length (streaming or chunked bodies) is never buffered, so the
         * snapshot can never pull an unbounded stream into memory. A null
         * content type is assumed textual only for such bounded bodies.
         * Returns the body text (or null) and the content type (or null).
         */
        internal fun snapshotBody(request: Request): Pair<String?, String?> {
            var bodyText: String? = null
            var contentType: String? = null
            try {
                val body = request.body
                if (body != null) {
                    contentType = body.contentType()?.toString()
                        ?: request.header("Content-Type")
                    val length = body.contentLength()
                    if (!body.isOneShot() && !body.isDuplex() &&
                        length in 0..DataParser.MAX_BODY_BYTES.toLong() &&
                        DataParser.isTextualContentType(contentType)
                    ) {
                        val buffer = Buffer()
                        body.writeTo(buffer)
                        if (buffer.size <= DataParser.MAX_BODY_BYTES) {
                            bodyText = buffer.readString(Charsets.UTF_8)
                        }
                    }
                }
            } catch (t: Throwable) {
                bodyText = null
            }
            return bodyText to contentType
        }
    }
}
