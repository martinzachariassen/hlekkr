package no.mlz.shortener.security

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.min

// In-memory, per-IP token bucket: `capacity` = burst, `refillPerMinute` = sustained rate. State is
// single-instance and does NOT survive horizontal scaling — a scaled deployment moves this to
// Redis. `nanoTime` is injected so refill logic is deterministically testable.
class TokenBucketRateLimiter(
    private val capacity: Long,
    refillPerMinute: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    data class Decision(val allowed: Boolean, val retryAfterSeconds: Long)

    private val refillPerNano: Double = refillPerMinute.toDouble() / TimeUnit.MINUTES.toNanos(1)
    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun check(key: String): Decision =
        buckets.computeIfAbsent(key) { Bucket(capacity.toDouble(), nanoTime()) }.tryConsume()

    private inner class Bucket(private var tokens: Double, private var lastRefill: Long) {
        @Synchronized
        fun tryConsume(): Decision {
            refill()
            return if (tokens >= 1.0) {
                tokens -= 1.0
                Decision(allowed = true, retryAfterSeconds = 0)
            } else {
                val waitNanos = ((1.0 - tokens) / refillPerNano)
                val retryAfter = ceil(waitNanos / TimeUnit.SECONDS.toNanos(1)).toLong().coerceAtLeast(1)
                Decision(allowed = false, retryAfterSeconds = retryAfter)
            }
        }

        private fun refill() {
            val now = nanoTime()
            val elapsed = now - lastRefill
            if (elapsed > 0) {
                tokens = min(capacity.toDouble(), tokens + elapsed * refillPerNano)
                lastRefill = now
            }
        }
    }
}
