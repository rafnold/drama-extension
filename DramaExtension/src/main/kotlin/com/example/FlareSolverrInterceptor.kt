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
 * ## Why that also removes the cookie-replay problem
 *
 * Returning FlareSolverr's HTML directly means each provider call is
 * self-contained - no stored cookie, no replay, no dependence on the caller
 * cooperating. That sidesteps the IP-binding caveat for catalog and detail
 * pages entirely, because the bytes never have to validate against Cloudflare
 * from the device at all. Streams, playlists and subtitles are fetched by the
 * resolvers directly and are unaffected by Cloudflare (different hosts).
 *
 * ## Caveat: `cf_clearance` is IP-bound (matters only for replay)
 *
 * Cloudflare binds the cookie to the IP and UA that solved it. On this LAN
 * that holds anyway (verified: the phone and the FlareSolverr box both report
 * public IP 178.84.195.10), so a future replay-based fast path would work at
 * home. Off-LAN it would not - which is another reason the HTML-return path is
 * the primary one.
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

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // The one and only proceed() for this chain.
        val response = chain.proceed(request)
        if (!isChallenge(response)) return response

        response.close()

        val solver = this.solver
        if (solver == null) {
            // Not configured: hand back an empty document so the caller
            // renders an empty tab rather than throwing.
            return emptyChallenge(request)
        }

        val cleared = solver.fetchCleared(request.url.toString())
        if (cleared == null || cleared.html.isBlank()) {
            return emptyChallenge(request)
        }

        // FlareSolverr already returned the real page; synthesise a normal 200
        // so the caller's Jsoup parsing sees the cleared document.
        return html(request, cleared.html, "OK (via FlareSolverr)")
    }

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