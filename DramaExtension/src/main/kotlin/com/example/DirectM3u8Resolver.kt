package com.example

import org.jsoup.Jsoup

/**
 * Catch-all resolver (registry fallback): bare m3u8/mp4 URLs in the embed
 * page HTML, plus lazy-load attribute mining (data-src etc.).
 */
object DirectM3u8Resolver : Resolver {
    override val hosts: List<String> = emptyList() // catch-all: must stay last in the registry

    private val HEADERS = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    private val m3u8Re = Regex("https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*")
    private val mp4Re = Regex("https?://[^\"'\\s<>]+\\.mp4[^\"'\\s<>]*")

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
        val html = page.text
        val urls = LinkedHashSet<String>()
        m3u8Re.findAll(html).forEach { urls += it.value }
        // Lazy-load / attribute mining (data-src, src, data-lazy-src).
        try {
            val doc = Jsoup.parse(html, embedUrl)
            for (el in doc.select("[src], [data-src], [data-lazy-src]")) {
                for (attr in listOf("src", "data-src", "data-lazy-src")) {
                    val v = el.attr(attr)
                    if (v.isBlank() || !v.startsWith("http")) continue
                    if (v.contains(".m3u8") || v.contains(".mp4")) urls += v
                }
            }
        } catch (_: Throwable) {
            // attribute mining is best-effort
        }
        mp4Re.findAll(html).forEach {
            if (it.value.contains(".mp4")) urls += it.value
        }
        if (urls.isEmpty()) return ResolveResult.EMPTY
        val name = label ?: "Direct"
        val sources = urls.map { u ->
            ResolvedSource(
                name = name,
                url = u,
                referer = embedUrl,
                type = if (u.contains(".m3u8", ignoreCase = true)) "hls" else "mp4",
            )
        }
        return ResolveResult(true, sources)
    }
}
