package no.mlz.shortener.security

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.min

// In-memory, per-IP token bucket: `capacity` = burst, `refillPerMinute` = sustained rate. State is
// single-instance — a scaled deployment must move this to Redis. `nanoTime` is injected for tests.
class TokenBucketRateLimiter(
    private val capacity: Long,
    refillPerMinute: Long,
    private val maxEntries: Int = 50_000,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    data class Decision(val allowed: Boolean, val retryAfterSeconds: Long)

    private val refillPerNano: Double = refillPerMinute.toDouble() / TimeUnit.MINUTES.toNanos(1)
    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun check(key: String): Decision {
        // A full bucket is indistinguishable from an unseen key, so dropping full ones is lossless
        // and caps memory against IP churn (e.g. spoofed/rotating IPs).
        if (buckets.size >= maxEntries) buckets.values.removeIf { it.isReplenished() }
        return buckets.computeIfAbsent(key) { Bucket(capacity.toDouble(), nanoTime()) }.tryConsume()
    }

    internal fun trackedKeys(): Int = buckets.size

    // ConcurrentHashMap only makes lookup/insertion of a bucket thread-safe; concurrent requests
    // for the same key still mutate one Bucket's state, hence the per-bucket lock below.
    private inner class Bucket(private var tokens: Double, private var lastRefill: Long) {
        @Synchronized
        fun isReplenished(): Boolean {
            refill()
            return tokens >= capacity.toDouble()
        }

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
