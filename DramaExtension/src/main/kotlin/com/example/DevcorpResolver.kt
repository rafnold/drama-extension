package com.example

import org.json.JSONArray
import org.jsoup.Jsoup
import java.net.URLDecoder

/**
 * k-drama.in watch-page **server 3** (`/2.php/<id>/<s>/<e>`).
 *
 * This is the server that carries the titles vidsync lacks. Verified live
 * 2026-10-03 for "Fangs of Fortune" (id=239389 s1e1), the exact case the user
 * reported as broken in the app but working in the browser: vidsync (server 1,
 * our previous single primary) answers `521: Web server is down`, while server 3
 * serves the episode.
 *
 * ## Mechanism
 *
 * The page is a thin wrapper around one iframe:
 *
 * ```
 * https://s1.devcorp.me/player/player.html
 *     ?title=<episode title>
 *     &file=<URL-encoded JSON array:  [{ "title": "Server 1 (moviebox)",
 *                                         "file": "https://….m3u8" }, …]>
 *     &subtitle=<URL-encoded JSON array: [ { "name": "English",
 *                                             "file": "https://….vtt" }, … ]>
 *     &subtitle_start=true
 * ```
 *
 * So both the stream and the subtitles are JSON *inside query parameters* -
 * not plain markup - which is exactly why the generic host-matched resolver
 * (`Resolvers.forHost`) could never see them: there is no `<video>`, no
 * `<source>`, and no literal m3u8 in the body, only a `player.html` URL.
 *
 * Verified end to end: 1 m3u8 (`https://aapanel.devcorp.me/assets/….m3u8`,
 * HTTP 200, starts with `#EXTM3U`) and **22 subtitle tracks** (English first,
 * plus Français/Española/Português/Arabic/বাংলা/हिन्दी/Türk/中文/…; English
 * `.vtt` = 39,884 bytes). Neither the playlist nor the subtitles require a
 * Referer - both returned 200 with and without one - so no special headers are
 * attached.
 *
 * This is why the multi-server fan-out in [MultiServerResolver] only got part
 * of the way: it tried server 3 but the generic resolver returned no sources
 * for it. This resolver closes that gap, and is invoked from `loadLinks`
 * whenever the fan-out produced nothing.
 */
class DevcorpResolver {

    companion object {
        private val iframeRe = Regex("<iframe[^>]+src=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        private const val NAME = "Server 3 (moviebox)"

        /** Query values are percent-encoded JSON; decode before parsing. */
        private fun param(src: String, key: String): String? {
            val raw = Regex("[?&]$key=([^&]*)").find(src)?.groupValues?.get(1) ?: return null
            return try {
                URLDecoder.decode(raw.replace("&amp;", "&"), "UTF-8")
            } catch (_: Throwable) {
                null
            }
        }
    }

    /**
     * Resolves one episode through server 3.
     *
     * @param pageUrl the already-fetched `/2.php/<id>/<s>/<e>` URL, or a URL to
     *   fetch. [preFetchedHtml] avoids a second request when the caller already
     *   has the page.
     */
    fun resolve(pageUrl: String, preFetchedHtml: String? = null): ResolveResult {
        return try {
            val html = preFetchedHtml ?: Http.get(
                pageUrl,
                // MUST go through the shared Cloudflare interceptor: this page
                // is on k-drama.in, so a plain request gets a 403 challenge
                // whenever the clearance is not already cached, and with no
                // iframe in the body this resolver would silently return
                // EMPTY. Sharing KDramaIn's single instance also means it
                // reuses the cookie that provider already solved.
                interceptor = CloudflareGate.interceptor(),
            ).text
            if (html.isBlank()) return ResolveResult.EMPTY

            val iframe = iframeRe.find(html)?.groupValues?.get(1)
                ?: return ResolveResult.EMPTY
            val src = iframe.replace("&amp;", "&")

            val fileJson = param(src, "file") ?: return ResolveResult.EMPTY
            val files = try {
                JSONArray(fileJson)
            } catch (_: Throwable) {
                return ResolveResult.EMPTY
            }
            if (files.length() == 0) return ResolveResult.EMPTY

            val sources = ArrayList<ResolvedSource>()
            for (i in 0 until files.length()) {
                val o = files.optJSONObject(i) ?: continue
                val url = o.optString("file").trim()
                if (url.isEmpty() || !url.startsWith("http")) continue
                val label = o.optString("title").trim().ifBlank { "$NAME ${i + 1}" }
                val quality = Regex("(\\d{3,4})p").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                sources += ResolvedSource(
                    name = label,
                    url = url,
                    // Verified: the playlist answers 200 with or without a
                    // Referer, so none is pinned here.
                    referer = "",
                    quality = quality,
                    qualityLabel = if (quality > 0) "${quality}p" else "",
                    type = "hls",
                )
            }
            if (sources.isEmpty()) return ResolveResult.EMPTY

            val subtitles = ArrayList<ResolvedSubtitle>()
            param(src, "subtitle")?.let { subJson ->
                try {
                    val arr = JSONArray(subJson)
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val url = o.optString("file").trim()
                        val name = o.optString("name").trim()
                        if (url.isEmpty() || name.isEmpty()) continue
                        subtitles += ResolvedSubtitle(
                            lang = name,
                            url = url,
                            // English first in CloudStream's subtitle list.
                            trust = if (name.equals("english", true)) 3 else 1,
                        )
                    }
                } catch (_: Throwable) {
                    // Subtitles are optional; the stream is what matters.
                }
            }

            val kept = ArrayList<ResolvedSource>()
            for (s in sources) {
                // Reject image-only playlists. Verified 2026-10-03 for
                // "Fangs of Fortune": server 3's m3u8 has 556 segments and every
                // one is a .png on uk.8273671.xyz - a slideshow, not video. It
                // parses cleanly and *looks* like a valid HLS source, so
                // without this check CloudStream happily plays a static-image
                // "video" at whatever quality the user then complains about.
                if (!isRealVideo(s.url)) continue
                kept += s
            }
            if (kept.isEmpty()) return ResolveResult.EMPTY

            ResolveResult(ok = true, sources = kept, subtitles = subtitles).dedupe()
        } catch (_: Throwable) {
            ResolveResult.EMPTY
        }
    }

    /**
     * True when [playlistUrl] looks like a real video playlist.
     *
     * Fetches the playlist and looks at its segment extensions. Anything that
     * is purely `.png`/`.jpg`/`.jpeg`/`.webp`/`.gif` is an image playlist and is
     * rejected. An unreachable playlist is treated as *probably fine* (we
     * cannot prove otherwise without penalising every real source), and an
     * actual `#EXT-X-STREAM-INF` master playlist is always accepted since its
     * variants are resolved by the player.
     */
    private fun isRealVideo(playlistUrl: String): Boolean {
        val body = try {
            Http.get(playlistUrl).text
        } catch (_: Throwable) {
            return true
        }
        if (body.isBlank()) return true
        // Master playlist: variants carry the real bitrates, let the player pick.
        if (body.contains("#EXT-X-STREAM-INF")) return true

        val segments = Regex("^([^\\s#]+)$", RegexOption.MULTILINE)
            .findAll(body)
            .map { it.groupValues[1].lowercase() }
            .filter { it.startsWith("http") || it.contains('.') }
            .toList()
        if (segments.isEmpty()) return true

        val imageExts = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp")
        val videoSegments = segments.filterNot { s -> imageExts.any { s.endsWith(it) } }
        // All-image playlist => slideshow, reject. Anything with a real media
        // segment is kept.
        return videoSegments.isNotEmpty()
    }
}