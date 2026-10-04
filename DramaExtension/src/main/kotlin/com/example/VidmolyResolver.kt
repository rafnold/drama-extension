package com.example

/**
 * Vidmoly (vidmoly.biz) embed: the m3u8 URL is embedded straight in the
 * embed page HTML (first .m3u8 match).
 */
object VidmolyResolver : Resolver {
    override val hosts = listOf("vidmoly")

    private val HEADERS = mapOf("User-Agent" to UA)
    private val m3u8Re = Regex("(https?://[^\"'\\s<>]*\\.m3u8[^\"'\\s<>]*)")

    override fun resolve(ctx: ResolveContext, embedUrl: String, label: String?): ResolveResult {
        if (ctx.remainingMs() <= 0) return ResolveResult.EMPTY
        val page = try {
            Http.get(embedUrl, headers = HEADERS, timeoutMs = ctx.remainingMs())
        } catch (_: Throwable) {
            return ResolveResult.EMPTY
        }
        if (page.code in intArrayOf(404, 410)) {
            Cache.markDead(embedUrl)
            return ResolveResult.EMPTY
        }
        val m3u8 = m3u8Re.find(page.text)?.groupValues?.get(1)
            ?.trimEnd('\'', '"', ';', ')')
            ?: return ResolveResult.EMPTY
        if (!m3u8.startsWith("http")) return ResolveResult.EMPTY
        return ResolveResult(
            ok = true,
            sources = listOf(
                ResolvedSource(label ?: "Vidmoly", m3u8, embedUrl, type = "hls")
            ),
        )
    }
}
