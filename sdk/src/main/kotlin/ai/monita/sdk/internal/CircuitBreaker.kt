// Copyright RNA Digital PTY LTD
package ai.monita.sdk.internal

/**
 * Runaway capture guard, mirroring the JS circuit breaker: more than
 * [maxRequests] captured events inside a [windowMs] window trips capture
 * off for the rest of the process lifetime.
 */
internal class CircuitBreaker(
    private val maxRequests: Int = 100,
    private val windowMs: Long = 6_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var requestCount = 0
    private var windowStart = clock()

    @Volatile
    var tripped: Boolean = false
        private set

    /** Returns true when capture may proceed; trips permanently on overflow. */
    @Synchronized
    fun allow(): Boolean {
        if (tripped) return false
        val now = clock()
        if (now - windowStart > windowMs) {
            requestCount = 0
            windowStart = now
        }
        requestCount++
        if (requestCount > maxRequests) {
            tripped = true
            MonitaLog.error("Circuit breaker tripped, too many captured events, monitoring stopped for this process")
            return false
        }
        return true
    }
}
