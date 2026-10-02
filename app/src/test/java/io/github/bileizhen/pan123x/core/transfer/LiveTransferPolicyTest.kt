@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package io.github.bileizhen.pan123x.core.transfer

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class LiveTransferPolicyTest {
    @Test fun waitingTasksRespectLiveLimitsAndReleaseOnFailureAndCancellation() = runTest {
        var limit = 1
        val gate = DynamicTaskGate { limit }
        val hold = CompletableDeferred<Unit>()
        val started = mutableListOf<Int>()
        val first = launch { gate.withPermit { started += 1; hold.await() } }
        val second = launch { gate.withPermit { started += 2; hold.await() } }
        val canceled = launch { gate.withPermit { started += 3 } }
        runCurrent(); assertEquals(listOf(1), started)
        canceled.cancelAndJoin()
        limit = 2; advanceTimeBy(251); runCurrent(); assertEquals(listOf(1, 2), started)
        limit = 1
        val third = launch { gate.withPermit { started += 4 } }
        advanceTimeBy(251); assertEquals(listOf(1, 2), started)
        first.cancelAndJoin(); runCurrent(); assertEquals(listOf(1, 2), started)
        hold.complete(Unit); second.join(); third.join(); assertEquals(listOf(1, 2, 4), started)
        try { gate.withPermit { throw IllegalStateException("failure") } } catch (error: IllegalStateException) { assertEquals("failure", error.message) }
        gate.withPermit { started += 5 }; assertEquals(5, started.last())
    }

    @Test fun speedBudgetIsSharedAcrossConnectionsAndTracksLiveChanges() {
        var rate = 1024L
        var now = 1_000_000_000L
        val limiter = LiveRateLimiter({ rate }, { now })
        assertEquals(1000L, limiter.reserveMillis(1024))
        assertEquals(2000L, limiter.reserveMillis(1024))
        now += 1_000_000_000; assertEquals(1500L, limiter.reserveMillis(512))
        rate = 2048; assertEquals(500L, limiter.reserveMillis(1024))
        rate = 0; assertEquals(0L, limiter.reserveMillis(1024))
    }

    @Test fun throttleWaitRespondsToCancellationAndUnlimitedSetting() = runTest {
        var rate = 1024L
        val limiter = LiveRateLimiter({ rate }, { testScheduler.currentTime * 1_000_000 })
        val canceled = launch { limiter.consume(4096); error("should be canceled") }
        runCurrent(); canceled.cancelAndJoin()
        var done = false
        val unlimited = launch { limiter.consume(4096); done = true }
        runCurrent(); assertFalse(done)
        rate = 0; advanceTimeBy(101); runCurrent(); assertTrue(done); unlimited.join()
    }
}
