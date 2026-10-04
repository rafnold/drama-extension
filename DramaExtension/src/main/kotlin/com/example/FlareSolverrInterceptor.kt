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

        /**
         * Markers of a Cloudflare interstitial **inside a 200 response**.
         *
         * FlareSolverr does not return a 403 when it fails to clear a challenge:
         * it returns HTTP 200 whose body is still the "Just a moment..." page
         * with `challenge-platform` in its CSP. Verified 2026-10-04 against
         * 3.5.2 on k-drama.in:
         *
         *  - 1st request in a fresh session: 11.3 s, **200, cf_challenge=True**,
         *    cookies `['cf_clearance']` - i.e. it hands back the challenge page.
         *  - 2nd request in the same session: **0.4 s, 200, cf_challenge=False**,
         *    real content.
         *
         * So a status-code-only check ([ERROR_CODES]) misses the *first* failure
         * entirely and we returned the challenge body to the caller as if it were
         * the page - no iframe in it, every resolver empty, and CloudStream
         * reporting "Bad http status" (observed on device 2026-10-04).
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
     * Fetches [url] through FlareSolverr **with the page's JavaScript allowed
     * to run**, returning the rendered DOM.
     *
     * Needed for pages that inject their payload client-side. Verified
     * 2026-10-04 on k-drama.in's server 6: `yoy4.php` ships no `<iframe>` in
     * its HTML and only reveals the `kisskh.megaplay.su` embed after ~6 s of
     * scripting, so a plain fetch leaves an embed-scraping resolver with nothing
     * to match. Uses the same session as the main interceptor so no second
     * Cloudflare solve is paid.
     *
     * Returns null when FlareSolverr is unconfigured, unreachable, or still
     * hands back a challenge page - callers treat that as "no source".
     */
    fun renderedHtml(url: String, referer: String? = null, waitMs: Int): String? {
        val solver = (CloudflareGate.interceptor() as FlareSolverrInterceptor)
            .renderedSolver() ?: return null
        val result = solver.fetchCleared(
            url,
            referer = referer,
            session = FlareSolverr.DEFAULT_SESSION,
            renderWaitMs = waitMs,
        ) ?: return null
        if (result.html.isBlank() || looksLikeChallenge(result.html)) return null
        return result.html
    }

    private val solver = baseUrl?.takeIf { it.isNotBlank() }?.let { FlareSolverr(it) }

    /** The configured solver, or null when FlareSolverr is not configured. */
    fun renderedSolver(): FlareSolverr? = solver?.also { ensureSession(it) }

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

    /**
     * FlareSolverr session kept alive for the "device is on a different egress
     * IP than FlareSolverr" case.
     *
     * A replayed `cf_clearance` only validates from the IP that solved it, so
     * off the shared LAN every request would re-solve from scratch (~11.6 s
     * each). Holding one persistent session means FlareSolverr keeps its own
     * browser and clearance alive server-side, and subsequent fetches are
     * answered in ~0.8 s ("Challenge not detected!") from *its* IP - which
     * sidesteps IP binding entirely, since the bytes never have to validate
     * from the phone.
     */
    @Volatile
    private var sessionEnsured = false

    private fun ensureSession(solver: FlareSolverr) {
        if (sessionEnsured) return
        synchronized(lock) {
            if (sessionEnsured) return
            solver.createSession(FlareSolverr.DEFAULT_SESSION)
            sessionEnsured = true
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val host = original.url.host

        // Warm path: if we already hold this host's clearance, send it up front
        // so the request is answered directly and Cloudflare never challenges.
        val cached = synchronized(lock) { cleared[host] }
        val warm = if (cached != null) withClearance(original, cached.first, cached.second) else null

        // A response can be a challenge in TWO ways, and we must catch both:
        //  - the usual 403/503 from Cloudflare, and
        //  - **HTTP 200 whose body is the interstitial**, which is what
        //    FlareSolverr returns when it solved in its own browser but the
        //    clearance it captured does not validate for the URL we asked
        //    (verified 2026-10-04; see CHALLENGE_BODY_MARKERS).
        val response = if (warm != null) chain.proceed(warm) else chain.proceed(original)
        if (!isChallenge(response)) {
            val ok = response.peekBody(4096).string()
            if (!looksLikeChallenge(ok)) return response
            // 200-with-interstitial: Cloudflare served a challenge page to a
            // plain client even though we thought we had clearance. Treat it
            // exactly like a 403 - drop the stale cache and go solve.
            response.close()
        } else {
            response.close()
        }

        // Stale cache: either Cloudflare rotated the clearance, or this
        // device's egress IP differs from the solving one (off the shared LAN)
        // so the replay can never validate. Drop it either way.
        val hadCache = synchronized(lock) {
            if (cached != null && cleared[host] === cached) {
                cleared.remove(host)
                true
            } else {
                cached != null
            }
        }

        val solver = this.solver
        if (solver == null) {
            // Not configured: hand back an empty document so the caller
            // renders an empty tab rather than throwing.
            return emptyChallenge(original)
        }

        // Device logcat is the only place this can be observed (the app
        // suppresses nothing, and stdout reaches logcat as System.out).
        // Naming the host and whether we had a cached clearance is what makes
        // a "No Links Found" report diagnosable instead of a guessing loop.
        ExtLog.log("cf", "challenge on $host cached=${cached != null} -> solving")

        // A rejected replay means replay is useless for this device, so go
        // through FlareSolverr's persistent session instead of paying a fresh
        // ~11.6 s solve on every single request.
        //
        // Retry once: measured 2026-10-04, FlareSolverr's FIRST request in a
        // fresh session returns the challenge page even though it reports
        // success and hands back a cf_clearance cookie; the SECOND request,
        // with that cookie now cached, returns the real page in 0.4 s. So a
        // single solve attempt is not enough - without this retry every cold
        // start failed.
        var result = if (hadCache) {
            fetchViaSession(solver, original) ?: solver.fetchCleared(original.url.toString())
        } else {
            solver.fetchCleared(original.url.toString())
        }
        if (result != null && looksLikeChallenge(result.html)) {
            // Solving happened but the answer is still an interstitial: re-ask
            // through the persistent session, which reuses FlareSolverr's own
            // live browser + clearance and therefore answers correctly.
            result = fetchViaSession(solver, original)
        }
        if (result == null || result.html.isBlank() || looksLikeChallenge(result.html)) {
            ExtLog.log(
                "cf",
                "solve FAILED $host null=${result == null} " +
                    "blank=${result?.html?.isBlank()} " +
                    "stillChallenge=${result?.let { looksLikeChallenge(it.html) }}",
            )
            return emptyChallenge(original)
        }
        ExtLog.log("cf", "solved $host ${result.html.length} bytes")

        result.cookie?.takeIf { it.isNotBlank() }?.let { c ->
            result.userAgent?.takeIf { it.isNotBlank() }?.let { ua ->
                synchronized(lock) { cleared[host] = c to ua }
            }
        }

        // FlareSolverr already returned the real page; synthesise a normal 200
        // so the caller's Jsoup parsing sees the cleared document.
        return html(original, result.html, "OK (via FlareSolverr)")
    }

    /**
     * Fetches [url] through FlareSolverr's own persistent session.
     *
     * Used when a replay was *rejected*, which in practice means this device's
     * egress IP differs from the one that solved the clearance (i.e. off the
     * shared LAN). Re-solving statelessly would cost ~11.6 s every time; inside
     * a long-lived session FlareSolverr reuses its browser and answers in
     * ~0.8 s, because the fetch originates from *its* IP and so is not subject
     * to the caller's IP binding at all.
     */
    private fun fetchViaSession(
        solver: FlareSolverr,
        request: okhttp3.Request,
    ): FlareSolverr.Cleared? {
        ensureSession(solver)
        return solver.fetchCleared(
            request.url.toString(),
            session = FlareSolverr.DEFAULT_SESSION,
        )
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