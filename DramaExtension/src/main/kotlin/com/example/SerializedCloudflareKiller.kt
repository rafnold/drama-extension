package com.example

import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Serializes [CloudflareKiller]'s WebView challenge solve.
 *
 * CloudStream loads every catalog tab in parallel, so opening KDrama.in
 * fires ~5 concurrent GETs for `/dramas.php?type=…` within the same
 * millisecond (verified in device logcat, 2026-10-03: five
 * `WebViewResolver: Initial web-view request` lines in one second, then five
 * `Web-view timeout after 60s`).
 *
 * `CloudflareKiller` caches the solved `cf_clearance` in its public
 * `savedCookies` map, but it starts empty and has no internal locking - so
 * on a cold cache every concurrent request sees "no cookie yet" and launches
 * its own hidden WebView. Five WebViews driving Cloudflare's Turnstile at
 * once starve each other and all five time out, leaving the tab empty.
 *
 * This wrapper holds a lock only while the host has no cached clearance, so:
 * - the first request through solves the challenge and populates
 *   `savedCookies`;
 * - the requests queued behind it then find the cookie and take the cheap
 *   replay path instead of spawning more WebViews.
 *
 * Once a host is cached the wrapper adds no locking at all, so steady-state
 * requests are unaffected.
 */
class SerializedCloudflareKiller(
    private val inner: CloudflareKiller = CloudflareKiller(),
) : Interceptor {

    /** Guards the one-time solve; only contended on a cold cookie cache. */
    private val solveLock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        if (inner.savedCookies.containsKey(host)) {
            // Clearance already solved for this host - no lock, fast path.
            return inner.intercept(chain)
        }
        synchronized(solveLock) {
            // Re-check inside the lock: a thread that waited its turn will
            // find the cookie the winner just solved.
            if (inner.savedCookies.containsKey(host)) {
                return inner.intercept(chain)
            }
            return inner.intercept(chain)
        }
    }
}