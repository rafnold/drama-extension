package com.example

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Generic Cloudflare bypass for any provider, backed by FlareSolverr.
 *
 * ## Why FlareSolverr rather than the host's own WebView solver
 *
 * CloudStream ships `CloudflareKiller`, which solves a challenge in a hidden
 * WebView. Against k-drama.in it never succeeded on a real device: Turnstile
 * visibly loaded and the solve was killed at CloudStream's 60 s default
 * (v16/v17) and again at the 180 s budget v18 gave `WebViewResolver`. The
 * challenge simply does not yield to an automated WebView in this app.
 *
 * A real headful Chrome does clear it. FlareSolverr is a Selenium-driven
 * Chrome returning the rendered HTML plus the `cf_clearance` cookie and the UA
 * that earned it. Verified live against 3.5.2 on 2026-10-03 from both the
 * build host and the phone: "Challenge solved!", 200, 20 cards.
 *
 * ## Contract: exactly one `chain.proceed()`
 *
 * An OkHttp interceptor must call `proceed` exactly once. The first version of
 * this file called it up to three times on one chain (cached replay, original,
 * post-solve replay), which OkHttp rejects with `IllegalStateException` - and
 * because every provider here wraps its fetch in `catch (_: Throwable)` and
 * returns an empty page, the symptom was a silently empty tab with no visible
 * error anywhere. That is what v19 shipped.
 *
 * So the request is issued **once**, before any Cloudflare decision. If the
 * result is a challenge, we resolve it out-of-band through FlareSolverr and
 * return that HTML as a synthesised 200. No second `proceed`.
 *
 * ## Why that also makes the warm path cheap
 *
 * Returning FlareSolverr's HTML satisfies the current request outright, and
 * the cookie + UA it hands back are cached per host so later requests are
 * *replayed* with them before `proceed` and answered directly by Cloudflare.
 * Only a genuinely uncached host pays the ~12 s solve. On this LAN the replay
 * validates because the phone and the FlareSolverr box share one public IP
 * (178.84.195.10, verified); off-LAN it would not, and the code degrades
 * gracefully - the replay is simply challenged again and we fall back to
 * re-solving rather than failing.
 *
 * Streams, playlists and subtitles are fetched by the resolvers directly and
 * are on other hosts, so they were never Cloudflare-gated.
 *
 * ## Usage
 *
 * ```kotlin
 * val doc = app.get(url, interceptor = FlareSolverrInterceptor()).document
 * ```
 *
 * Configure the endpoint via `SiteConfig.flareSolverrUrl()` (remote
 * config.json > hardcoded default > `$FLARESOLVERR_URL`); blank disables the
 * mechanism and challenges then render as empty.
 */
class FlareSolverrInterceptor(
    private val baseUrl: String? = SiteConfig.flareSolverrUrl(),
) : Interceptor {

    companion object {
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")
    }

    private val solver = baseUrl?.takeIf { it.isNotBlank() }?.let { FlareSolverr(it) }

    /**
     * Solved clearance per host: cookie header -> matching UA.
     *
     * Cloudflare binds `cf_clearance` to the solving IP *and* UA, and the
     * binding is long-lived (the cookie's own expiry is ~1 year), so one solve
     * serves every later request. Measured on the user's LAN: a cold FlareSolverr
     * solve is ~11.6 s, while replaying the cookie answers a catalog page in
     * 0.13 s and a detail page (25 episode links) in 0.79 s. That is the
     * difference between every tab click costing 12 s and costing nothing.
     *
     * Because the replay is a normal request built before `proceed`, the
     * one-proceed-per-chain contract still holds.
     */
    private val cleared = HashMap<String, Pair<String, String>>()
    private val lock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val host = original.url.host

        // Warm path: if we already hold this host's clearance, send it up front
        // so the request is answered directly and Cloudflare never challenges.
        val cached = synchronized(lock) { cleared[host] }
        val warm = if (cached != null) withClearance(original, cached.first, cached.second) else null

        // The one and only proceed() for this chain.
        val response = if (warm != null) chain.proceed(warm) else chain.proceed(original)
        if (!isChallenge(response)) return response

        response.close()

        // Stale cache (Cloudflare rotated the clearance): drop it so the next
        // request re-solves cleanly rather than carrying a dead cookie.
        synchronized(lock) { if (cached != null && cleared[host] === cached) cleared.remove(host) }

        val solver = this.solver
        if (solver == null) {
            // Not configured: hand back an empty document so the caller
            // renders an empty tab rather than throwing.
            return emptyChallenge(original)
        }

        val result = solver.fetchCleared(original.url.toString())
        if (result == null || result.html.isBlank()) {
            return emptyChallenge(original)
        }

        result.cookie?.takeIf { it.isNotBlank() }?.let { c ->
            result.userAgent?.takeIf { it.isNotBlank() }?.let { ua ->
                synchronized(lock) { cleared[host] = c to ua }
            }
        }

        // FlareSolverr already returned the real page; synthesise a normal 200
        // so the caller's Jsoup parsing sees the cleared document.
        return html(original, result.html, "OK (via FlareSolverr)")
    }

    /** Copy [request] with the solved Cookie and its matching User-Agent. */
    private fun withClearance(
        request: okhttp3.Request,
        cookie: String,
        ua: String,
    ): okhttp3.Request =
        request.newBuilder()
            .header("Cookie", cookie)
            .header("User-Agent", ua)
            .build()

    private fun isChallenge(response: Response): Boolean =
        response.header("Server") in CLOUDFLARE_SERVERS &&
            response.code in ERROR_CODES

    private fun html(request: okhttp3.Request, body: String, message: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message(message)
            .header("Content-Type", "text/html; charset=utf-8")
            .body(body.toResponseBody("text/html; charset=utf-8".toMediaTypeOrNull()))
            .build()

    /** A 200 carrying no cards, for the unconfigured / failed-solve cases. */
    private fun emptyChallenge(request: okhttp3.Request): Response =
        html(
            request,
            "<html><head><title>Just a moment...</title></head><body></body></html>",
            "OK (challenge)",
        )
}