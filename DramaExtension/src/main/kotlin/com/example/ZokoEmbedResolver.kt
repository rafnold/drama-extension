package com.example

import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Chain B: embedload.cfd/watch?v=N -> inner zoko/otaku embed.
 *
 *   embed page  ->  inner <iframe src="https://<host>/<token>">
 *   inner page  ->  window.__P="<b64>": base64-decode, XOR each byte with a
 *                   short repeating key -> JSON {"src":"<m3u8>",
 *                   "subtitles":[{file,label}]}
 *
 * The XOR key is a short string literal on the inner page. Known keys are
 * tried first; if none yields a valid JSON payload, short string constants
 * found in the page are mined (so a key rotation keeps working).
 */
object ZokoEmbedResolver : Resolver {
    override val hosts = listOf("embedload", "zokoanime", "otakuembed")

    /** Known XOR keys (kept list-shaped so rotations can be added). */
    internal val ZOKO_XOR_SEEDS: List<String> = listOf("otaku-embed-v1")

    private val HEADERS = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    private val zokoBlobRe = Regex("window\\.__P\\s*=\\s*\"([^\"]+)\"")
    private val m3u8Re = Regex("https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*")
    private val stringLitRe = Regex("'([A-Za-z0-9_@.\\-]{3,24})'")

    override fun resolve(ctx: ResolveContext, embedUrl: String, label: String?): ResolveResult {
        if (ctx.remainingMs() <= 0) return ResolveResult.EMPTY
        val host = Resolvers.hostOf(embedUrl)
        var inner = embedUrl
        if (host.contains("embedload")) {
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
            inner = Jsoup.parse(page.text, embedUrl).select("iframe[src]")
                .mapNotNull { absoluteUrl(it.attr("src"), embedUrl) }
                .firstOrNull { it != embedUrl && !Resolvers.hostOf(it).contains("embedload") }
                ?: return ResolveResult.EMPTY
        }
        if (ctx.remainingMs() <= 0) return ResolveResult.EMPTY
        val innerResp = try {
            Http.get(inner, headers = HEADERS, referer = embedUrl,
                timeoutMs = ctx.remainingMs())
        } catch (_: Throwable) {
            return ResolveResult.EMPTY
        }
        if (innerResp.code in intArrayOf(404, 410)) {
            Cache.markDead(inner)
            return ResolveResult.EMPTY
        }
        val html = innerResp.text

        val blob = zokoBlobRe.find(html)?.groupValues?.get(1)
        if (blob != null) {
            val json = decodePayload(blob, html)
            if (json != null) {
                try {
                    val obj = JSONObject(json)
                    val src = obj.optString("src").ifBlank { obj.optString("file") }
                    if (src.startsWith("http")) {
                        val subs = mutableListOf<ResolvedSubtitle>()
                        val arr = obj.optJSONArray("subtitles")
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val s = arr.optJSONObject(i) ?: continue
                                val file = s.optString("file").ifBlank { s.optString("url") }
                                    .ifBlank { s.optString("src") }
                                if (!file.startsWith("http")) continue
                                val lang = ResolverCrypto.langCode(
                                    s.optString("label")
                                        .ifBlank { s.optString("lang") }
                                        .ifBlank { s.optString("kind") }
                                ) ?: "en"
                                subs += ResolvedSubtitle(lang, file)
                            }
                        }
                        val name = inner.substringAfter("://").substringBefore("/")
                            .ifBlank { "Zoko" }
                            .replaceFirstChar { it.uppercase() }
                        return ResolveResult(
                            ok = true,
                            sources = listOf(ResolvedSource(name, src, inner, type = "hls")),
                            subtitles = subs,
                        )
                    }
                } catch (_: Throwable) {
                    // fall through to the raw m3u8 scan
                }
            }
        }
        // Fallback: bare m3u8 URLs in the inner page.
        val urls = m3u8Re.findAll(html).map { it.value }.toList().distinct()
        if (urls.isEmpty()) return ResolveResult.EMPTY
        val fallback = label ?: "Embedload"
        return ResolveResult(
            ok = true,
            sources = urls.map { ResolvedSource(fallback, it, inner, type = "hls") },
        )
    }

    /**
     * Try the known keys, then short string literals from the page, until one
     * XOR-decodes [blob] into a JSON payload with a playable src.
     */
    private fun decodePayload(blob: String, page: String): String? {
        val mined = stringLitRe.findAll(page)
            .map { it.groupValues[1] }
            .distinct()
            .take(100)
        for (seed in ZOKO_XOR_SEEDS + mined) {
            val v = ResolverCrypto.xorB64(blob, seed) ?: continue
            val t = v.trimStart()
            if (t.startsWith("{") && (t.contains("src") || t.contains("file"))) {
                try {
                    val obj = JSONObject(t)
                    val src = obj.optString("src").ifBlank { obj.optString("file") }
                    if (src.startsWith("http")) return t
                } catch (_: Throwable) {
                    // not valid JSON - keep trying
                }
            }
        }
        return null
    }
}
