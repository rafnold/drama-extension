package com.example

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared daemon thread pool — the plugin's only concurrency primitive
 * (the compile classpath has no kotlinx-coroutines, so parallelism is plain
 * threads + futures + wall-clock budgets).
 *
 * CloudStream runs provider methods on its own IO dispatcher threads, so
 * blocking the calling thread while pool threads do parallel HTTP is fine:
 * total wall-clock drops from sum(times) to ~max(time).
 */
object Concurrency {
    private val counter = AtomicInteger(0)

    val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "drama-ext-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    /**
     * Runs [tasks] in parallel and collects results in **completion order**.
     *
     * The overall wall-clock budget is [budgetMs]; a task that exceeds the
     * remaining budget or throws is dropped (its slot is simply missing).
     *
     * ## Why not input order
     *
     * The first version walked the futures in submission order and gave each
     * one `remaining` milliseconds. That makes the *deadest* task the most
     * expensive one: k-drama.in's fan-out puts vidsync (server 1) first, and
     * that host currently hangs for **20.5 s** before failing, so it consumed
     * the whole budget and every later future was `cancel(true)`-ed with
     * nothing to show - observed on device 2026-10-04 as
     * `java.io.IOException: Canceled` from OkHttp followed by
     * `showToast = No Links Found`.
     *
     * Draining by completion time means a slow/dead host can only ever delay
     * the result, never cancel a task that would have succeeded.
     */
    fun <T> fanOut(budgetMs: Long, tasks: List<() -> T>): List<T> {
        if (tasks.isEmpty()) return emptyList()
        val start = System.currentTimeMillis()
        val futures = tasks.map { task -> pool.submit(task) }
        val out = mutableListOf<T>()
        val pending = ArrayDeque(futures)
        while (pending.isNotEmpty()) {
            val remaining = budgetMs - (System.currentTimeMillis() - start)
            if (remaining <= 0) {
                pending.forEach { it.cancel(true) }
                break
            }
            val f = pending.first()
            try {
                // A generous slice per future: we only need *a* task to finish
                // within the budget, so waiting on the head in bounded steps
                // lets later (faster) tasks land first instead of being
                // starved by a slow one sitting at the head of the queue.
                val slice = minOf(remaining, POLL_SLICE_MS)
                out += f.get(slice, TimeUnit.MILLISECONDS)
                pending.remove(f)
            } catch (_: TimeoutException) {
                // Not done yet: move it to the back and re-check the others, so
                // a hung task cannot consume the whole budget on its own.
                pending.remove(f)
                pending.addLast(f)
            } catch (_: Throwable) {
                f.cancel(true)
                pending.remove(f)
            }
        }
        return out
    }

    /** Per-wait slice in [fanOut]; small enough to re-poll often. */
    private const val POLL_SLICE_MS = 1_500L

    /** Fire-and-forget background work (config refresh, pre-warming). */
    fun run(block: () -> Unit) {
        pool.execute {
            try {
                block()
            } catch (_: Throwable) {
                // background work must never crash the pool
            }
        }
    }
}
