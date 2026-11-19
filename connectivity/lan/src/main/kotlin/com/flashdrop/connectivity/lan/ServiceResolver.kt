package com.flashdrop.connectivity.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Resolves discovered service names to endpoints without letting one dead entry hold up the rest.
 *
 * Discovery caches keep names of services that have since gone away, so a sender can be handed
 * several stale names next to the live one. Resolving a stale name never succeeds; handled one
 * after another with a long timeout, each one delays the live device by that timeout. Here every
 * attempt is bounded by [timeoutMs], up to [parallelism] names resolve at once, and a name that
 * failed goes to the back of the queue, so fresh names are always served before retries.
 */
internal class ServiceResolver<T : Any>(
    private val scope: CoroutineScope,
    parallelism: Int,
    private val timeoutMs: Long,
    private val maxAttempts: Int,
    private val retryDelayMs: Long,
    private val resolve: suspend (String) -> T?,
    private val onResolved: (String, T) -> Unit,
) {
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val attempts = HashMap<String, Int>()
    private val lost = HashSet<String>()

    init {
        repeat(parallelism.coerceAtLeast(1)) { scope.launch { work() } }
    }

    /** Queues [name] unless it is already queued or being resolved. */
    fun submit(name: String) {
        synchronized(this) {
            lost.remove(name)
            if (attempts.containsKey(name)) return
            attempts[name] = 0
        }
        queue.trySend(name)
    }

    /** The service went away: drop it if it has not been resolved yet. */
    fun cancel(name: String) {
        synchronized(this) { lost.add(name) }
    }

    fun close() {
        queue.close()
    }

    private suspend fun work() {
        for (name in queue) {
            if (isLost(name)) continue
            val result = withTimeoutOrNull(timeoutMs) { resolve(name) }
            if (result != null) {
                val deliver = synchronized(this) {
                    attempts.remove(name)
                    name !in lost
                }
                if (deliver) onResolved(name, result)
                continue
            }
            val retry = synchronized(this) {
                val tried = (attempts[name] ?: 0) + 1
                if (tried >= maxAttempts || name in lost) {
                    attempts.remove(name)
                    false
                } else {
                    attempts[name] = tried
                    true
                }
            }
            if (retry) {
                scope.launch {
                    delay(retryDelayMs)
                    queue.trySend(name)
                }
            }
        }
    }

    private fun isLost(name: String): Boolean = synchronized(this) {
        if (name in lost) {
            attempts.remove(name)
            true
        } else {
            false
        }
    }
}
