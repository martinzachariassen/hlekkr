package no.mlz.shortener.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class RateLimiterTest {

    private var now = 0L
    private fun limiter(capacity: Long, perMinute: Long) =
        TokenBucketRateLimiter(capacity, perMinute, nanoTime = { now })

    @Test
    fun `allows up to capacity then denies`() {
        val limiter = limiter(capacity = 2, perMinute = 60)

        assertTrue(limiter.check("1.2.3.4").allowed)
        assertTrue(limiter.check("1.2.3.4").allowed)
        val denied = limiter.check("1.2.3.4")
        assertFalse(denied.allowed)
        assertTrue(denied.retryAfterSeconds >= 1)
    }

    @Test
    fun `refills over time`() {
        val limiter = limiter(capacity = 1, perMinute = 60) // 1 token per second

        assertTrue(limiter.check("ip").allowed)
        assertFalse(limiter.check("ip").allowed)

        now += TimeUnit.SECONDS.toNanos(1)
        assertTrue(limiter.check("ip").allowed)
    }

    @Test
    fun `buckets are independent per key`() {
        val limiter = limiter(capacity = 1, perMinute = 60)

        assertTrue(limiter.check("a").allowed)
        assertTrue(limiter.check("b").allowed)
        assertFalse(limiter.check("a").allowed)
    }

    @Test
    fun `retryAfter reflects the wait for one token`() {
        val limiter = limiter(capacity = 1, perMinute = 60) // 1/sec
        limiter.check("ip") // drains the single token
        assertEquals(1, limiter.check("ip").retryAfterSeconds)
    }
}
