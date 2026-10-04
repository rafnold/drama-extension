package com.example

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * English subtitle for one episode of server 5 (Zxcstream / zxcprime), if any.
 *
 * The video manifest for this server stays gated (see [ZxcResolver]), but the
 * subtitle endpoint is not: it answers in plain JSON with signed `.srt` URLs.
 * So while server 5 contributes no video, it can still contribute the owner's
 * English subtitle track.
 *
 * **English only.** The endpoint offers 11 languages but the owner watches with
 * English subs, so only the `English` caption is emitted - the client's subtitle
 * menu gets one entry instead of ten. Titles with no English caption simply
 * return an empty map (a legitimate outcome), so callers can merge
 * unconditionally without a null check.
 */
class ZxcSubtitleResolver {

    /**
     * ## Verified chain (plain client, no browser, 2026-10-04)
     *
     * 1. `POST https://player.zxcprime.xyz/backend/willierevillame` with the same
     *    plain-json body shape [ZxcResolver] uses, except the server field is the
     *    literal `"subtitle_"` rather than `atlas`/`valstrax`:
     *    ```json
     *    {"a7f39c821d604e5b9c71f36e1547b":"239389",
     *     "c285f91ab306d28147a35632e816b":"tv",
     *     "6b491e7253ad84d392e7561a9384c":"subtitle_",
     *     "d8427b59ce30684a2f957c3613e85b":1,
     *     "91c6e4a728503d1f785c92346b713d":1}
     *    ```
     *    -> `{"token":"<64 hex>","ts":<epoch ms>}`.
     *
     *    **The `Origin` header is load-bearing.** Omitting it makes the endpoint
     *    answer `{"success":false,"error":"Internal Server Error"}` - a misleading
     *    body for what is really a rejected preflight. [Http.postJson] sends it.
     *
     * 2. `GET https://embed.vidstuck.xyz/backend/subtitle?<same hex keys>` with
     *    the token/ts from step 1 plus title/year/airdate (title and airdate are
     *    required; year is accepted):
     *    -> `{"captions":[{"id":"6480859368186693800",
     *                     "file":"https://cacdn.hakunaymatata.com/subtitle/<hash>.srt?Policy=...&Signature=...",
     *                     "display":"<language name>"}, ...]}`
     *
     *    Note the response is served from a **different host** than the token
     *    (`embed.vidstuck.xyz` vs `player.zxcprime.xyz`) with a bare
     *    `Referer: https://player.zxcprime.xyz/` - verified required; without it
     *    the endpoint does not answer.
     *
     *    Verified live for id=239389 ("Fangs of Fortune") s1e1: **11 captions**,
     *    English at index 2, and the signed `.srt` downloads clean (29,900 B,
     *    BOM + valid SRT cue timing). The signed URLs are CloudFront-style and
     *    time-limited, so they must be fetched per-episode and never cached.
     *
     * 3. The caption objects carry only `id`, `file` and `display` - there is no
     *    `lang`/`name` field, and `display` is a **human-readable name in that
     *    language**, not a code. Verified values include `English`,
     *    `Français`, `Português`, `Русский`, `中文`, `العربية` (with
     *    diacritics), `বাংলা`, and - notably - **`Indonesia`, the country name,
     *    not the language name**. So these strings cannot be trusted to be
     *    language names; [EN_DISPLAY] matches the one value we want exactly
     *    rather than pattern-matching a table that this endpoint can contradict.
     *
     * ## Why the video manifest is not here
     *
     * The per-quality `link` from `/backend_/sources/atlas` is an AES-CBC
     * envelope, and the `url`/`header` pair that `/backend/database/andromeda`
     * accepts (measured 142 / 900 base64url chars, decoding to 106 / 675 raw
     * bytes) is **not** that `link` (1132 chars / 848 bytes) nor the `token`
     * (64 chars / 48 bytes) - feeding those to andromeda answers HTTP 400. Only
     * values observed in a live browser session are accepted. See [ZxcResolver]
     * for the full negative result.
          */

         companion object {
        private const val TIMEOUT = 20_000L

        /** Token host - also the Referer the subtitle host expects. */
        private const val ORIGIN = "https://player.zxcprime.xyz"
        private const val REFERER = "$ORIGIN/"
        private const val SUB_HOST = "https://embed.vidstuck.xyz"

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

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

        /** The server-name value that selects the subtitle backend. */
        private const val SUB_SERVER = "subtitle_"

        /** Clean unencrypted TMDB proxy for title/airdate (see ZxcResolver). */
        private const val META = "https://zxcstream.icu/database/details"

        private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

        /**
         * The only `display` value this resolver accepts.
         *
         * The owner watches with English subtitles only, so rather than build a
         * native-name -> ISO table for all 11 languages the endpoint returns
         * (Arabic, Bengali, Filipino, French, Malay, Portuguese, Russian, Urdu,
         * Chinese, ...), match English alone and skip everything else. Keeps the
         * client's subtitle menu to one entry instead of ten the user will never
         * pick.
         *
         * Verified live 2026-10-04 (id=239389 s1e1): the English caption is
         * present at index 2 and its signed .srt downloads clean (29,900 B with
         * valid SRT cue timing). Note the endpoint uses the **country** name for
         * Indonesian ("Indonesia") rather than the language name - a reminder
         * that these `display` strings are not reliably language names.
         */
        private const val EN_DISPLAY = "english"

        /** Normalised `display` -> "en", or null when it is not English. */
        private fun englishOrNull(display: String): String? =
            if (display.trim().lowercase() == EN_DISPLAY) "en" else null

        /** (title, airDate, year) from the clean TMDB proxy, or null. */
        private fun metadata(id: String, isMovie: Boolean): Triple<String, String, String>? = try {
            val kind = if (isMovie) "movie" else "tv"
            val o = JSONObject(
                Http.get("$META/$kind/$id?language=en-US", timeoutMs = TIMEOUT).text,
            )
            val title = o.optString("title").ifEmpty { o.optString("name") }
            val date = o.optString("first_air_date").ifEmpty { o.optString("release_date") }
            val year = date.take(4)
            if (title.isEmpty() || date.isEmpty()) null else Triple(title, date, year)
        } catch (_: Throwable) {
            null
        }

        /**
         * English-first subtitle map for one episode of server 5.
         *
         * Returns an empty map when the server has no captions for the title
         * (a legitimate outcome - several titles carry none), so callers can
         * merge it unconditionally without a null check.
         */
        fun subtitlesFor(
            id: String,
            season: Int,
            episode: Int,
            isMovie: Boolean,
        ): Map<String, ResolvedSubtitle> {
            val meta = metadata(id, isMovie) ?: return emptyMap()
            val type = if (isMovie) "movie" else "tv"
            val s = if (isMovie) 1 else season
            val e = if (isMovie) 1 else episode

            val token = token(id, type, s, e) ?: return emptyMap()
            val query = listOf(
                "$K_TMDB=${enc(id)}",
                "$K_TYPE=${enc(type)}",
                "$K_SERVER=$SUB_SERVER",
                "$K_TS=${enc(token.second)}",
                "$K_TOKEN=${enc(token.first)}",
                "$K_TITLE=${enc(meta.first)}",
                "$K_YEAR=${enc(meta.third)}",
                "$K_AIRDATE=${enc(meta.second)}",
                "$K_SEASON=$s",
                "$K_EPISODE=$e",
            ).joinToString("&")

            val body = try {
                Http.get(
                    "$SUB_HOST/backend/subtitle?$query",
                    referer = REFERER,
                    headers = mapOf("User-Agent" to UA, "Accept" to "application/json"),
                    timeoutMs = TIMEOUT,
                ).text
            } catch (_: Throwable) {
                return emptyMap()
            }

            val arr = try {
                JSONObject(body).optJSONArray("captions") ?: return emptyMap()
            } catch (_: Throwable) {
                return emptyMap()
            }

            // English only (see EN_DISPLAY): pick the first English caption and stop,
            // rather than walking the remaining ten languages.
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                if (englishOrNull(c.optString("display", "")) == null) continue
                val file = c.optString("file", "")
                if (file.isEmpty() || !file.startsWith("http")) continue
                return mapOf(
                    "en" to ResolvedSubtitle(
                        lang = "en",
                        url = file,
                        // The signed URLs are CloudFront-style and time-limited,
                        // so the CDN wants the same UA/referer that fetched them.
                        headers = mapOf(
                            "User-Agent" to UA,
                            "Referer" to REFERER,
                        ),
                    ),
                )
            }
            return emptyMap()
        }

        /**
         * POST /backend/willierevillame -> Pair(token, ts).
         *
         * Sends `Origin` alongside the Referer: without it the endpoint answers
         * `{"success":false,"error":"Internal Server Error"}`.
         */
        private fun token(id: String, type: String, season: Int, episode: Int): Pair<String, String>? =
            try {
                val body = JSONObject()
                    .put(K_TMDB, id)
                    .put(K_TYPE, type)
                    .put(K_SERVER, SUB_SERVER)
                    .put(K_SEASON, season)
                    .put(K_EPISODE, episode)
                    .toString()
                // Http.postJson returns the raw okhttp3.Response, not a body
                // string (ZxcResolver wraps it with .use{} for exactly this).
                val text = Http.postJson(
                    "$ORIGIN/backend/willierevillame",
                    body,
                    headers = mapOf(
                        "User-Agent" to UA,
                        "Origin" to ORIGIN,
                        "Referer" to "$ORIGIN/player/tv/$id/$season/$episode",
                        "Accept" to "application/json",
                    ),
                    timeoutMs = TIMEOUT,
                ).use { r -> r.body.string() }
                val o = JSONObject(text)
                val tok = o.optString("token", "")
                val ts = o.optString("ts", "")
                if (tok.isEmpty() || ts.isEmpty()) null else tok to ts
            } catch (_: Throwable) {
                null
            }
    }
}