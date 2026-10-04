package com.example

import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * KissKH megaplay embed: kisskh.megaplay.su embed page with
 * <script id="player-payload"> (or legacy id="megaplay-player") holding
 * JSON {source, tracks:[{file,label}]}.
 */
object MegaplayResolver : Resolver {
    override val hosts = listOf("megaplay")

    private val HEADERS = mapOf("User-Agent" to UA)
    private val payloadRe = Regex(
        "id=['\"]?(?:player-payload|megaplay-player)['\"]?[^>]*>(.*?)</script>",
        RegexOption.DOT_MATCHES_ALL,
    )

    override fun resolve(ctx: ResolveContext, embedUrl: String, label: String?): ResolveResult {
        if (ctx.remainingMs() <= 0) return ResolveResult.EMPTY
        val root = originRoot(embedUrl)
        val page = try {
            Http.get(embedUrl, headers = HEADERS, referer = root,
                timeoutMs = ctx.remainingMs())
        } catch (_: Throwable) {
            return ResolveResult.EMPTY
        }
        if (page.code in intArrayOf(404, 410)) {
            Cache.markDead(embedUrl)
            return ResolveResult.EMPTY
        }
        val html = page.text
        val payload = Jsoup.parse(html, embedUrl)
            .selectFirst("script#player-payload, script#megaplay-player")?.data()
            ?: payloadRe.find(html)?.groupValues?.get(1)
            ?: return ResolveResult.EMPTY
        val j = try {
            JSONObject(payload)
        } catch (_: Throwable) {
            return ResolveResult.EMPTY
        }
        val source = j.optString("source")
        if (!source.startsWith("http")) return ResolveResult.EMPTY

        val subs = mutableListOf<ResolvedSubtitle>()
        j.optJSONArray("tracks")?.let { tracks ->
            for (i in 0 until tracks.length()) {
                val t = tracks.optJSONObject(i) ?: continue
                val f = t.optString("file")
                if (!f.startsWith("http")) continue
                subs += ResolvedSubtitle(
                    lang = langOf(t.optString("label"), f),
                    url = f,
                    headers = mapOf("User-Agent" to UA, "Referer" to root),
                )
            }
        }
        return ResolveResult(
            ok = true,
            sources = listOf(
                ResolvedSource(label ?: "Megaplay", source, root, type = "hls")
            ),
            subtitles = subs,
        )
    }
}
