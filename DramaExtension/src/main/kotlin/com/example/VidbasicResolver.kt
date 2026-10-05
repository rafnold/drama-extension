package com.example

import org.jsoup.Jsoup

/**
 * Chain C: vidbasic.top/embed/<id> -> 3rdplayer.html -> AES m3u8.
 *
 *   embed page   ->  <iframe id="embedvideo" src="/3rdplayer.html?key=<b64>">
 *   3rdplayer    ->  <div data-name="crypto" data-value="<b64>">
 *   AES-256-CBC (PKCS7) with fixed key/iv (ASCII digit strings)
 *   -> "<m3u8 url>"
 *
 * The key/iv pair is a fixed pair embedded in the page; known pairs are
 * tried first (list-shaped so rotations can be added).
 *
 * ## Multi-server wrapper pattern
 *
 * Some vidbasic.top embeds are multi-server wrappers that list multiple
 * providers (Streamwish, Vidhide, Doodstream, etc.) in <li> elements with
 * `data-video` attributes, and load one in an <iframe>. This resolver
 * extracts the `data-video` URLs and returns them as separate sources,
 * so the generic resolver layer can try each provider.
 */
object VidbasicResolver : Resolver {
    override val hosts = listOf("vidbasic")

    /** Known (key, iv) pairs, tried in order until one yields an http m3u8. */
    internal val VIDBASIC_AES_SEEDS: List<Pair<String, String>> get() = SiteConfig.vidbasicAesSeeds()

    private val HEADERS = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    private val cryptoValueRe =
        Regex("data-name=\"crypto\"[^>]*data-value=\"([^\"]+)\"")
    private val keyParamRe = Regex("[?&]key=([^&\"']+)", RegexOption.IGNORE_CASE)

    override fun resolve(ctx: ResolveContext, embedUrl: String, label: String?): ResolveResult {
        if (ctx.remainingMs() <= 0) return ResolveResult.EMPTY
        val page = try {
            Http.get(embedUrl, headers = HEADERS, referer = ctx.pageUrl,
                timeoutMs = ctx.remainingMs())
        } catch (_: Throwable) {
            return ResolveResult.EMPTY
        }
        if (page.code in intArrayOf(404, 410)) {
            Cache.markDead(embedUrl)
            return ResolveResult.EMPTY
        }

        // Check for the multi-server wrapper pattern: <li> elements with data-video
        val dataVideos = Jsoup.parse(page.text, embedUrl)
            .select("li[data-video]")
            .map { it.attr("data-video") }
            .filter { it.isNotBlank() && it.startsWith("http") }

        if (dataVideos.isNotEmpty()) {
            // Multi-server wrapper: return each provider as a separate source.
            // The generic resolver layer will try each one.
            val sources = dataVideos.mapIndexed { i, url ->
                ResolvedSource(
                    name = "Vidbasic $i",
                    url = url,
                    referer = embedUrl,
                    quality = 0,
                    qualityLabel = "",
                    type = "other",
                )
            }
            return ResolveResult(ok = true, sources = sources).dedupe()
        }

        // Original pattern: 3rdplayer.html with AES encryption
        val thirdUrl = Jsoup.parse(page.text, embedUrl)
            .selectFirst("iframe#embedvideo")?.attr("src")
            ?: Jsoup.parse(page.text, embedUrl)
                .select("iframe[src]")
                .firstOrNull { it.attr("src").contains("3rdplayer") }?.attr("src")
            ?: return ResolveResult.EMPTY
        val fullThird = absoluteUrl(thirdUrl, embedUrl)
            ?.takeIf { it.startsWith("http") } ?: return ResolveResult.EMPTY

        if (ctx.remainingMs() <= 0) return ResolveResult.EMPTY
        val thirdResp = try {
            Http.get(fullThird, headers = HEADERS, referer = embedUrl,
                timeoutMs = ctx.remainingMs())
        } catch (_: Throwable) {
            return ResolveResult.EMPTY
        }
        if (thirdResp.code in intArrayOf(404, 410)) {
            Cache.markDead(fullThird)
            return ResolveResult.EMPTY
        }
        val blob = cryptoValueRe.find(thirdResp.text)?.groupValues?.get(1)
            ?: keyParamRe.find(fullThird)?.groupValues?.get(1)
            ?: return ResolveResult.EMPTY
        val data = try {
            java.net.URLDecoder.decode(blob, "UTF-8")
        } catch (_: Throwable) {
            blob
        }

        for ((key, iv) in VIDBASIC_AES_SEEDS) {
            val m3u8 = ResolverCrypto.aesCbcDecode(
                key.toByteArray(Charsets.UTF_8),
                iv.toByteArray(Charsets.UTF_8),
                ResolverCrypto.decodeB64(data),
            )?.takeIf { it.trim().startsWith("http") }
            if (m3u8 != null) {
                val name = label ?: "Vidbasic"
                return ResolveResult(
                    ok = true,
                    sources = listOf(ResolvedSource(name, m3u8.trim(), fullThird, type = "hls")),
                )
            }
        }
        return ResolveResult.EMPTY
    }
}
