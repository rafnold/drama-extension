package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Dramahood (https://dramahood.mom) - Asian drama / KShow streaming.
 *
 * The site is a plain WordPress install ("asianmozi" theme) with
 * server-rendered catalog, search and detail pages. Every title is a
 * series (K-dramas, C-dramas, J-dramas, variety shows) with episodes;
 * there are no movie pages.
 *
 *   Catalog:  /category/<cat>/  (paginated /category/<cat>/page/N/,
 *            over-run pages 404, no homepage leakage)
 *   Search:   /?s=<query> (10 results, same card markup as the catalog)
 *   Note: the two "latest releases" categories list individual episode
 *         posts (<slug>-episode-N/); toCards() normalizes those back to
 *         the series page and de-duplicates repeated series.
 *   Series:   /<slug>/  -> <h1> title, <img class="poster">,
 *            info <p><strong>Label:</strong> <span>value</span></p>,
 *            plot in <div class="right"><div class="info"><p>...</p></div>,
 *            episodes in <ul class="list-episode"> (descending order)
 *   Episode:  /<slug>-episode-N/ -> <iframe> to one of three player hosts
 *            (or an empty player div for episodes not uploaded yet).
 *
 * Player chains (all verified live, 2026-09-29):
 *
 *  A. dramavideo.se/watch?v=<n>
 *     -> <li class="linkserver" data-video="<code>" data-provider="<sv>">
 *        (one or more servers per episode)
 *     -> https://player.dramavideo.se/?id=<code>&sv=<sv>  (host is built
 *        from base64 parts in player.js; falls back to the constant)
 *     -> inline script with encData=<b64>, keyHex, ivHex:
 *        AES-256-CBC (PKCS7, key/iv hex-encoded) decrypt -> injected HTML with
 *        const sources = JSON.parse(`[{"file":"<m3u8>","type":"hls",
 *        "label":"..."}]`) and const tracks = JSON.parse(`[ ... ]`).
 *
 *  B. embedload.cfd/watch?v=<n>
 *     -> inner <iframe src="https://zokoanime.video/<token>">
 *     -> window.__P="<b64>": base64-decode, XOR each byte with the
 *        repeating key "otaku-embed-v1" -> JSON {"src":"<m3u8>",
 *        "subtitles":[ ... ]}.
 *
 *  C. vidbasic.top/embed/<shortid>
 *     -> <iframe id="embedvideo" src="/3rdplayer.html?key=<b64>&id=..">
 *     -> 3rdplayer page: <div data-name="crypto" data-value="<b64>">
 *     -> AES-256-CBC (PKCS7) with the FIXED key
 *        "94588293375053432799222445521289" / iv "5259228356829423"
 *        (both ASCII digit strings) -> "<m3u8 url>".
 *
 * No chain currently ships real subtitle files (A: tracks=[], B:
 * subtitles=[], C: none) but all three subtitle slots are parsed when
 * present.
 */
class Dramahood : MainAPI() {

    override var name = "Dramahood"
    override var mainUrl = "https://dramahood.mom/"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.AsianDrama)
    override var lang = "en"
    override val mainPage = mainPageOf(
        "drama" to "Dramas",
        "kshow" to "KShows",
        "latest-drama" to "Latest Dramas",
        "latest-kshow" to "Latest KShows",
    )

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private val HEADERS = mapOf("User-Agent" to UA)

        private const val PLAYER_HOST_FALLBACK = "https://player.dramavideo.se"
        private const val VIBASIC_AES_KEY = "94588293375053432799222445521289"
        private const val VIBASIC_AES_IV = "5259228356829423"
        private const val ZOKO_XOR_KEY = "otaku-embed-v1"

        private val categoryPaths = mapOf(
            "drama" to "category/drama",
            "kshow" to "category/kshow",
            "latest-drama" to "category/latest-asian-drama-releases",
            "latest-kshow" to "category/latest-kshow-releases",
        )

        private val epNumRe = Regex("-episode-(\\d+)")
        // Latest-release category cards point at episode posts; map back to series.
        private val epUrlRe = Regex("^(.+?)-episode-\\d+/?$")
        private val epNameSuffixRe = Regex("(?i)\\s+(?:episode|ep)\\s*\\d+$")
        private val yearRe = Regex("\\((19\\d{2}|20\\d{2})\\)")
        private val m3u8Re = Regex("https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*")

        // Chain A: encData / keyHex / ivHex in the player.dramavideo.se page.
        private val encDataRe =
            Regex("encData\\s*=\\s*\"([^\"]+)\"\\s*,\\s*keyHex\\s*=\\s*\"([0-9a-fA-F]+)\"\\s*,\\s*ivHex\\s*=\\s*\"([0-9a-fA-F]+)\"")
        // Chain A: const sources = JSON.parse(`[...]`) / const tracks = ...
        private val sourcesJsonRe = Regex("const\\s+sources\\s*=\\s*JSON\\.parse\\(`([^`]*)`\\)")
        private val tracksJsonRe = Regex("const\\s+tracks\\s*=\\s*JSON\\.parse\\(`([^`]*)`\\)")
        private val playerJsPartsRe = Regex("parts\\s*=\\s*(\\[[^\\]]+\\])")

        // Chain B: window.__P="<b64>"
        private val zokoBlobRe = Regex("window\\.__P\\s*=\\s*\"([^\"]+)\"")

        // Chain C: data-name="crypto" data-value="<b64>" (or ?key=<b64>)
        private val cryptoValueRe = Regex("data-name=\"crypto\"[^>]*data-value=\"([^\"]+)\"")
        private val keyParamRe = Regex("[?&]key=([^&\"']+)", RegexOption.IGNORE_CASE)
    }

    // ------------------------------------------------------------------
    // Crypto helpers
    // ------------------------------------------------------------------

    private fun aesCbcDecrypt(b64: String, key: ByteArray, iv: ByteArray): String? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv)
            )
            String(cipher.doFinal(java.util.Base64.getDecoder().decode(b64)), Charsets.UTF_8)
                .trim('\u0000')
                .trim()
        } catch (_: Throwable) {
            null
        }
    }

    private fun xorB64(b64: String, key: String): String? {
        return try {
            val raw = java.util.Base64.getDecoder().decode(b64)
            val out = ByteArray(raw.size) { i ->
                (raw[i].toInt() xor key[i % key.length].code).toByte()
            }
            String(out, Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun String?.langCode(): String? {
        val s = this?.lowercase()?.trim() ?: return null
        val names = mapOf(
            "english" to "en", "eng" to "en", "en" to "en",
            "korean" to "ko", "kor" to "ko", "ko" to "ko",
            "japanese" to "ja", "jpn" to "ja", "ja" to "ja",
            "chinese" to "zh", "chi" to "zh", "zho" to "zh", "zh" to "zh",
            "mandarin" to "zh", "cantonese" to "yue", "yue" to "yue",
        )
        if (s.length in 1..3 && s.all { it.isLetter() }) names[s]?.let { return it }
        for (t in s.split(Regex("[^a-z]+"))) {
            if (t.length in 2..3 && names[t] != null) return names[t]
        }
        return null
    }

    // ------------------------------------------------------------------
    // URL helpers
    // ------------------------------------------------------------------

    private fun toAbsoluteUrl(href: String?, base: String = mainUrl): String? {
        if (href.isNullOrBlank()) return null
        return when {
            href.startsWith("http://") || href.startsWith("https://") -> href
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> base.removeSuffix("/") + href
            else -> base.removeSuffix("/") + "/" + href
        }
    }

    /** True for root-level series URLs like https://dramahood.mom/<slug>/ */
    private fun isSeriesUrl(url: String): Boolean {
        if (!url.startsWith(mainUrl)) return false
        val path = url.removePrefix(mainUrl)
        if (path.contains("-episode-")) return false
        if (path.contains("/category/") || path.contains("/page/")) return false
        if (path.contains("wp-content") || path.contains("wp-json")) return false
        return true
    }

    // ------------------------------------------------------------------
    // Card parsing (catalog + search share the markup)
    // ------------------------------------------------------------------

    private fun Document.toCards(): List<SearchResponse> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<SearchResponse>()
        for (li in select("li")) {
            val a = li.selectFirst("a[href]") ?: continue
            val rawUrl = toAbsoluteUrl(a.attr("href")) ?: continue
            // Regular category pages link straight to series pages. The
            // "latest releases" categories list individual episode posts
            // (<slug>-episode-N/), so normalize those to the series page and
            // de-duplicate repeated series (latest episode first).
            val url = epUrlRe.find(rawUrl)?.let { it.groupValues[1] + "/" } ?: rawUrl
            if (!isSeriesUrl(url)) continue
            if (!seen.add(url)) continue

            val img = a.selectFirst("img") ?: li.selectFirst("img") ?: continue
            var nm = img.attr("alt").trim()
            if (nm.isBlank()) {
                val h2 = li.selectFirst("h2 a")?.text()?.trim().orEmpty()
                nm = h2.substringBefore(" (").ifBlank { a.text().trim() }
            }
            nm = epNameSuffixRe.replace(nm, "").trim()
            if (nm.isBlank()) continue

            val poster = toAbsoluteUrl(img.attr("src"))
                ?.takeIf { it.startsWith("http") && !it.contains("/themes/") }

            out += newTvSeriesSearchResponse(nm, url, TvType.AsianDrama) {
                posterUrl = poster
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Main page
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = categoryPaths[request.data] ?: return newHomePageResponse(request, emptyList())
        return try {
            val url = if (page <= 1) "${mainUrl}$path/"
            else "${mainUrl}$path/page/$page/"
            val doc = app.get(url, headers = HEADERS).document
            newHomePageResponse(request, doc.toCards())
        } catch (_: Throwable) {
            newHomePageResponse(request, emptyList())
        }
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        return try {
            val doc = app.get(
                mainUrl, params = mapOf("s" to q), headers = HEADERS
            ).document
            doc.toCards()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Detail / episodes
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = HEADERS).document

        val h1 = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val name = h1.ifBlank {
            url.substringAfterLast("/").substringBefore("/").trim()
        }

        val poster = doc.selectFirst("img.poster")?.attr("src")?.let { toAbsoluteUrl(it) }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { toAbsoluteUrl(it) }

        // Scope to the real info block: the hidden "top views" widget further
        // down the page also emits <p><strong>Status:</strong> Ep N</p> lines
        // that would otherwise clobber these values (last one wins in a map).
        val info = mutableMapOf<String, String>()
        for (p in doc.select("div.movie div.left p")) {
            val strong = p.selectFirst("strong")?.text()?.trim() ?: continue
            val label = strong.removeSuffix(":").lowercase().trim()
            if (label.isBlank()) continue
            val value = p.text().trim().removePrefix(strong).trimStart(':', ' ').trim()
            if (value.isNotEmpty()) info[label] = value
        }
        val year = info["release year"]?.toIntOrNull()
            ?: yearRe.find(h1)?.groupValues?.get(1)?.toIntOrNull()
        val status = info["status"].orEmpty()
        val genre = info["genre"].orEmpty()
        val country = info["country"].orEmpty()
        val plot = doc.select("div.right div.info p")
            .joinToString("\n") { it.text().trim() }
            .ifBlank { doc.select("div.info p").joinToString("\n") { it.text().trim() } }

        val eps = mutableListOf<Episode>()
        val seenEp = LinkedHashSet<String>()
        for (a in doc.select("ul.list-episode a[href]")) {
            val epUrl = toAbsoluteUrl(a.attr("href")) ?: continue
            val ep = epNumRe.find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (!seenEp.add(epUrl)) continue
            eps += newEpisode(epUrl) {
                this.name = name
                season = 1
                episode = ep
            }
        }
        eps.sortBy { it.episode ?: 0 }

        val showStatus = when {
            status.contains("ongoing", true) || status.contains("running", true) ->
                ShowStatus.Ongoing
            status.contains("finish", true) || status.contains("complet", true) ||
                status.contains("ended", true) -> ShowStatus.Completed
            else -> null
        }

        val tags = buildList {
            addAll(genre.split(",").map { it.trim() }.filter { it.isNotBlank() })
            if (country.isNotBlank()) add(country)
        }

        return newTvSeriesLoadResponse(name, url, TvType.AsianDrama, eps) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            if (tags.isNotEmpty()) this.tags = tags
            this.showStatus = showStatus
        }
    }

    // ------------------------------------------------------------------
    // Player chain resolvers
    // ------------------------------------------------------------------

    private class Resolved(
        val name: String,
        val url: String,
        val referer: String,
        val subtitles: List<Pair<String, String>>
    )

    /** Chain A: dramavideo.se/watch?v=N -> player.dramavideo.se/?id=..&sv=.. */
    private suspend fun resolveDramavideo(embedUrl: String, pageUrl: String): List<Resolved> {
        val out = mutableListOf<Resolved>()
        val html = app.get(embedUrl, referer = pageUrl, headers = HEADERS).text
        val doc = org.jsoup.Jsoup.parse(html, "https://dramavideo.se/")

        val servers = doc.select("li.linkserver").mapNotNull { li ->
            val code = li.attr("data-video")
            val sv = li.attr("data-provider")
            if (code.isBlank() || sv.isBlank()) null else code to sv
        }
        if (servers.isEmpty()) return out

        val playerHost = resolvePlayerHost(embedUrl, html)

        for ((code, sv) in servers) {
            val playerUrl = "$playerHost/?id=${java.net.URLEncoder.encode(code, "UTF-8")}&sv=${java.net.URLEncoder.encode(sv, "UTF-8")}"
            val playerHtml = try {
                app.get(playerUrl, referer = embedUrl, headers = HEADERS).text
            } catch (_: Throwable) {
                continue
            }
            val m = encDataRe.find(playerHtml) ?: continue
            val decrypted = aesCbcDecrypt(
                m.groupValues[1],
                hexToBytes(m.groupValues[2]),
                hexToBytes(m.groupValues[3])
            ) ?: continue

            val subtitles = mutableListOf<Pair<String, String>>()
            val tracksM = tracksJsonRe.find(decrypted)
            if (tracksM != null) {
                try {
                    val arr = JSONArray(tracksM.groupValues[1])
                    for (i in 0 until arr.length()) {
                        val t = arr.optJSONObject(i) ?: continue
                        val file = t.optString("file").ifBlank { t.optString("url") }
                            .ifBlank { t.optString("src") }
                        if (!file.startsWith("http")) continue
                        val label = t.optString("label").ifBlank { t.optString("kind") }
                        subtitles += (label.langCode() ?: "en") to file
                    }
                } catch (_: Throwable) {
                    // malformed tracks - keep going without subtitles
                }
            }

            val sourcesM = sourcesJsonRe.find(decrypted) ?: continue
            var added = false
            try {
                val arr = JSONArray(sourcesM.groupValues[1])
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val file = s.optString("file")
                    if (!file.startsWith("http")) continue
                    val label = s.optString("label")
                    val srcName = if (label.isNotBlank()) "Dramavideo $sv (${label})"
                    else "Dramavideo $sv"
                    out += Resolved(srcName, file, playerUrl, subtitles)
                    added = true
                }
            } catch (_: Throwable) {
                // fall through to the raw m3u8 scan
            }
            if (!added) {
                m3u8Re.findAll(decrypted).forEach { m2 ->
                    out += Resolved("Dramavideo $sv", m2.value, playerUrl, subtitles)
                }
            }
        }
        return out
    }

    /** The player iframe host is built from base64 parts in player.js. */
    private suspend fun resolvePlayerHost(embedUrl: String, watchHtml: String): String {
        return try {
            val jsUrl = org.jsoup.Jsoup.parse(watchHtml, embedUrl)
                .select("script[src]")
                .map { it.attr("src") }
                .firstOrNull { it.contains("player.js") }
                ?.let { toAbsoluteUrl(it, "https://dramavideo.se/") }
            if (jsUrl == null) return PLAYER_HOST_FALLBACK
            val js = app.get(jsUrl, referer = embedUrl, headers = HEADERS).text
            val parts = playerJsPartsRe.findAll(js).lastOrNull()?.groupValues?.get(1)
                ?: return PLAYER_HOST_FALLBACK
            val b64 = parts.substringAfter("\"").let { p ->
                Regex("\"([^\"]+)\"").findAll(p).joinToString("") { it.groupValues[1] }
            }
            java.util.Base64.getDecoder().decode(b64).toString(Charsets.UTF_8)
                .trim().trimEnd('/')
                .let { if (it.startsWith("http")) it else PLAYER_HOST_FALLBACK }
        } catch (_: Throwable) {
            PLAYER_HOST_FALLBACK
        }
    }

    /** Chain B: embedload.cfd/watch?v=N -> zokoanime.video/<token> */
    private suspend fun resolveEmbedload(embedUrl: String, pageUrl: String): List<Resolved> {
        val out = mutableListOf<Resolved>()
        val html = app.get(embedUrl, referer = pageUrl, headers = HEADERS).text
        val doc = org.jsoup.Jsoup.parse(html, embedUrl)
        val inner = doc.select("iframe[src]")
            .map { toAbsoluteUrl(it.attr("src"), embedUrl) }
            .firstOrNull { it != null && it != embedUrl && !it.contains("embedload.cfd") }
            ?: return out
        if (!inner!!.startsWith("http")) return out

        val innerHtml = app.get(inner, referer = embedUrl, headers = HEADERS).text
        val blobM = zokoBlobRe.find(innerHtml)
        if (blobM != null) {
            val json = xorB64(blobM.groupValues[1], ZOKO_XOR_KEY)
            if (json != null) {
                try {
                    val obj = JSONObject(json)
                    val src = obj.optString("src").ifBlank { obj.optString("file") }
                    if (src.startsWith("http")) {
                        val subtitles = mutableListOf<Pair<String, String>>()
                        val subs = obj.optJSONArray("subtitles")
                        if (subs != null) {
                            for (i in 0 until subs.length()) {
                                val s = subs.optJSONObject(i) ?: continue
                                val file = s.optString("file").ifBlank { s.optString("url") }
                                    .ifBlank { s.optString("src") }
                                if (!file.startsWith("http")) continue
                                val label = s.optString("label")
                                    .ifBlank { s.optString("lang") }
                                    .ifBlank { s.optString("kind") }
                                subtitles += (label.langCode() ?: "en") to file
                            }
                        }
                        val host = inner.substringAfter("://").substringBefore("/")
                            .ifBlank { "Zoko" }
                        out += Resolved(host.replaceFirstChar { it.uppercase() }, src, inner, subtitles)
                    }
                } catch (_: Throwable) {
                    // fall through to the raw m3u8 scan
                }
            }
        }
        if (out.isEmpty()) {
            m3u8Re.findAll(innerHtml).forEach { m ->
                out += Resolved("Embedload", m.value, inner, emptyList())
            }
        }
        return out
    }

    /** Chain C: vidbasic.top/embed/<id> -> 3rdplayer.html -> AES m3u8 */
    private suspend fun resolveVidbasic(embedUrl: String, pageUrl: String): List<Resolved> {
        val out = mutableListOf<Resolved>()
        val html = app.get(embedUrl, referer = pageUrl, headers = HEADERS).text
        val doc = org.jsoup.Jsoup.parse(html, embedUrl)
        val thirdUrl = doc.selectFirst("iframe#embedvideo")?.attr("src")
            ?: doc.select("iframe[src]")
                .firstOrNull { it.attr("src").contains("3rdplayer") }?.attr("src")
            ?: return out
        val fullThird = toAbsoluteUrl(thirdUrl, embedUrl) ?: return out
        if (!fullThird.startsWith("http")) return out

        val thirdHtml = app.get(fullThird, referer = embedUrl, headers = HEADERS).text
        val blob = cryptoValueRe.find(thirdHtml)?.groupValues?.get(1)
            ?: keyParamRe.find(fullThird)?.groupValues?.get(1)
            ?: return out
        val m3u8 = aesCbcDecrypt(
            java.net.URLDecoder.decode(blob, "UTF-8"),
            VIBASIC_AES_KEY.toByteArray(Charsets.UTF_8),
            VIBASIC_AES_IV.toByteArray(Charsets.UTF_8)
        )?.takeIf { it.startsWith("http") } ?: return out

        out += Resolved("Vidbasic", m3u8, fullThird, emptyList())
        return out
    }

    /** Fallback for unknown embed hosts: scan the page for a bare m3u8. */
    private suspend fun resolveDirect(embedUrl: String, pageUrl: String): List<Resolved> {
        val out = mutableListOf<Resolved>()
        try {
            val h = app.get(embedUrl, referer = pageUrl, headers = HEADERS).text
            m3u8Re.findAll(h).forEach { m ->
                out += Resolved("Direct", m.value, embedUrl, emptyList())
            }
        } catch (_: Throwable) {
            // unreachable or non-http embed - nothing to add
        }
        return out
    }

    // ------------------------------------------------------------------
    // Video + subtitles
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = data
        val doc = app.get(url, headers = HEADERS).document

        val embeds = LinkedHashSet<String>()
        for (iframe in doc.select("iframe[src]")) {
            val s = iframe.attr("src")
            if (s.startsWith("http")) embeds += s
        }
        for (el in doc.select("[data-video]")) {
            val s = el.attr("data-video")
            if (s.startsWith("http")) embeds += s
        }
        if (embeds.isEmpty()) return false

        val resolved = mutableListOf<Resolved>()
        val seen = LinkedHashSet<String>()
        for (embedUrl in embeds) {
            val host = try {
                java.net.URI(embedUrl).host?.lowercase().orEmpty()
            } catch (_: Throwable) {
                ""
            }
            val list: List<Resolved> = try {
                when {
                    host.endsWith("dramavideo.se") -> resolveDramavideo(embedUrl, url)
                    host.endsWith("embedload.cfd") -> resolveEmbedload(embedUrl, url)
                    host.endsWith("vidbasic.top") -> resolveVidbasic(embedUrl, url)
                    else -> resolveDirect(embedUrl, url)
                }
            } catch (_: Throwable) {
                emptyList()
            }
            for (r in list) {
                if (seen.add(r.url)) resolved += r
            }
        }
        if (resolved.isEmpty()) return false

        val seenSubs = LinkedHashSet<String>()
        for (r in resolved) {
            for ((lang, subUrl) in r.subtitles) {
                if (seenSubs.add(subUrl)) {
                    subtitleCallback(newSubtitleFile(lang, subUrl))
                }
            }
        }

        var added = false
        for (r in resolved) {
            callback(
                newExtractorLink(name, r.name, r.url, ExtractorLinkType.M3U8) {
                    this.referer = r.referer
                    headers = mapOf("User-Agent" to UA)
                }
            )
            added = true
        }
        return added
    }
}
