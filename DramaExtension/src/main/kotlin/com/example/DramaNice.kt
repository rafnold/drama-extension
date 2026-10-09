package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document

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
    override var mainUrl = SiteConfig.mirror("dramanice")
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.AsianDrama)
    override var lang = "en"
    override val mainPage = mainPageOf(
        "popular" to "Popular",
        "all" to "All Dramas",
        "kdrama" to "K-Dramas",
        "cdrama" to "C-Dramas",
        "jdrama" to "J-Dramas",
        "thai" to "Thai",
    )

    companion object {
        // The A-Z /list-all-drama/ index renders the whole catalog (159
        // titles, verified 2026-10-01) with per-item country-XX classes from
        // the sidebar filter (labels: 19=Korean, 8=South Korea, 17=Chinese,
        // 48=China, 36=Japanese, 51=Japan, 25=Thailand). Both spelling
        // variants are merged per tab. Text-only cards (no posters on this
        // page) — the image-card listing has no country classes.
        private val countryClasses = mapOf(
            "kdrama" to setOf("country-19", "country-8"),
            "cdrama" to setOf("country-17", "country-48"),
            "jdrama" to setOf("country-36", "country-51"),
            "thai" to setOf("country-25"),
        )

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private val episodeSlugRe = Regex("/([a-z0-9]+(?:-[a-z0-9]+)*)-episode-(\\d+)/?")
        private var sitemapCache: List<String>? = null
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Absolute-URLs a link against [mainUrl]. The app's HTML documents are
     * parsed without a base URI, so jsoup's `abs:href` resolves relative
     * links to empty strings; build absolute URLs manually instead.
     */
    private fun toAbsoluteUrl(href: String?): String? {
        if (href.isNullOrBlank()) return null
        return when {
            href.startsWith("http://") || href.startsWith("https://") -> href
            href.startsWith("//") -> "https:$href"
            else -> {
                val base = mainUrl.removeSuffix("/")
                if (href.startsWith("/")) "$base$href" else "$base/$href"
            }
        }
    }

    private fun Document.toDramaCards(): List<SearchResponse> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<SearchResponse>()
        for (a in select("a[href*=/drama/]")) {
            val url = toAbsoluteUrl(a.attr("href")) ?: continue
            if (!seen.add(url)) continue
            val nm = a.attr("title").trim().ifBlank { a.text().trim() }
            if (nm.isBlank()) continue
            val img = a.selectFirst("img")
                ?: a.parent()?.selectFirst("img")
                ?: a.closest("li")?.selectFirst("img")
            val poster = img
                ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                ?.takeIf { !it.startsWith("data:") }
                ?.let { toAbsoluteUrl(it) }
                ?.takeIf { it.startsWith("http") }
            out += newTvSeriesSearchResponse(nm, url, TvType.AsianDrama) {
                posterUrl = poster
            }
        }
        return out
    }

    /** Text-list cards for the A-Z index filtered by country class. */
    private fun Document.toCountryCards(classes: Set<String>): List<SearchResponse> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<SearchResponse>()
        for (li in select("li")) {
            val liClasses = li.classNames()
            if (classes.none { it in liClasses }) continue
            val a = li.selectFirst("a[href*=/drama/]") ?: continue
            val url = toAbsoluteUrl(a.attr("href")) ?: continue
            if (!seen.add(url)) continue
            val nm = a.attr("title").trim().ifBlank { a.text().trim() }
            if (nm.isBlank()) continue
            out += newTvSeriesSearchResponse(nm, url, TvType.AsianDrama) {
                posterUrl = null
            }
        }
        return out
    }

    /** Fetches the WordPress post sitemaps once and caches all post URLs. */
    private suspend fun sitemapUrls(): List<String> {
        sitemapCache?.let { return it }
        val urls = mutableListOf<String>()
        val base = mainUrl.removeSuffix("/")
        for (file in listOf("post-sitemap.xml", "post-sitemap2.xml", "post-sitemap3.xml")) {
            val url = "$base/$file"
            // UG-5: conditional GET; a 304 reuses the previously stored body.
            val text = try {
                Net.getFresh(url) ?: Net.stored(url)
            } catch (_: Throwable) {
                Net.stored(url)
            }
            if (text != null) {
                for (m in Regex("<loc>([^<]+)</loc>").findAll(text)) {
                    urls.add(m.groupValues[1].trim())
                }
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

    // ------------------------------------------------------------------
    // MainAPI
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ExtLog2.log("dn", "getMainPage ENTER")
        ExtLog2.log("dn", "getMainPage exit")
        return try {
            // Country tabs filter the single-page A-Z /list-all-drama/
            // index (159 titles, per-item country-XX classes) — no extra
            // requests for pagination. "All Dramas" uses the site's
            // paginated image-card listing.
            val base = mainUrl.removeSuffix("/")
            // UG-5 dead tier: dead listing pages are not re-tried for 1 h.
            val listUrl = when {
                request.data in countryClasses -> "$base/list-all-drama/"
                request.data == "all" && page > 1 -> "$base/most-popular-drama/page/$page/"
                request.data == "all" -> "$base/most-popular-drama/"
                else -> mainUrl
            }
            if (Cache.isDead(listUrl)) return newHomePageResponse(request, emptyList())
            val resp = app.get(listUrl)
            if (resp.code == 404 || resp.code == 410) Cache.markDead(listUrl)
            val doc = resp.document
            val cards = when {
                // Country tabs: single-page A-Z index filtered by the
                // per-item country-XX classes (no pagination requests).
                request.data in countryClasses ->
                    doc.toCountryCards(countryClasses.getValue(request.data))
                // "All Dramas": site's paginated image-card listing.
                request.data == "all" -> doc.toDramaCards()
                else -> doc.toDramaCards()
            }
            newHomePageResponse(request, cards)
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
        ExtLog2.log("dn", "load ENTER")
        // UG-5: re-opened detail pages are served from the episode cache.
        Cache.episodes.get(url)?.let { return it }
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
            val eUrl = toAbsoluteUrl(a.attr("href")) ?: continue
            episodes += newEpisode(eUrl) {
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

        val resp = newTvSeriesLoadResponse(nm, url, TvType.AsianDrama, episodes) {
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
        Cache.episodes.put(url, resp)
        ExtLog2.log("dn", "load exit")
        return resp
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        ExtLog2.log("dn", "loadLinks ENTER")
        ExtLog2.log("dn", "loadLinks exit")
        return try {
            // UG-5: cached embed selection means re-opened episodes make no
            // page fetch; the sources tier then skips the whole player chain.
            var embed = Cache.lists.get(data)?.firstOrNull()
            if (embed == null) {
                val doc = app.get(data).document
                val iframeSrc = doc.selectFirst("iframe")
                    ?.let { it.attr("src").ifBlank { it.attr("data-src") } }
                    ?.takeIf { it.contains("dramavideo") }
                    ?: return false
                embed = toAbsoluteUrl(iframeSrc) ?: return false
                Cache.lists.put(data, listOf(embed))
            }
            if (embed.isBlank()) return false

            // UG-4: the single dramavideo embed (which itself fans out over
            // all li.linkserver servers) resolves under the shared ~20 s
            // budget.
            val ctx = ResolveContext(name, data)
            val results = Resolvers.resolveAll(
                ctx,
                ResolveContext.TOTAL_BUDGET_MS,
                listOf(EmbedTask(embed, "DramaNice")),
            )
            var added = false
            for ((_, res) in results) {
                if (res.ok) added = emitResult(res, name, UA, subtitleCallback, callback) || added
            }
            added
        } catch (_: Throwable) {
            false
        }
    }
}
