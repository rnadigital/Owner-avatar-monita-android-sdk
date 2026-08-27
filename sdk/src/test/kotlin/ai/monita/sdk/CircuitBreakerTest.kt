// Copyright RNA Digital PTY LTD
package ai.monita.sdk

import ai.monita.sdk.internal.CircuitBreaker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CircuitBreakerTest {

    @Test
    fun allowsUpToTheLimitWithinAWindow() {
        var now = 0L
        val breaker = CircuitBreaker(maxRequests = 100, windowMs = 6_000, clock = { now })
        repeat(100) {
            assertTrue(breaker.allow())
        }
        assertFalse(breaker.allow())
        assertTrue(breaker.tripped)
    }

    @Test
    fun onceTrippedItStaysTrippedForTheProcessLifetime() {
        var now = 0L
        val breaker = CircuitBreaker(maxRequests = 3, windowMs = 6_000, clock = { now })
        repeat(4) { breaker.allow() }
        assertTrue(breaker.tripped)
        now += 100_000
        assertFalse(breaker.allow())
    }

    @Test
    fun theWindowResetsWhenTimePasses() {
        var now = 0L
        val breaker = CircuitBreaker(maxRequests = 5, windowMs = 6_000, clock = { now })
        repeat(5) { assertTrue(breaker.allow()) }
        now += 6_001
        repeat(5) { assertTrue(breaker.allow()) }
        assertFalse(breaker.tripped)
    }
}
