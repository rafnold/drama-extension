package com.example

import org.json.JSONArray
import org.jsoup.Jsoup

/**
 * Chain A: dramavideo.se/watch?v=N  (shared by DramaNice + Dramahood).
 *
 *   watch page  ->  <li class="linkserver" data-video="<code>"
 *                    data-provider="<sv>">
 *   player page ->  https://<playerhost>/?id=<code>&sv=<sv>
 *                   (player host is built from base64 `parts` in
 *                   player.js; falls back to player.dramavideo.se)
 *   inline script: encData=<b64>, keyHex, ivHex -> AES-256-CBC (PKCS7)
 *   decrypt -> sources [{file, type, label}] + tracks [{file, label}]
 */
object DramavideoResolver : Resolver {
    override val hosts = listOf("dramavideo")

    private const val PLAYER_JS_FALLBACK = "https://dramavideo.se/player.js"
    private const val PLAYER_HOST_FALLBACK = "https://player.dramavideo.se"
    private val HEADERS = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    private val partsRe = Regex("parts\\s*=\\s*\\[([^\\]]+)\\]")
    private val encDataRe = Regex("encData\\s*=\\s*\"([^\"]+)\"")
    private val keyHexRe = Regex("keyHex\\s*=\\s*\"([0-9a-fA-F]+)\"")
    private val ivHexRe = Regex("ivHex\\s*=\\s*\"([0-9a-fA-F]+)\"")
    private val sourcesJsonRe = Regex("sources\\s*=\\s*JSON\\.parse\\(`([^`]*)`\\)")
    private val tracksJsonRe = Regex("tracks\\s*=\\s*JSON\\.parse\\(`([^`]*)`\\)")
    private val fileObjRe = Regex("\\{[^{}]*\"file\"[^{}]*\\}")
    private val fileRe = Regex("\"file\"\\s*:\\s*\"(https?://[^\"]+)\"")
    private val typeRe = Regex("\"type\"\\s*:\\s*\"([^\"]+)\"")
    private val labelRe = Regex("\"label\"\\s*:\\s*\"([^\"]+)\"")
    private val m3u8Re = Regex("https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*")

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
        val servers = Jsoup.parse(html, embedUrl).select("li.linkserver")
            .mapNotNull { li ->
                val code = li.attr("data-video")
                val sv = li.attr("data-provider").ifBlank { "server" }
                if (code.isBlank()) null else code to sv
            }
            .distinct()
        if (servers.isEmpty()) return ResolveResult.EMPTY

        val playerHost = resolvePlayerHost(html, embedUrl, ctx)
        val prefix = label ?: "Dramavideo"
        val sources = mutableListOf<ResolvedSource>()
        val subs = mutableListOf<ResolvedSubtitle>()

        for ((code, sv) in servers) {
            if (ctx.remainingMs() <= 0) break
            val playerUrl = "$playerHost/?id=$code&sv=$sv"
            val phtml = try {
                val r = Http.get(playerUrl, headers = HEADERS, referer = embedUrl,
                    timeoutMs = ctx.remainingMs())
                if (r.code in intArrayOf(404, 410)) {
                    Cache.markDead(playerUrl)
                    null
                } else {
                    r.text
                }
            } catch (_: Throwable) {
                null
            } ?: continue

            val plain = try {
                val enc = encDataRe.find(phtml) ?: continue
                val key = keyHexRe.find(phtml) ?: continue
                val iv = ivHexRe.find(phtml) ?: continue
                ResolverCrypto.aesCbcDecode(key.groupValues[1], iv.groupValues[1],
                    enc.groupValues[1])
            } catch (_: Throwable) {
                null
            } ?: continue

            // Subtitles (Dramahood form: const tracks = JSON.parse(`[ ... ]`)).
            tracksJsonRe.find(plain)?.groupValues?.get(1)?.let { t ->
                try {
                    val arr = JSONArray(t)
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val file = o.optString("file").ifBlank { o.optString("url") }
                            .ifBlank { o.optString("src") }
                        if (!file.startsWith("http")) continue
                        val lang = ResolverCrypto.langCode(
                            o.optString("label").ifBlank { o.optString("kind") }
                        ) ?: "en"
                        subs += ResolvedSubtitle(lang, file)
                    }
                } catch (_: Throwable) {
                    // malformed tracks - keep going without subtitles
                }
            }

            // Sources: JSON.parse form (Dramahood) -> object scan (DramaNice)
            // -> bare m3u8 scan.
            val found = parseSources(plain)
            for ((file, type, slabel) in found) {
                sources += ResolvedSource(
                    name = if (slabel.isNotBlank()) "$prefix $sv ($slabel)"
                    else "$prefix $sv",
                    url = file,
                    referer = playerUrl,
                    type = type,
                )
            }
        }
        return ResolveResult(sources.isNotEmpty(), sources, subs)
    }

    /** (file, type, label) triples from the decrypted player page. */
    private fun parseSources(plain: String): List<Triple<String, String, String>> {
        val out = mutableListOf<Triple<String, String, String>>()
        sourcesJsonRe.find(plain)?.groupValues?.get(1)?.let { json ->
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val file = o.optString("file")
                    if (!file.startsWith("http")) continue
                    out += Triple(file,
                        o.optString("type").ifBlank { sniffType(file) },
                        o.optString("label"))
                }
            } catch (_: Throwable) {
                // fall through to the object scan
            }
        }
        if (out.isEmpty()) {
            for (obj in fileObjRe.findAll(plain)) {
                val file = fileRe.find(obj.value)?.groupValues?.get(1) ?: continue
                out += Triple(file,
                    typeRe.find(obj.value)?.groupValues?.get(1) ?: sniffType(file),
                    labelRe.find(obj.value)?.groupValues?.get(1).orEmpty())
            }
        }
        if (out.isEmpty()) {
            m3u8Re.findAll(plain).forEach { out += Triple(it.value, "hls", "") }
        }
        return out
    }

    private fun sniffType(url: String): String = when {
        url.contains(".m3u8", ignoreCase = true) -> "hls"
        url.contains(".mpd", ignoreCase = true) -> "dash"
        else -> "mp4"
    }

    /**
     * The player iframe host is built from base64 `parts` in player.js.
     * Cached per player.js URL (the rotation key changes rarely).
     */
    private fun resolvePlayerHost(watchHtml: String, watchUrl: String,
                                  ctx: ResolveContext): String {
        val jsUrl = try {
            Jsoup.parse(watchHtml, watchUrl).select("script[src]")
                .map { it.attr("src") }
                .firstOrNull { it.contains("player.js") }
                ?.let { absoluteUrl(it, "https://dramavideo.se/") }
                ?: PLAYER_JS_FALLBACK
        } catch (_: Throwable) {
            PLAYER_JS_FALLBACK
        }
        Cache.playerHosts.get(jsUrl)?.let { return it }
        var host = PLAYER_HOST_FALLBACK
        try {
            if (ctx.remainingMs() > 0) {
                val js = Http.get(jsUrl, headers = HEADERS, referer = watchUrl,
                    timeoutMs = ctx.remainingMs()).text
                partsRe.findAll(js).lastOrNull()?.groupValues?.get(1)?.let { p ->
                    val b64 = p.substringAfter("\"").let { s ->
                        Regex("\"([^\"]+)\"").findAll(s).joinToString("") { it.groupValues[1] }
                    }
                    ResolverCrypto.b64(b64).trim().trimEnd('/')
                        .takeIf { it.startsWith("http") }?.let { host = it }
                }
            }
        } catch (_: Throwable) {
            // keep the fallback
        }
        Cache.playerHosts.put(jsUrl, host)
        return host
    }
}

/** Shared desktop UA for the resolver layer. */
const val UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
