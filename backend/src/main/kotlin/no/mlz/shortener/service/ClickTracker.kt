package no.mlz.shortener.service

import no.mlz.shortener.repository.LinkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * Records redirect clicks off the hot path (§6).
 *
 * The redirect handler calls [record], which never blocks: the link id is dropped onto a
 * bounded [Channel] and a single background coroutine batches inserts (flush at
 * [batchSize] events or every [flushIntervalMs] ms, whichever comes first).
 *
 * Tradeoff: a crash between enqueue and flush loses at most one un-flushed batch of click
 * counts. That is acceptable for analytics; it would NOT be acceptable for billing-grade data.
 * Under sustained overflow the oldest queued events are dropped rather than blocking redirects.
 */
class ClickTracker(
    private val repository: LinkRepository,
    private val scope: CoroutineScope,
    private val batchSize: Int = 100,
    private val flushIntervalMs: Long = 500,
    capacity: Int = 10_000,
) {
    private val log = LoggerFactory.getLogger(ClickTracker::class.java)

    private val channel = Channel<Long>(
        capacity = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var consumer: Job? = null

    fun start() {
        consumer = scope.launch { consume() }
    }

    /** Enqueue a click for [linkId]. Non-blocking and best-effort. */
    fun record(linkId: Long) {
        channel.trySend(linkId)
    }

    private suspend fun consume() {
        val buffer = ArrayList<Long>(batchSize)
        while (true) {
            val first = channel.receiveCatching().getOrNull() ?: break
            buffer.add(first)
            // Fill the batch until it's full or the flush window elapses. receiveCatching lets the
            // channel close mid-fill (on shutdown) without throwing — we just flush what we have.
            withTimeoutOrNull(flushIntervalMs) {
                while (buffer.size < batchSize) {
                    val next = channel.receiveCatching().getOrNull() ?: return@withTimeoutOrNull
                    buffer.add(next)
                }
            }
            flush(buffer)
            buffer.clear()
        }
        if (buffer.isNotEmpty()) flush(buffer)
    }

    private suspend fun flush(linkIds: List<Long>) {
        try {
            withContext(Dispatchers.IO) { repository.recordClicks(linkIds) }
        } catch (e: Exception) {
            // Losing analytics counts must never crash the consumer or the app.
            log.warn("Failed to flush {} click events", linkIds.size, e)
        }
    }

    /** Stops accepting clicks and lets the consumer drain what is already queued. */
    suspend fun close() {
        channel.close()
        consumer?.join()
    }
}
