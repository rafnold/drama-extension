package com.example

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * DramaNice (https://dramanice.boo) - Asian drama streaming.
 *
 * Video chain:
 *  1. Episode page embeds `https://dramavideo.se/watch?v=<id>`
 *  2. That watch page lists `li.linkserver` entries (data-provider + data-video code)
 *  3. The player host (last base64 `parts` assignment in player.js, e.g.
 *     https://player.dramavideo.se) serves `/?id=<code>&sv=<provider>` as an
 *     AES-256-CBC encrypted page (encData / keyHex / ivHex)
 *  4. The decrypted page contains `sources = JSON.parse([{file, type, label}])`
 *     with the final m3u8/mp4 URL.
 */
class DramaNice : MainAPI() {

    override var name = "DramaNice"
    override var mainUrl = "https://dramanice.boo/"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.AsianDrama)
    override var lang = "en"
    override val mainPage = mainPageOf(
        "popular" to "Popular",
        "all" to "All Dramas",
    )

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val PLAYER_FALLBACK = "https://player.dramavideo.se"
        private val episodeSlugRe = Regex("/([a-z0-9]+(?:-[a-z0-9]+)*)-episode-(\\d+)/?$")
        private val fileRe = Regex("\"file\"\\s*:\\s*\"(https?://[^\"]+)\"")
        private val typeRe = Regex("\"type\"\\s*:\\s*\"([^\"]+)\"")
        private var playerHostCache: String? = null
        private var sitemapCache: List<String>? = null
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun Document.toDramaCards(): List<SearchResponse> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<SearchResponse>()
        for (a in select("a[href*=/drama/]")) {
            val url = a.attr("abs:href")
            if (!seen.add(url)) continue
            val nm = a.attr("title").trim().ifBlank { a.text().trim() }
            if (nm.isBlank()) continue
            val img = a.selectFirst("img")
                ?: a.parent()?.selectFirst("img")
                ?: a.closest("li")?.selectFirst("img")
            val poster = img
                ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                ?.takeIf { it.startsWith("http") }
            out += newTvSeriesSearchResponse(nm, url, TvType.AsianDrama) {
                posterUrl = poster
            }
        }
        return out
    }

    /** Fetches the WordPress post sitemaps once and caches all post URLs. */
    private suspend fun sitemapUrls(): List<String> {
        sitemapCache?.let { return it }
        val urls = mutableListOf<String>()
        for (file in listOf("post-sitemap.xml", "post-sitemap2.xml", "post-sitemap3.xml")) {
            try {
                val text = app.get("$mainUrl$file").text
                for (m in Regex("<loc>([^<]+)</loc>").findAll(text)) {
                    urls.add(m.groupValues[1].trim())
                }
            } catch (_: Throwable) {
            }
        }
        sitemapCache = urls
        return urls
    }

    /**
     * Finds every episode page for a drama in the sitemap. Episode slugs may
     * drop the trailing duplicate counter or the release year, so several
     * slug candidates are tried.
     */
    private fun episodesFromSitemap(slug: String, all: List<String>): List<Pair<Int, String>> {
        val candidates = mutableSetOf(slug)
        slug.replace(Regex("-\\d{1,2}$"), "").let { if (it != slug) candidates.add(it) }
        candidates.forEach { c ->
            c.replace(Regex("-\\d{4}$"), "").let { if (it != c) candidates.add(it) }
        }
        val out = mutableListOf<Pair<Int, String>>()
        for (url in all) {
            val m = episodeSlugRe.find(url) ?: continue
            // Compare by the last path segment only: sitemap URLs are absolute
            // and the site's slug prefix may include an extra path element
            // (e.g. /drama/<slug>/) or a year/counter suffix.
            val prefix = url.substringBeforeLast("-episode-").removeSuffix("/")
            val prefixPath = prefix.substringAfterLast('/')
            if (prefixPath in candidates || prefixPath.startsWith(slug + "-")) {
                out.add(m.groupValues[2].toInt() to url)
            }
        }
        return out.distinctBy { it.first }.sortedBy { it.first }
    }

    /** Resolves the active player host from the base64 `parts` array in player.js. */
    private suspend fun playerHost(): String {
        playerHostCache?.let { return it }
        var host = PLAYER_FALLBACK
        try {
            val js = app.get("https://dramavideo.se/player.js", referer = "https://dramavideo.se/").text
            val last = Regex("parts\\s*=\\s*\\[([^\\]]+)\\]").findAll(js).lastOrNull()
            if (last != null) {
                val parts = last.groupValues[1].split(',')
                    .map { it.trim().trim('"', '\'') }
                    .filter { it.isNotEmpty() }
                    .map {
                        try {
                            Base64.decode(it, Base64.DEFAULT).toString(Charsets.UTF_8)
                        } catch (_: Throwable) {
                            ""
                        }
                    }
                    .joinToString("")
                if (parts.startsWith("http")) host = parts
            }
        } catch (_: Throwable) {
        }
        playerHostCache = host
        return host
    }

    private fun aesDecrypt(encData: String, keyHex: String, ivHex: String): String? {
        return try {
            val key = SecretKeySpec(keyHex.hexToBytes(), "AES")
            val iv = IvParameterSpec(ivHex.hexToBytes())
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            cipher.doFinal(Base64.decode(encData, Base64.DEFAULT)).toString(Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    private fun String.hexToBytes(): ByteArray {
        val clean = filter { it.isDigit() || it in "abcdefABCDEF" }
        val out = ByteArray(clean.length / 2)
        for (i in 0 until out.size) {
            out[i] = ((clean[i * 2].digitToInt(16) shl 4) + clean[i * 2 + 1].digitToInt(16)).toByte()
        }
        return out
    }

    /** Extracts media sources from the decrypted player page. */
    private fun extractSources(plain: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (obj in Regex("\\{[^{}]*\"file\"[^{}]*\\}").findAll(plain)) {
            val file = fileRe.find(obj.value)?.groupValues?.get(1) ?: continue
            val type = typeRe.find(obj.value)?.groupValues?.get(1) ?: "hls"
            out.add(file to type)
        }
        return out
    }

    // ------------------------------------------------------------------
    // MainAPI
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val doc = if (request.name == "all") {
                app.get("$mainUrl/list-all-drama/").document
            } else {
                app.get(mainUrl).document
            }
            newHomePageResponse(request, doc.toDramaCards())
        } catch (_: Throwable) {
            newHomePageResponse(request, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val doc = app.get(mainUrl, params = mapOf("s" to query)).document
            doc.toDramaCards()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val nm = doc.selectFirst("h1")?.text()?.trim()
            ?: throw Exception("Could not parse title from $url")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img[src*=/wp-content/uploads/]")?.attr("src")
        val year = Regex("\\((19\\d{2}|20\\d{2})\\)").find(nm)?.value
            ?.trim('(', ')')?.toIntOrNull()

        val info = HashMap<String, String>()
        for (p in doc.select("p")) {
            val t = p.text().trim()
            val sep = t.indexOf(':')
            if (sep > 0 && sep < 20) {
                val label = t.substring(0, sep).trim().lowercase()
                val value = t.substring(sep + 1).trim()
                if (value.isNotEmpty()) info[label] = value
            }
        }
        val genres = info["genre"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val description = doc.selectFirst("div.info_des")?.text()?.trim()

        // Episodes shown on the page (most recent ones).
        val slug = url.trimEnd('/').substringAfterLast('/')
        val seen = LinkedHashSet<Int>()
        val episodes = mutableListOf<Episode>()
        for (a in doc.select("ul.list_episode a[href*=-episode-]")) {
            val n = Regex("episode-(\\d+)").find(a.attr("href"))?.groupValues?.get(1)
                ?.toIntOrNull() ?: continue
            if (!seen.add(n)) continue
            episodes += newEpisode(a.attr("abs:href")) {
                name = "Episode $n"
                season = 1
                episode = n
            }
        }
        // Merge the complete list from the sitemap (cached).
        val fromSitemap = episodesFromSitemap(slug, sitemapUrls())
        for ((n, eUrl) in fromSitemap) {
            if (!seen.add(n)) continue
            episodes += newEpisode(eUrl) {
                name = "Episode $n"
                season = 1
                episode = n
            }
        }
        episodes.sortBy { it.episode ?: Int.MAX_VALUE }

        return newTvSeriesLoadResponse(nm, url, TvType.AsianDrama, episodes) {
            posterUrl = poster
            this.year = year
            plot = description
            tags = genres?.plus(info["country"]?.let { listOf(it) } ?: emptyList())
            showStatus = when {
                info["status"]?.contains("ongoing", true) == true -> ShowStatus.Ongoing
                info["status"]?.contains("ended", true) == true -> ShowStatus.Completed
                else -> null
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            val doc = app.get(data).document
            val iframeSrc = doc.selectFirst("iframe")
                ?.let { it.attr("src").ifBlank { it.attr("data-src") } }
                ?.takeIf { it.contains("dramavideo.se") }
                ?: return false
            val watchUrl = fixUrl(iframeSrc)

            val wdoc = app.get(watchUrl, referer = data).document
            val servers = wdoc.select("li.linkserver")
            if (servers.isEmpty()) return false

            val host = playerHost()
            var added = false
            for (li in servers) {
                val code = li.attr("data-video")
                val provider = li.attr("data-provider").ifBlank { "server" }
                if (code.isBlank()) continue
                val playerUrl = "$host/?id=$code&sv=$provider"
                val plain = try {
                    val phtml = app.get(playerUrl, referer = watchUrl).document.outerHtml()
                    val enc = Regex("encData\\s*=\\s*\"([^\"]+)\"").find(phtml)
                        ?: Regex("encData=\"([^\"]+)\"").find(phtml)
                        ?: continue
                    val key = Regex("keyHex\\s*=\\s*\"([^\"]+)\"").find(phtml)
                        ?: Regex("keyHex=\"([^\"]+)\"").find(phtml)
                        ?: continue
                    val iv = Regex("ivHex\\s*=\\s*\"([^\"]+)\"").find(phtml)
                        ?: Regex("ivHex=\"([^\"]+)\"").find(phtml)
                        ?: continue
                    aesDecrypt(enc.groupValues[1], key.groupValues[1], iv.groupValues[1])
                        ?: continue
                } catch (_: Throwable) {
                    continue
                }
                for ((file, type) in extractSources(plain)) {
                    val isHls = type == "hls" || file.contains(".m3u8")
                    callback(
                        newExtractorLink(
                            name,
                            "DramaNice $provider",
                            file,
                            if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                        ) {
                            referer = watchUrl
                            quality = 0
                            headers = mapOf("User-Agent" to UA)
                        }
                    )
                    added = true
                }
            }
            added
        } catch (_: Throwable) {
            false
        }
    }
}
