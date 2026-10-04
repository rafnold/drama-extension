package com.example

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * k-drama.in watch-page **server 5** (Zxcstream / zxcstream.icu).
 *
 * ## What this resolver does and does NOT do
 *
 * It performs the site's **discovery** call and reports the real quality ladder
 * it returns. It deliberately emits **no playable source**: the per-quality
 * `link` is an AES-CBC envelope whose key is only held server-side (see below),
 * so there is no URL we could honestly hand to the client. Emitting a guessed
 * URL would fail silently and be indistinguishable from "no source", which is
 * worse than reporting nothing.
 *
 * ## Verified chain (reproduced from a plain client, no browser, 2026-10-03)
 *
 * 1. `POST https://player.zxcprime.xyz/backend/willierevillame`
 *    body is a PLAIN json object keyed by fixed hex field names:
 *    ```json
 *    {"a7f39c821d604e5b9c71f36e1547b":"239389",
 *     "c285f91ab306d28147a35632e816b":"tv",
 *     "6b491e7253ad84d392e7561a9384c":"atlas",
 *     "d8427b59ce30684a2f957c3613e85b":1,
 *     "91c6e4a728503d1f785c92346b713d":1}
 *    ```
 *    -> `{"token":"<64 hex>","ts":1791109554230}`
 *
 * 2. `GET https://player.zxcprime.xyz/backend_/sources/<atlas|valstrax>?...`
 *    with the same hex keys plus `token` (`c492f7a1…`) and `ts` (`61d9a52…`).
 *    The hex **name mapping is fixed** - only token/ts rotate - so this is
 *    reproducible, not a moving target.
 *
 *    -> `{"success":true,"server":"atlas","meow":true,
 *         "links":[{"type":"hls","link":"<base64>","resolution":360}, … 480,720,1080],
 *         "dubs":[{"lanCode":"zh","lanName":"Original Audio","original":true},
 *                 {"lanCode":"ar","lanName":"Arabic sub"},
 *                 {"lanCode":"ru","lanName":"Russian sub"}]}`
 *
 *    `atlas` returns `type:"hls"`; `valstrax` returns `type:"dash"`. This is
 *    the 360p-1080p ladder the owner sees on the website.
 *
 * ## Why no source is emitted
 *
 * Each `link` base64-decodes to `b"Salted__" + 8-byte salt + 832 bytes
 * ciphertext` (832 % 16 == 0) - a standard OpenSSL AES-CBC envelope. Verified
 * facts that close off the client-side options:
 *
 *  - 32 JS chunks across both origins contain **no** `Salted__`, `EVP`,
 *    `BytesToKey`, `CryptoJS` or `passphrase`; every `subtle.decrypt` /
 *    `pbkdf2` / `deriveBits` hit is stock fflate (dash.js) or Widevine/forge.
 *  - The token is **not** the key (tried raw as AES-256 key with iv = salt*2 /
 *    zeros / salt+8zero, and via EVP_BytesToKey with md5 and sha256).
 *  - Salt **and** ciphertext change on every request for the same title;
 *    Shannon entropy 7.72-7.75 with no valid PKCS7 padding, so it is real
 *    CBC with a ~832-byte plaintext.
 *  - The `link` is not a URL: requesting it on every plausible origin/path
 *    returns 404 (the 200s are Next.js's SPA catch-all serving the app shell).
 *
 * So the key never reaches the browser and the manifest is resolved
 * server-side. To finish this we need one known-plaintext pair: a real
 * manifest response captured from a working browser session.
 */
class ZxcResolver {

    companion object {
        private const val ORIGIN = "https://player.zxcprime.xyz"
        private const val REF = "$ORIGIN/player/tv/0/1/1"

        /** Must match the UA of the page that solves any challenge on this origin. */
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private const val TIMEOUT = 20_000L

        /** Fixed obfuscated field names (stable across requests and builds). */
        private const val K_TMDB = "a7f39c821d604e5b9c71f36e1547b"
        private const val K_TYPE = "c285f91ab306d28147a35632e816b"
        private const val K_SERVER = "6b491e7253ad84d392e7561a9384c"
        private const val K_SEASON = "d8427b59ce30684a2f957c3613e85b"
        private const val K_EPISODE = "91c6e4a728503d1f785c92346b713d"
        private const val K_TS = "61d9a5274c8e3b29afd6384c291e6"
        private const val K_TOKEN = "c492f7a183d6502b1e7436c538a716d"
        private const val K_TITLE = "5e28c9147a306d1e829f3674b392a1"
        private const val K_YEAR = "b731e6c94f08269d725f8341c306e"
        private const val K_AIRDATE = "e164932c50216a39e5814b3027"
        private const val K_ENDDATE = "e16932c543416ad739e5814b3027"
        private const val K_IMDB = "f35a8c19d674b3265e871c4933a725f"

        /** The two server names the site's own picker exposes. */
        val SERVERS = listOf("atlas", "valstrax")

        /** Clean unencrypted TMDB proxy on the .icu origin (no Cloudflare). */
        private const val META = "https://zxcstream.icu/database/details"

        /**
         * (title, airDate) for a tmdb id, or null when unavailable.
         *
         * Verified live: `/database/details/tv/239389?language=en-US` answers
         * real TMDB JSON including `"name":"Fangs of Fortune"` and
         * `"first_air_date":"2024-10-25"` with no auth and no challenge.
         */
        private fun metadata(id: String, isMovie: Boolean): Pair<String, String>? = try {
            val kind = if (isMovie) "movie" else "tv"
            val o = JSONObject(
                Http.get("$META/$kind/$id?language=en-US", timeoutMs = TIMEOUT).text,
            )
            val title = o.optString("title").ifEmpty { o.optString("name") }
            val date = o.optString("first_air_date").ifEmpty { o.optString("release_date") }
            if (title.isEmpty() || date.isEmpty()) null else title to date
        } catch (_: Throwable) {
            null
        }

        private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

        /**
         * Asks the site what it has for this episode.
         *
         * Returns a human-readable line per server, e.g.
         * `Server 5 (atlas): hls 360p/480p/720p/1080p - source unresolved (worker-gated)`.
         * Empty when the site has nothing (or is unreachable) - callers treat
         * that as "no source", which is the honest outcome.
         */
        fun probe(
            id: String,
            season: Int,
            episode: Int,
            isMovie: Boolean,
        ): String {
            val type = if (isMovie) "movie" else "tv"
            val s = if (isMovie) 1 else season
            val e = if (isMovie) 1 else episode
            // The sources endpoint REQUIRES title + airdate (verified: omitting
            // either returns {"success":false,"error":"missing params"}), and
            // neither is available in loadLinks' scope. zxcstream.icu exposes a
            // clean unencrypted TMDB proxy for exactly this, so read them from
            // there rather than threading new state through the provider.
            val meta = metadata(id, isMovie) ?: return ""
            val lines = LinkedHashSet<String>()
            for (server in SERVERS) {
                val token = token(server, id, type, s, e) ?: continue
                val links = sources(server, id, type, s, e, token, meta.first, meta.second)
                    ?: continue
                if (links.length() == 0) continue
                // org.json.JSONArray is not Iterable - index it manually.
                val qualities = LinkedHashSet<String>()
                var fmt = "?"
                for (i in 0 until links.length()) {
                    val l = links.optJSONObject(i) ?: continue
                    val t = l.optString("type", "?")
                    if (fmt == "?") fmt = t
                    val r = l.optInt("resolution", 0)
                    qualities += if (r > 0) "${r}p" else t
                }
                val q = if (qualities.isEmpty()) fmt else qualities.joinToString("/")
                lines += "Server 5 ($server): $fmt $q - source unresolved (worker-gated)"
            }
            return lines.joinToString("; ")
        }

        /** POST /backend/willierevillame -> Pair(token, ts) or null. */
        private fun token(server: String, id: String, type: String, season: Int, episode: Int): Pair<String, String>? =
            try {
                val body = JSONObject()
                    .put(K_TMDB, id)
                    .put(K_TYPE, type)
                    .put(K_SERVER, server)
                    .put(K_SEASON, season)
                    .put(K_EPISODE, episode)
                    .toString()
                val text = ZxcResolver.postJson(
                    "$ORIGIN/backend/willierevillame",
                    body,
                )
                val o = JSONObject(text)
                val tok = o.optString("token", "")
                val ts = o.optString("ts", "")
                if (tok.isEmpty() || ts.isEmpty()) null else tok to ts
            } catch (_: Throwable) {
                null
            }

        /** GET /backend_/sources/<server>?<hex keys + token + ts> -> links array. */
        private fun sources(
            server: String,
            id: String,
            type: String,
            season: Int,
            episode: Int,
            token: Pair<String, String>,
            title: String? = null,
            airDate: String? = null,
        ): JSONArray? = try {
            val q = listOf(
                "$K_TMDB=${enc(id)}",
                "$K_TYPE=${enc(type)}",
                "$K_SERVER=${enc(server)}",
                "$K_TS=${enc(token.second)}",
                "$K_TOKEN=${enc(token.first)}",
                // Verified required by the endpoint: omitting EITHER title or
                // airdate returns {"success":false,"error":"missing params"}.
                // year / enddate / imdb are accepted but optional.
                "$K_TITLE=${enc(title.orEmpty())}",
                "$K_AIRDATE=${enc(airDate.orEmpty())}",
                "$K_SEASON=$season",
                "$K_EPISODE=$episode",
            ).joinToString("&")
            val text = ZxcResolver.getJson("$ORIGIN/backend_/sources/$server?$q")
            val o = JSONObject(text)
            if (!o.optBoolean("success", false)) null else o.optJSONArray("links")
        } catch (_: Throwable) {
            null
        }

        private fun postJson(url: String, body: String): String =
            // `Origin` is required: the endpoint answers
            // {"success":false,"error":"Internal Server Error"} without it
            // (verified 2026-10-04).
            Http.postJson(
                url,
                body,
                headers = mapOf("Origin" to ORIGIN, "User-Agent" to UA),
                timeoutMs = TIMEOUT,
            ).use { r -> r.body?.string() ?: "" }

        private fun getJson(url: String): String =
            Http.get(url, referer = REF, timeoutMs = TIMEOUT).text
    }
}