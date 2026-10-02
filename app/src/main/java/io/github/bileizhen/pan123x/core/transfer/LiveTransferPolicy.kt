package io.github.bileizhen.pan123x.core.transfer

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Changing a limit affects waiting tasks; running work is never interrupted or discarded. */
class DynamicTaskGate(private val limit: () -> Int) {
    private val lock = Any()
    private var active = 0
    private val revision = MutableStateFlow(0L)

    suspend fun <T> withPermit(block: suspend () -> T): T {
        while (true) {
            currentCoroutineContext().ensureActive()
            val observed = revision.value
            val acquired = synchronized(lock) {
                if (active < limit().coerceIn(1, 32)) { active++; true } else false
            }
            if (acquired) break
            // The timeout observes settings changes even when all active tasks are long lived.
            withTimeoutOrNull(250) { revision.first { it != observed } }
        }
        try { return block() } finally {
            synchronized(lock) { active--; revision.value++ }
        }
    }
}

/** Shared per direction across tasks and connections, adapted from LeiFetch's rate limiter. */
class LiveRateLimiter(
    private val rate: () -> Long,
    private val clockNs: () -> Long = System::nanoTime,
) {
    private val lock = Any()
    private var nextNs = 0L
    private var previousRate = 0L

    fun reserveMillis(bytes: Int): Long = synchronized(lock) {
        val currentRate = rate().coerceAtLeast(0)
        if (currentRate != previousRate || currentRate == 0L) {
            nextNs = 0; previousRate = currentRate
        }
        if (currentRate == 0L || bytes <= 0) return@synchronized 0L
        val now = clockNs()
        nextNs = maxOf(now, nextNs) + (bytes.toDouble() * 1_000_000_000 / currentRate).toLong()
        ((nextNs - now) / 1_000_000).coerceAtLeast(0)
    }

    suspend fun consume(bytes: Int) {
        val reservedRate = rate()
        var remaining = reserveMillis(bytes)
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            if (rate() != reservedRate || rate() <= 0) return
            val slice = minOf(remaining, 100)
            delay(slice); remaining -= slice
        }
    }

    /** OkHttp writes on its IO thread; short waits keep cancellation and live changes responsive. */
    fun consumeBlocking(bytes: Int, checkActive: () -> Unit) {
        val reservedRate = rate()
        var remaining = reserveMillis(bytes)
        while (remaining > 0) {
            checkActive()
            if (rate() != reservedRate || rate() <= 0) return
            val slice = minOf(remaining, 100)
            Thread.sleep(slice)
            remaining -= slice
        }
    }
}
