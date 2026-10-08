package com.example

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import com.lagradost.cloudstream3.network.WebViewResolver

/**
 * Host-WebView Cloudflare bypass for any provider.
 *
 * ## Two Cloudflare paths in this project
 *
 * There are deliberately two bypasses:
 *
 *  - [FlareSolverrInterceptor] — the *default*, backed by a Selenium-driven
 *    Chrome over the wire. It works against k-drama.in where the host's own
 *    hidden WebView does not (Turnstile solved in a real browser, ~11.6 s cold,
 *    then replayed from a per-host cache). It is what every Cloudflare-gated
 *    fetch currently goes through via [CloudflareGate].
 *
 *  - **This file** — the *fallback*, backed by CloudStream's own
 *    [WebViewResolver], which drives a real hidden WebView on the device. It is
 *    wired in lazily (see the UG-12 spec) for the case where a provider's
 *    challenge yields to an on-device WebView but not to FlareSolverr, or where
 *    the device is offline and FlareSolverr's box is unreachable.
 *
 * The two are interchangeable from the caller's point of view: both are
 * `okhttp3.Interceptor`s handed to `Http.get(url, interceptor = …)`, and both
 * honour the same **one-`proceed`-per-chain** contract that [FlareSolverrInterceptor]
 * documents (a second `proceed` on the same chain is an
 * `IllegalStateException` that, because every resolver here wraps its fetch in
 * `catch (_: Throwable)` and returns an empty page, surfaces as a silently
 * empty tab — the exact failure that shipped as v19).
 *
 * ## Contract: exactly one `chain.proceed()`
 *
 * As in [FlareSolverrInterceptor], the request is issued **once**. If it comes
 * back as a challenge we do NOT `proceed` again: we solve out-of-band through
 * [WebViewResolver.resolveUsingWebView] (a *separate* OkHttp client that
 * [WebViewResolver] owns, so it is not this chain), and then `proceed` the
 * *solved* request — the one [WebViewResolver] hands back, which already
 * carries the `cf_clearance` cookie and the UA that earned it. The warm path
 * replays a cached clearance *before* `proceed`, so a later request to a
 * cleared host is answered directly by Cloudflare without ever re-solving.
 *
 * ## Degradation
 *
 * When [WebViewResolver] is unavailable (no WebView on the host, or the solve
 * times out at [WebViewResolver.Companion.DEFAULT_TIMEOUT]) we hand back an
 * empty 200 challenge page rather than throwing, so the caller renders an
 * empty tab exactly as it does with the unconfigured [FlareSolverrInterceptor].
 *
 * ## Usage
 *
 * ```kotlin
 * val doc = Http.get(url, interceptor = CfBypassInterceptor()).document
 * ```
 */
class CfBypassInterceptor : Interceptor {

    companion object {
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")

        /**
         * Markers of a Cloudflare interstitial **inside a 200 response**.
         *
         * Same list as [FlareSolverrInterceptor.Companion.CHALLENGE_BODY_MARKERS]
         * (kept private there, so it is duplicated here rather than widened to
         * `internal`): a solver can fail by returning HTTP 200 whose body is
         * still the "Just a moment..." page with `challenge-platform` in its
         * CSP, so a status-code-only check misses that failure entirely.
         */
        private val CHALLENGE_BODY_MARKERS = listOf(
            "challenge-platform",
            "cf-challenge-running",
            "Just a moment...",
            "Checking your browser before accessing",
        )

        /** True when [body] is a Cloudflare interstitial rather than real content. */
        fun looksLikeChallenge(body: String?): Boolean {
            if (body.isNullOrBlank()) return false
            val head = body.take(4096)
            return CHALLENGE_BODY_MARKERS.any { head.contains(it, ignoreCase = true) }
        }
    }

    /**
     * Solved clearance per host: cookie header -> matching UA.
     *
     * Cloudflare binds `cf_clearance` to the solving IP *and* UA, and the
     * binding is long-lived (the cookie's own expiry is ~1 year), so one on-device
     * solve serves every later request from the same device. This is the same
     * store shape as `CloudflareKiller.savedCookies` (host -> map of cookies);
     * we keep it as a flat `cookie -> ua` pair because the replay only ever needs
     * to set two headers.
     */
    private val cleared = HashMap<String, Pair<String, String>>()
    private val lock = Any()

    /**
     * One shared [WebViewResolver] for the whole extension.
     *
     * Constructing a new one per request would re-create the hidden WebView on
     * every solve. `interceptUrl` matches any page on any host — the resolver
     * only ever drives a WebView to *solve* the challenge, and the actual
     * request we care about is the one returned by `resolveUsingWebView`, so
     * the success regex just needs to stop the WebView once the challenge
     * clears. `useOkhttp` is true (the resolver owns its client); `script` is
     * null (Turnstile is Cloudflare's own script, not ours); `scriptCallback`
     * is null.
     */
    private val resolver by lazy {
        WebViewResolver(
            interceptUrl = Regex("https?://[^/]+/.*"),
            additionalUrls = emptyList(),
            userAgent = null, // null -> use the default webview user agent
            useOkhttp = true,
            script = null,
            scriptCallback = null,
            timeout = WebViewResolver.Companion.DEFAULT_TIMEOUT,
        )
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val host = original.url.host

        // Warm path: if we already hold this host's clearance, send it up front
        // so the request is answered directly and Cloudflare never challenges.
        val cached = synchronized(lock) { cleared[host] }
        val warm = if (cached != null) withClearance(original, cached.first, cached.second) else null

        val response = if (warm != null) chain.proceed(warm) else chain.proceed(original)
        if (!isChallenge(response)) {
            val ok = response.peekBody(4096).string()
            if (!looksLikeChallenge(ok)) return response
            // 200-with-interstitial: drop the stale cache and go solve.
            response.close()
        } else {
            response.close()
        }

        ExtLog.log("cf", "challenge on $host (webview) cached=${cached != null} -> solving")

        // Solve out-of-band through the hidden WebView. `shouldStop` returns true
        // for any request to the target host, which stops the WebView as soon as
        // the challenge redirects to the real page on that host. The returned
        // Pair.first is the cleared request (cookie + UA attached) or null.
        val solved: okhttp3.Request? = try {
            Http.blocking(WebViewResolver.Companion.DEFAULT_TIMEOUT) {
                resolver.resolveUsingWebView(
                    original.url.toString(),
                    original.header("Referer"),
                    emptyMap(),
                    { req -> req.url.host == host },
                ).first
            }
        } catch (e: Throwable) {
            ExtLog.log("cf", "webview solve threw on $host: ${e.message}")
            null
        }

        val solvedRequest = solved
        if (solvedRequest == null) {
            ExtLog.log("cf", "webview solve FAILED $host -> empty")
            return emptyChallenge(original)
        }

        // Cache the clearance for the warm path, then proceed the solved request
        // exactly once.
        val cookie = solvedRequest.header("Cookie")
        val ua = solvedRequest.header("User-Agent")
        if (!cookie.isNullOrBlank() && !ua.isNullOrBlank()) {
            synchronized(lock) { cleared[host] = cookie to ua }
        }

        val clearedResponse = chain.proceed(solvedRequest)
        val okBody = clearedResponse.peekBody(4096).string()
        if (!looksLikeChallenge(okBody)) {
            ExtLog.log("cf", "solved $host via webview")
            return clearedResponse
        }
        // The cleared request was still challenged (e.g. the WebView's egress IP
        // differs from the one that will serve the replay, off-LAN). Degrade.
        clearedResponse.close()
        ExtLog.log("cf", "webview solve on $host still challenged -> empty")
        return emptyChallenge(original)
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

    /** A 200 carrying no cards, for the no-WebView / failed-solve cases. */
    private fun emptyChallenge(request: okhttp3.Request): Response =
        html(
            request,
            "<html><head><title>Just a moment...</title></head><body></body></html>",
            "OK (challenge)",
        )
}
