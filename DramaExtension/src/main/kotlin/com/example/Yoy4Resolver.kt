package com.example

import com.lagradost.cloudstream3.network.CloudflareKiller

/**
 * Server 6 of the k-drama.in watch page ("YOY") - the chain that actually
 * plays, verified live 2026-10-03.
 *
 * The watch page's `switchServer(6)` sets the player iframe to
 * `https://k-drama.in/yoy4.php?id=<tmdbId>&s=<season>&e=<episode>`, and that
 * page is a one-line proxy: it embeds
 * `https://kisskh.megaplay.su/kisskh/<megaplayId>` and nothing else.
 *
 * The rest of the chain, all verified with a live fetch:
 *
 * 1. `kisskh.megaplay.su/kisskh/<id>` is **referer-gated**: with no Referer
 *    it answers `403 "Embed Only"`; with `Referer: https://k-drama.in/` it
 *    answers `200 "KissKH Player"`. The referer only has to *be* a
 *    k-drama.in page - any path on the host works.
 * 2. That page embeds one m3u8 on the same host plus a set of `.srt` tracks
 *    (`…/<hash>/<name>.en.srt`, `.ar.srt`, `.id.srt`, `.km.srt`, `.ms.srt`,
 *    `.nl.srt`, …). English confirmed: 51,960 bytes, correct SRT timing.
 * 3. **The m3u8 itself has a different referer gate than the page**: it
 *    answers `403 {"error":"forbidden"}` for a k-drama.in referer or none,
 *    and `200` only for a self-referer (`Referer: https://kisskh.megaplay.su/`
 *    or the player page URL). Media segments then fetch fine with or without
 *    it, but the playlist must be requested with the self-referer.
 *
 * So the emitted link must carry `referer = https://kisskh.megaplay.su/` and
 * the same header must be used to fetch the playlist; emitting the bare m3u8
 * without it yields a 403 at playback time.
 *
 * This runs as a fallback behind the primary vidsync embed (per fallback
 * discipline: a partial regression must still yield a playable link).
 * Verified end to end for `watch.php?id=290699&season=1&episode=1`
 * ("Shadow Punisher" S1E1): playlist 200 / 134,510 bytes, media segment 200 /
 * 400,435,488 bytes, English .srt 200 / 51,960 bytes.
 */
class Yoy4Resolver {

    companion object {
        private const val NAME = "Server 6 (YOY)"
        private val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        /** Gate for the proxy page on k-drama.in. */
        private const val PAGE_REFERER = "https://k-drama.in/"

        /** Gate for the kisskh player page (any k-drama.in page works). */
        private const val PLAYER_REFERER = PAGE_REFERER

        /** Gate for the m3u8 + .srt: the kisskh host itself. */
        private const val MEGA_REFERER = "https://kisskh.megaplay.su/"

        private val iframeRe = Regex("src=\"(https://kisskh\\.megaplay\\.su/[^\"]+)\"")
        private val m3u8Re = Regex("(https://kisskh\\.megaplay\\.su/vid/[^\"\\s<]+\\.m3u8)")
        private val srtRe = Regex("(https://kisskh\\.megaplay\\.su/sub/[^\"\\s<]+\\.srt)")
        private val langOfRe = Regex("\\.([a-z]{2,3})\\.srt$")

        /** ISO-639 codes for the codes kisskh actually uses. */
        private val langNames = mapOf(
            "en" to "English",
            "ar" to "Arabic",
            "id" to "Indonesian",
            "ms" to "Malay",
            "km" to "Khmer",
            "nl" to "Dutch",
            "th" to "Thai",
            "vi" to "Vietnamese",
            "zh" to "Chinese",
            "es" to "Spanish",
            "fr" to "French",
            "de" to "German",
            "pt" to "Portuguese",
            "ru" to "Russian",
            "ja" to "Japanese",
            "ko" to "Korean",
            "tr" to "Turkish",
        )
    }

    /**
     * Resolves one episode through the yoy4 chain.
     *
     * @param data the episode URL, e.g.
     *   `https://k-drama.in/watch.php?id=290699&season=1&episode=1&type=tv`
     * @return sources + subtitles, or [ResolveResult.EMPTY] on any failure.
     */
    fun resolve(data: String): ResolveResult {
        return try {
            val id = Regex("id=(\\d+)").find(data)?.groupValues?.get(1)
                ?: return ResolveResult.EMPTY
            val season = Regex("season=(\\d+)").find(data)?.groupValues?.get(1) ?: "1"
            val episode = Regex("episode=(\\d+)").find(data)?.groupValues?.get(1) ?: "1"

            val yoyUrl = "$PAGE_REFERER" + "yoy4.php?id=$id&s=$season&e=$episode"
            val yoyHtml = Http.get(
                yoyUrl,
                headers = mapOf("User-Agent" to UA, "Referer" to PAGE_REFERER),
                interceptor = killer(),
            ).text

            val playerUrl = iframeRe.find(yoyHtml)?.groupValues?.get(1)
                ?: return ResolveResult.EMPTY

            val playerHtml = Http.get(
                playerUrl,
                headers = mapOf("User-Agent" to UA, "Referer" to PLAYER_REFERER),
            ).text

            val m3u8 = m3u8Re.find(playerHtml)?.groupValues?.get(1)
                ?: return ResolveResult.EMPTY

            // The playlist needs the self-referer; fetch it once so a dead
            // playlist is reported as "no sources" here rather than failing
            // silently at playback time.
            val probe = Http.get(
                m3u8,
                headers = mapOf("User-Agent" to UA, "Referer" to MEGA_REFERER),
            )
            if (probe.code != 200) return ResolveResult.EMPTY
            val playlist = probe.text
            if (!playlist.contains("#EXTM3U")) return ResolveResult.EMPTY

            val subs = LinkedHashMap<String, ResolvedSubtitle>()
            for (m in srtRe.findAll(playerHtml)) {
                val url = m.groupValues[1]
                val code = langOfRe.find(url)?.groupValues?.get(1) ?: continue
                if (!langNames.containsKey(code)) continue
                if (subs.containsKey(code)) continue
                subs[code] = ResolvedSubtitle(
                    lang = langNames[code].orEmpty().ifBlank { code },
                    url = url,
                    trust = if (code == "en") 2 else 1,
                    headers = mapOf("Referer" to MEGA_REFERER),
                )
            }

            ResolveResult(
                ok = true,
                sources = listOf(
                    ResolvedSource(
                        name = "$NAME ${playlist.qualityLabel()}",
                        url = m3u8,
                        referer = MEGA_REFERER,
                        quality = 0,
                        qualityLabel = playlist.qualityLabel(),
                        type = "hls",
                        headers = mapOf("Referer" to MEGA_REFERER),
                    )
                ),
                subtitles = subs.values.toList(),
            ).dedupe()
        } catch (_: Throwable) {
            ResolveResult.EMPTY
        }
    }

    /** Best-effort "1080p"-style label from the playlist's EXT-X-STREAM-INF
     *  entries; empty for a single-quality media playlist. */
    private fun String.qualityLabel(): String {
        val m = Regex("RESOLUTION=\\d+x(\\d+)").find(this) ?: return ""
        val h = m.groupValues[1].toIntOrNull() ?: return ""
        return when {
            h >= 2000 -> "${h / 1000}k"
            h >= 1000 -> "${h}p"
            else -> ""
        }
    }

    /**
     * Shared Cloudflare interceptor for the yoy4 proxy fetch.
     *
     * Wrapped in [SerializedCloudflareKiller] for the same reason as
     * KDramaIn: a bare CloudflareKiller lets every concurrent cold request
     * spawn its own WebView. Typed as the [okhttp3.Interceptor] interface
     * because the wrapper is a decorator, not a subclass.
     */
    @Volatile
    private var killerSingleton: okhttp3.Interceptor? = null

    @Synchronized
    private fun killer(): okhttp3.Interceptor =
        killerSingleton ?: SerializedCloudflareKiller(CloudflareKiller())
            .also { killerSingleton = it }
}