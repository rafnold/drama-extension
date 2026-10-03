package com.example

import org.json.JSONArray
import org.json.JSONObject

/**
 * Renders a URL through a [FlareSolverr](https://github.com/FlareSolverr/FlareSolverr)
 * instance and returns the cleared HTML plus the cookie/UA pair that goes with
 * it.
 *
 * ## Why this exists
 *
 * k-drama.in sits behind a Cloudflare managed challenge. Verified 2026-10-03:
 * plain HTTP clients (curl, NiceHttp/OkHttp) get `403 cf-mitigated: challenge`
 * on every path, and CloudStream's own hidden WebView solver never clears it
 * - it ran the full 60 s (v16/v17) and then the full 180 s (v18) and timed out
 * both times, on device, with Turnstile visibly loading. So the challenge does
 * not yield to an automated WebView in this app.
 *
 * A real headful Chrome does clear it, and FlareSolverr is exactly that: a
 * Selenium-driven Chrome that returns the rendered HTML, the
 * `cf_clearance` cookie and the UA that earned it. Verified live against
 * FlareSolverr 3.5.2: `{"status":"ok","message":"Challenge solved!"}`,
 * HTTP 200, 20 `detail.php` cards, `cf_clearance` present.
 *
 * ## Cookie replay vs full proxy
 *
 * This resolves the *page* and then lets the caller replay the cookie itself,
 * rather than proxying every subsequent request. That keeps m3u8 playlists and
 * subtitle files on the cheap direct path instead of pushing megabytes of
 * video segments through a browser.
 *
 * ## Scope
 *
 * Generic on purpose: any provider can opt in via [fetchCleared], so it is not
 * k-drama.in-specific. Site-specific quirks (extra headers, referer
 * requirements, hosts that must not be proxied) stay with the caller.
 */
class FlareSolverr(
    private val baseUrl: String,
) {
    companion object {
        /** FlareSolverr's own default challenge budget, in ms. */
        private const val MAX_TIMEOUT_MS = 120_000

        /** Never let a hung FlareSolverr stall a provider indefinitely. */
        private const val REQUEST_TIMEOUT_MS = 150_000L

        /**
         * Session id used for hosts served *through* FlareSolverr rather than
         * by cookie replay. See [fetchClearedInSession].
         */
        const val DEFAULT_SESSION = "drama-extension"
    }

    /**
     * A cleared page: the rendered HTML, the `Cookie` header value carrying
     * `cf_clearance`, and the UA that solved it.
     */
    data class Cleared(
        val html: String,
        val cookie: String?,
        val userAgent: String?,
        val url: String?,
        val status: Int,
    )

    /**
     * Creates (or refreshes) a persistent FlareSolverr session named [session].
     *
     * A session keeps one browser alive server-side with its cookies and
     * clearance, so follow-up `request.get` calls carrying the same `session`
     * skip the challenge entirely - measured at ~0.8 s versus ~11.6 s for a
     * stateless solve. Best-effort: a failure here is not fatal, the next
     * `fetchCleared(session = ...)` still works (FlareSolverr auto-creates).
     */
    fun createSession(session: String): Boolean {
        val payload = JSONObject().apply {
            put("cmd", "sessions.create")
            put("session", session)
            put("maxTimeout", MAX_TIMEOUT_MS)
        }
        return try {
            val body = Http.postJson(
                baseUrl.trimEnd('/') + "/v1",
                payload.toString(),
                20_000L,
            ).use { it.body?.string().orEmpty() }
            JSONObject(body).optString("status") == "ok"
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Fetches [url] through FlareSolverr.
     *
     * @param referer optional Referer to send to the site.
     * @param headers extra request headers for the site fetch.
     * @return null when FlareSolverr is unreachable or reports a failure, so
     *   callers can fall back rather than surface an error.
     */
    fun fetchCleared(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        session: String? = null,
    ): Cleared? {
        val endpoint = baseUrl.trimEnd('/') + "/v1"
        val payload = JSONObject().apply {
            put("cmd", "request.get")
            put("url", url)
            put("maxTimeout", MAX_TIMEOUT_MS)
            if (!session.isNullOrBlank()) put("session", session)
            if (referer != null) put("referer", referer)
            if (headers.isNotEmpty()) put("headers", JSONObject(headers))
        }

        val body = try {
            Http.postJson(endpoint, payload.toString(), REQUEST_TIMEOUT_MS)
                .use { it.body?.string().orEmpty() }
        } catch (_: Throwable) {
            return null
        }

        val root = try {
            JSONObject(body)
        } catch (_: Throwable) {
            return null
        }
        if (root.optString("status") != "ok") return null

        val solution = root.optJSONObject("solution") ?: return null

        // cookies: [{name, value, domain, path, ...}]
        val cookies = solution.optJSONArray("cookies")
        val cookieHeader = if (cookies != null && cookies.length() > 0) {
            buildString {
                for (i in 0 until cookies.length()) {
                    val c = cookies.optJSONObject(i) ?: continue
                    val name = c.optString("name").trim()
                    val value = c.optString("value")
                    if (name.isNotEmpty()) {
                        if (isNotEmpty()) append("; ")
                        append(name).append('=').append(value)
                    }
                }
            }.ifBlank { null }
        } else {
            null
        }

        return Cleared(
            html = solution.optString("response"),
            cookie = cookieHeader,
            userAgent = solution.optString("userAgent").ifBlank { null },
            url = solution.optString("url").ifBlank { null },
            status = solution.optInt("status", 0),
        )
    }
}