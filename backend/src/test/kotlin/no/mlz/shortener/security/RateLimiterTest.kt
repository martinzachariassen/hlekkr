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
        limiter.check("ip")
        assertEquals(1, limiter.check("ip").retryAfterSeconds)
    }

    @Test
    fun `evicts replenished buckets once the map fills`() {
        val limiter = TokenBucketRateLimiter(capacity = 1, refillPerMinute = 60, maxEntries = 3, nanoTime = { now })
        limiter.check("a")
        limiter.check("b")
        limiter.check("c")
        assertEquals(3, limiter.trackedKeys())

        now += TimeUnit.SECONDS.toNanos(5) // all three refill to full
        limiter.check("d")                 // hits the cap, sweeps the replenished ones first

        assertEquals(1, limiter.trackedKeys())
    }

    @Test
    fun `keeps still-limited buckets when sweeping`() {
        val limiter = TokenBucketRateLimiter(capacity = 1, refillPerMinute = 6, maxEntries = 2, nanoTime = { now })
        limiter.check("keep") // drains; needs 10s to refill
        limiter.check("also")

        now += TimeUnit.SECONDS.toNanos(1) // neither has refilled
        limiter.check("new")               // sweep finds nothing evictable; map grows past the soft cap

        assertFalse(limiter.check("keep").allowed)
    }
}
