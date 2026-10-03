package com.example

import com.lagradost.cloudstream3.network.WebViewResolver
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Runs Cloudflare's challenge in a WebView with a much longer budget than
 * CloudStream's built-in one.
 *
 * ## Why not plain `CloudflareKiller`
 *
 * `CloudflareKiller.bypassCloudflare()` delegates to `WebViewResolver` with
 * its default 60 s timeout and exposes no way to change it. On the device
 * (logcat 2026-10-03) the challenge consistently ran the full 60 s and was
 * then destroyed:
 *
 * ```
 * WebViewResolver: Initial web-view request: .../dramas.php?type=cdrama&page=1
 * WebViewResolver: Loading WebView URL: .../cdn-cgi/challenge-platform/...
 * WebViewResolver: Loading WebView URL: .../turnstile/v0/b/.../api.js
 * WebViewResolver: Web-view timeout after 60s
 * WebViewResolver: Destroyed webview
 * ```
 *
 * The Turnstile flow was progressing - it just ran out of time. The same
 * challenge clears in ~8 s in a real headed Chrome. `WebViewResolver`'s
 * constructor `(Regex, List<Regex>, String, Boolean, String, (String) -> Unit,
 * Long)` takes the timeout as its final argument, so we can give it more room
 * rather than being capped at 60 s.
 *
 * ## Matching CloudflareKiller's own detection
 *
 * CloudflareKiller only intervenes when the response is a Cloudflare
 * challenge: HTTP 403/503 with `Server: cloudflare`. This mirrors that
 * exactly, so no WebView is ever started for an ordinary response.
 *
 * ## One solver at a time
 *
 * The host fetches every catalog tab in parallel; uncoordinated, each request
 * launches its own WebView and they starve one another (seen on device: five
 * simultaneous `Initial web-view request` lines). Requests that lose the
 * election do **not** queue behind the winner - they fall through to a plain
 * request, which returns the challenge page quickly and renders as an empty
 * tab for that refresh. No tab ever hangs.
 */
class LongTimeoutCloudflareKiller(
    private val solveTimeoutMs: Long = 180_000L,
) : Interceptor {

    companion object {
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")
    }

    private val resolver = WebViewResolver(
        Regex(""),
        emptyList(),
        "",
        false,
        "",
        { },
        solveTimeoutMs,
    )

    private val lock = Any()
    private val inFlight = HashSet<String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        val isChallenge = response.header("Server") in CLOUDFLARE_SERVERS &&
            response.code in ERROR_CODES
        if (!isChallenge) return response

        val host = request.url.host
        response.close()

        val elected = synchronized(lock) { inFlight.add(host) }
        if (elected) {
            return try {
                solve(chain, request)
            } finally {
                synchronized(lock) { inFlight.remove(host) }
            }
        }

        // Another request is solving this host. Give it a brief grace period,
        // then go direct rather than waiting out a WebView that may run for
        // minutes.
        val deadline = System.currentTimeMillis() + 4_000L
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            val probe = try {
                chain.proceed(request)
            } catch (_: Throwable) {
                null
            }
            if (probe != null) {
                val stillChallenged = probe.header("Server") in CLOUDFLARE_SERVERS &&
                    probe.code in ERROR_CODES
                if (!stillChallenged) return probe
                probe.close()
            }
        }

        return chain.proceed(request)
    }

    /**
     * Drives the WebView solve off the main thread.
     *
     * `WebViewResolver.resolveUsingWebView(request, callback)` is a suspend
     * function returning `Pair<Request?, List<Request>>`: the first element is
     * the request to replay once the challenge has cleared (null when the
     * WebView gave up), the second is the set of requests it observed. The
     * callback is a predicate deciding whether a given observed request means
     * "solved"; we accept the first non-challenge one.
     *
     * The plugin classpath has no kotlinx-coroutines, so the call is driven
     * through [Http.blocking]'s plain-Continuation bridge, exactly like every
     * other non-suspend resolver call in this project.
     */
    private fun solve(chain: Interceptor.Chain, request: okhttp3.Request): Response {
        val webViewUa = WebViewResolver.webViewUserAgent

        val raw = Http.blocking(solveTimeoutMs + 15_000L) {
            resolver.resolveUsingWebView(request) { candidate ->
                val challenged = candidate.header("Server") in CLOUDFLARE_SERVERS &&
                    candidate.header("CF-Mitigated") == "challenge"
                !challenged
            }
        }

        // The call returns Pair<Request?, List<Request>>: the first element is
        // the request to replay after the challenge cleared (null if the
        // WebView gave up), the second is the set of requests it observed.
        val solved = (raw as Pair<*, *>?)?.first as okhttp3.Request?
            ?: return chain.proceed(request)

        val prepared = if (webViewUa.isNullOrBlank()) {
            solved
        } else {
            solved.newBuilder().header("User-Agent", webViewUa).build()
        }
        return chain.proceed(prepared)
    }
}