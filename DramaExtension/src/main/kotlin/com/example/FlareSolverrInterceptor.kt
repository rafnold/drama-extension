package com.example

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Generic Cloudflare bypass for any provider, backed by FlareSolverr.
 *
 * ## Why FlareSolverr rather than the host's own WebView solver
 *
 * CloudStream ships `CloudflareKiller`, which solves a challenge in a hidden
 * WebView. Against k-drama.in it never succeeded on a real device: Turnstile
 * visibly loaded and then the solve was killed at CloudStream's 60 s default
 * (v16/v17) and again at the 180 s budget v18 passed to `WebViewResolver`
 * itself. So the challenge does not yield to an automated WebView in this
 * app, and no amount of extra timeout changes that.
 *
 * A real headful Chrome does clear it. FlareSolverr is a Selenium-driven
 * Chrome that returns the rendered HTML plus the `cf_clearance` cookie and the
 * UA that earned it. Verified live against 3.5.2 on 2026-10-03:
 * `{"status":"ok","message":"Challenge solved!"}`, HTTP 200, 20 cards.
 *
 * ## Why cookie replay rather than a full proxy
 *
 * The challenge page is resolved once; from then on the caller replays the
 * cookie on its own ordinary requests. That keeps every subsequent fetch -
 * catalog pages, m3u8 playlists, `.srt` subtitles - on the cheap direct path
 * instead of pushing them through a browser. FlareSolverr is only consulted
 * when a response is actually a challenge.
 *
 * ## Caveat: the cookie is IP-bound
 *
 * Cloudflare binds `cf_clearance` to the IP and UA that solved it, so the
 * replay only works from the same egress IP as the FlareSolverr host. On a
 * LAN that means the phone and the FlareSolverr box must share a router (they
 * do here: both behind 192.168.1.x, single public IP 178.84.195.10 - the
 * replay was verified end to end). Over mobile data it will not validate, and
 * the interceptor degrades to returning the challenge response so callers
 * render empty rather than crash.
 *
 * ## Usage
 *
 * Generic by design - any provider can pass `interpreter = flareSolverr()`
 * (or the shared instance) on its `app.get` calls:
 *
 * ```kotlin
 * val doc = app.get(url, interceptor = FlareSolverrInterceptor()).document
 * ```
 *
 * Configure the endpoint via `SiteConfig.flareSolverrUrl()` (remote
 * `config.json` or `$FLARESOLVERR_URL`); blank disables the mechanism.
 */
class FlareSolverrInterceptor(
    private val baseUrl: String? = SiteConfig.flareSolverrUrl(),
    /** Extra headers to send with the FlareSolverr fetch (e.g. a Referer). */
    private val extraHeaders: Map<String, String> = emptyMap(),
) : Interceptor {

    companion object {
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")
    }

    private val solver = baseUrl?.takeIf { it.isNotBlank() }?.let { FlareSolverr(it) }

    /** Cookie+UA per host, so a challenge is solved at most once per host. */
    private val cleared = HashMap<String, Pair<String?, String?>>()

    private val lock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        // Replay a cached clearance when we have one for this host.
        synchronized(lock) {
            cleared[url.host]?.let { (cookie, ua) ->
                val replay = buildReplay(request, cookie, ua) ?: return@let
                val resp = try {
                    chain.proceed(replay)
                } catch (_: Throwable) {
                    null
                }
                if (resp != null && !isChallenge(resp)) {
                    return resp
                }
                resp?.close()
            }
        }

        val response = chain.proceed(request)
        if (!isChallenge(response)) return response

        val solver = this.solver
        if (solver == null) {
            // No FlareSolverr configured: hand back the challenge response so
            // the caller sees an empty page rather than an exception.
            return response
        }

        response.close()

        val result = solver.fetchCleared(
            url = request.url.toString(),
            referer = extraHeaders["Referer"],
            headers = extraHeaders.filterKeys { it.equals("Referer", true) },
        )
        if (result == null || result.status !in listOf(200, 304) || result.html.isBlank()) {
            return chain.proceed(request)
        }

        synchronized(lock) { cleared[url.host] = result.cookie to result.userAgent }

        // Re-issue the original request through the freshly cleared path so
        // the caller gets a normal, fully-formed response (cookies, images
        // resolved) instead of raw FlareSolverr HTML.
        val replay = buildReplay(request, result.cookie, result.userAgent)
            ?: return chain.proceed(request)
        val resp = chain.proceed(replay)
        if (!isChallenge(resp)) return resp
        resp.close()

        // Replay still challenged (e.g. the egress IP differs from the
        // FlareSolverr host's): fall back to FlareSolverr's own HTML so the
        // caller still gets usable content instead of nothing.
        val fallback = okhttp3.Response.Builder()
            .request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200)
            .message("OK (via FlareSolverr)")
            .header("Content-Type", "text/html; charset=utf-8")
            .body(result.html.toResponseBody("text/html; charset=utf-8".toMediaTypeOrNull()))
            .build()
        return fallback
    }

    private fun isChallenge(response: Response): Boolean =
        response.header("Server") in CLOUDFLARE_SERVERS &&
            response.code in ERROR_CODES

    /** Copy [request] adding the solved Cookie and matching User-Agent. */
    private fun buildReplay(
        request: okhttp3.Request,
        cookie: String?,
        ua: String?,
    ): okhttp3.Request? {
        if (cookie.isNullOrBlank() && ua.isNullOrBlank()) return null
        val b = request.newBuilder()
        if (!cookie.isNullOrBlank()) b.header("Cookie", cookie)
        if (!ua.isNullOrBlank()) b.header("User-Agent", ua)
        return b.build()
    }
}