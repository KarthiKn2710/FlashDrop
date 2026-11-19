package com.flashdrop.connectivity.lan

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ServiceResolverTest {

    /** Names starting with "stale" never resolve, like services that went away but are still cached. */
    private suspend fun fakeResolve(name: String): String? {
        if (name.startsWith("stale")) awaitCancellation()
        delay(100) // a live service answers quickly
        return "endpoint-of-$name"
    }

    @Test
    fun staleEntriesDoNotDelayALiveDeviceWhenResolvingInParallel() = runTest {
        val resolvedAt = HashMap<String, Long>()
        val resolver = ServiceResolver<String>(
            this, parallelism = 4, timeoutMs = 3_000, maxAttempts = 2, retryDelayMs = 1_000,
            resolve = ::fakeResolve, onResolved = { name, _ -> resolvedAt[name] = currentTime },
        )
        listOf("stale-1", "stale-2", "stale-3", "live").forEach(resolver::submit)
        advanceUntilIdle()
        assertEquals(100L, resolvedAt["live"])
        assertEquals(setOf("live"), resolvedAt.keys)
        resolver.close()
    }

    @Test
    fun withOneResolverAtATimeEachStaleEntryCostsOnlyTheShortTimeout() = runTest {
        val resolvedAt = HashMap<String, Long>()
        val resolver = ServiceResolver<String>(
            this, parallelism = 1, timeoutMs = 2_000, maxAttempts = 3, retryDelayMs = 500,
            resolve = ::fakeResolve, onResolved = { name, _ -> resolvedAt[name] = currentTime },
        )
        listOf("stale-1", "stale-2", "live").forEach(resolver::submit)
        advanceUntilIdle()
        // The old behaviour (5 s per stale entry, one at a time) would have taken 10.1 s.
        assertEquals(2 * 2_000L + 100, resolvedAt["live"])
        resolver.close()
    }

    @Test
    fun failedNamesAreRetriedAfterNewlyFoundOnes() = runTest {
        val order = CopyOnWriteArrayList<String>()
        var flaky = 0
        val resolver = ServiceResolver<String>(
            this, parallelism = 1, timeoutMs = 1_000, maxAttempts = 3, retryDelayMs = 200,
            resolve = { name ->
                order.add(name)
                if (name == "flaky" && flaky++ == 0) null else "ok"
            },
            onResolved = { _, _ -> },
        )
        resolver.submit("flaky")
        resolver.submit("fresh")
        advanceUntilIdle()
        assertEquals(listOf("flaky", "fresh", "flaky"), order)
        resolver.close()
    }

    @Test
    fun aLostServiceIsNotReportedAndDuplicatesAreIgnored() = runTest {
        val reported = CopyOnWriteArrayList<String>()
        val calls = CopyOnWriteArrayList<String>()
        val resolver = ServiceResolver<String>(
            this, parallelism = 1, timeoutMs = 1_000, maxAttempts = 2, retryDelayMs = 100,
            resolve = { name -> calls.add(name); delay(300); "ok" },
            onResolved = { name, _ -> reported.add(name) },
        )
        resolver.submit("a")
        resolver.submit("a") // found twice: resolved once
        resolver.submit("b")
        resolver.cancel("b") // lost before it was resolved
        advanceUntilIdle()
        assertEquals(listOf("a"), calls)
        assertEquals(listOf("a"), reported)
        resolver.close()
    }

    @Test
    fun givesUpAfterMaxAttempts() = runTest {
        val calls = CopyOnWriteArrayList<String>()
        val resolver = ServiceResolver<String>(
            this, parallelism = 2, timeoutMs = 500, maxAttempts = 3, retryDelayMs = 100,
            resolve = { name -> calls.add(name); null },
            onResolved = { _, _ -> },
        )
        resolver.submit("dead")
        advanceUntilIdle()
        assertEquals(3, calls.size)
        // Once given up, a fresh sighting of the same name may try again.
        resolver.submit("dead")
        advanceUntilIdle()
        assertTrue(calls.size == 6)
        resolver.close()
    }
}
