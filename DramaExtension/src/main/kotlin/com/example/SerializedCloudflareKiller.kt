package com.example

import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Serializes [CloudflareKiller]'s WebView challenge solve.
 *
 * CloudStream loads every catalog tab in parallel, so opening KDrama.in
 * fires ~5 concurrent GETs for `/dramas.php?type=…` within the same
 * millisecond (device logcat, 2026-10-03: five `WebViewResolver: Initial
 * web-view request` lines in one second, then five `Web-view timeout
 * after 60s`).
 *
 * `CloudflareKiller` caches the solved `cf_clearance` in its public
 * `savedCookies` map, but it starts empty and has no internal locking - so
 * on a cold cache every concurrent request sees "no cookie yet" and launches
 * its own hidden WebView. Five WebViews driving Turnstile at once starve each
 * other and all five time out, leaving the tab empty.
 *
 * ## Why this does NOT simply block on a lock
 *
 * v16 used `synchronized { inner.intercept(chain) }`, which serializes the
 * whole request. That is wrong: one solve can burn the full 60 s WebView
 * timeout, so the tabs queued behind the winner each waited a further 60 s
 * and the provider appeared to "load indefinitely" (reported on device).
 * A tab must never wait behind another tab's challenge.
 *
 * ## Strategy
 *
 * - First thread in for a host wins and solves - the only WebView.
 * - Any other thread while that solve is in flight does **not** block on the
 *   lock. It waits a short grace period for the winner to populate
 *   `savedCookies`; if the cookie appears it replays through the cache.
 *   Otherwise it issues the **raw** request, which returns the 403 challenge
 *   page quickly instead of hanging. That tab renders empty for this refresh
 *   and fills in on the next one, once the clearance is cached.
 *
 * Worst case: one tab solves, the others go empty for a refresh, a refresh
 * fixes them. No tab ever queues behind another tab's WebView.
 */
class SerializedCloudflareKiller(
    private val inner: CloudflareKiller = CloudflareKiller(),
) : Interceptor {

    /** Guards the winner election only; never held while a loser waits. */
    private val solveLock = Any()

    /** Hosts with a solve currently in flight. */
    private val inFlight = HashSet<String>()

    /**
     * How long a losing racer waits for the winner's cookie before falling
     * back to a raw request. Deliberately short - CloudStream's own WebView
     * timeout is 60 s, and a tab should fail fast rather than hang.
     */
    private val graceWaitMs = 4_000L

    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host

        // Clearance already solved for this host - fast path, no locking.
        if (inner.savedCookies.containsKey(host)) {
            return inner.intercept(chain)
        }

        // Elect the solver: exactly one thread per host gets to solve.
        synchronized(solveLock) {
            if (inner.savedCookies.containsKey(host)) {
                return inner.intercept(chain)
            }
            if (inFlight.add(host)) {
                return try {
                    inner.intercept(chain)
                } finally {
                    synchronized(solveLock) { inFlight.remove(host) }
                }
            }
        }

        // Another thread is solving this host. Give the winner a brief moment,
        // then go direct rather than blocking behind its WebView.
        val deadline = System.currentTimeMillis() + graceWaitMs
        while (System.currentTimeMillis() < deadline) {
            if (inner.savedCookies.containsKey(host)) {
                return inner.intercept(chain)
            }
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        // Still unsolved: issue the raw request (fast 403 challenge page)
        // instead of hanging on someone else's WebView.
        return chain.proceed(chain.request())
    }
}