package com.example

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
     * Runs [tasks] in parallel and collects results in input order.
     * The overall wall-clock budget is [budgetMs]; a task that exceeds the
     * remaining budget or throws is dropped (its slot is simply missing).
     */
    fun <T> fanOut(budgetMs: Long, tasks: List<() -> T>): List<T> {
        if (tasks.isEmpty()) return emptyList()
        val start = System.currentTimeMillis()
        val futures = tasks.map { task -> pool.submit(task) }
        val out = mutableListOf<T>()
        for (f in futures) {
            val remaining = budgetMs - (System.currentTimeMillis() - start)
            if (remaining <= 0) {
                f.cancel(true)
                continue
            }
            try {
                out += f.get(remaining, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
                f.cancel(true)
            }
        }
        return out
    }

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
