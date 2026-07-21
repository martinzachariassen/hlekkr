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

// Keeps click writes off the redirect hot path: record() drops the id on a bounded channel and a
// single background coroutine batches inserts. Overflow drops oldest rather than blocking redirects,
// and a crash loses at most one un-flushed batch — acceptable for analytics counts, not billing.
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

    fun record(linkId: Long) {
        channel.trySend(linkId)
    }

    private suspend fun consume() {
        val buffer = ArrayList<Long>(batchSize)
        while (true) {
            val first = channel.receiveCatching().getOrNull() ?: break
            buffer.add(first)
            // receiveCatching lets the channel close mid-fill (on shutdown) without throwing.
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
            // A failed flush must never crash the consumer or the app.
            log.warn("Failed to flush {} click events", linkIds.size, e)
        }
    }

    suspend fun close() {
        channel.close()
        consumer?.join()
    }
}
