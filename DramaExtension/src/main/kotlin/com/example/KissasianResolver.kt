package com.example

import org.json.JSONArray
import org.json.JSONObject

/**
 * KissAsian player: catalog.dramavibe.cfd/player_embed.php?episode=N.
 *
 *   embed page  ->  player_source.php?episode=N -> {"ok","src","list":[m3u8]}
 *                   (legacy fallback: src / srcCdnList / bare m3u8 in page)
 *   subtitles   ->  subApi (signed, Referer-gated) JSON list
 */
object KissasianResolver : Resolver {
    override val hosts = listOf("dramavibe")

    private val HEADERS = mapOf("User-Agent" to UA)

    private val srcRe = Regex("var\\s+src\\s*=\\s*\"([^\"]+)\"")
    private val srcCdnListRe = Regex("var\\s+srcCdnList\\s*=\\s*\\[([^\\]]+)\\]")
    private val subApiRe = Regex("var\\s+subApi\\s*=\\s*\"([^\"]+)\"")
    private val m3u8Re = Regex("https?://[^\"',\\s]+\\.m3u8")
    private val epParamRe = Regex("[?&]episode=(\\d+)")
    private val originRe = Regex("https?://[^/]+")

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
        val playerHtml = page.text

        // 1) Runtime source resolution: player_source.php?episode=N
        val streamUrls = LinkedHashSet<String>()
        val epParam = epParamRe.find(embedUrl)?.groupValues?.get(1)
        if (!epParam.isNullOrBlank()) {
            val origin = originRe.find(embedUrl)?.value
            if (origin != null) {
                try {
                    val r = Http.get("$origin/player_source.php?episode=$epParam",
                        headers = HEADERS, referer = embedUrl,
                        timeoutMs = ctx.remainingMs())
                    if (r.code == 200) {
                        val obj = JSONObject(r.text)
                        val list = obj.optJSONArray("list")
                        if (list != null) {
                            for (i in 0 until list.length()) {
                                val u = list.optString(i)
                                if (u.startsWith("http")) streamUrls += u
                            }
                        }
                        if (streamUrls.isEmpty()) {
                            val src = obj.optString("src")
                            if (src.startsWith("http")) streamUrls += src
                        }
                    }
                } catch (_: Throwable) {
                    // fall through to the in-page fallbacks below
                }
            }
        }

        // 2) Legacy fallback: sources embedded directly in the player page.
        if (streamUrls.isEmpty()) {
            srcCdnListRe.find(playerHtml)
                ?.groupValues?.get(1)
                ?.let { raw -> m3u8Re.findAll(raw).forEach { streamUrls += it.value } }
            srcRe.find(playerHtml)
                ?.groupValues?.get(1)
                ?.takeIf { it.contains(".m3u8") }
                ?.let { streamUrls += it }
            m3u8Re.findAll(playerHtml).forEach { streamUrls += it.value }
        }
        if (streamUrls.isEmpty()) return ResolveResult.EMPTY

        // Subtitles: subApi (signed, Referer-gated) from the player page.
        val subs = mutableListOf<ResolvedSubtitle>()
        val subApi = subApiRe.find(playerHtml)?.groupValues?.get(1)
        if (!subApi.isNullOrBlank() && ctx.remainingMs() > 0) {
            try {
                val r = Http.get(absoluteUrl(subApi, embedUrl) ?: subApi,
                    headers = HEADERS, referer = embedUrl,
                    timeoutMs = ctx.remainingMs())
                if (r.code == 200) {
                    val arr = JSONArray(r.text)
                    for (i in 0 until arr.length()) {
                        val s = arr.optJSONObject(i) ?: continue
                        val u = s.optString("url")
                        if (!u.startsWith("http")) continue
                        val lang = ResolverCrypto.langCode(s.optString("lang"))
                            ?: ResolverCrypto.langCode(s.optString("format"))
                            ?: ResolverCrypto.langCode(
                                u.substringBefore('?').substringAfterLast('/'))
                            ?: "en"
                        subs += ResolvedSubtitle(lang, u)
                    }
                }
            } catch (_: Throwable) {
                // no subtitles for this episode
            }
        }

        val prefix = label ?: "Dramavibe"
        val sources = streamUrls.withIndex().map { (i, u) ->
            ResolvedSource("$prefix ${i + 1}", u, embedUrl, type = "hls")
        }
        return ResolveResult(true, sources, subs)
    }
}
