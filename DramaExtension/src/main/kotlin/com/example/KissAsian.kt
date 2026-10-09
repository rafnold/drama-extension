package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

/**
 * KissAsian (https://wwv21.kissasian.com.lv) - Asian drama streaming.
 *
 * Video chain (verified against the live site, 2026-09-27):
 *  1. Episode page: <li class="Standard Server" data-video="https://catalog.dramavibe.cfd/player_embed.php?episode=N">
 *  2. The embed page intentionally does NOT contain the CDN playlist URLs
 *     ("Light embed deterrence" — viewing source only yields subApi + videoUuid).
 *  3. The player resolves the streams at runtime:
 *       GET https://catalog.dramavibe.cfd/player_source.php?episode=N
 *       -> {"ok":true,"src":"<cdn1>.m3u8","list":["<cdn1>.m3u8","<cdn2>.m3u8"]}
 *  4. Subtitles come from the subApi endpoint (requires the embed page as
 *     Referer): [{"lang":"en","format":"srt","url":"<signed .srt url>"}]
 *
 * Search uses the exposed WordPress REST API
 * (/wp-json/wp/v2/series?search=...); the site's ?s= page renders its result
 * list client-side only, so it is useless server-side.
 *
 * The "Latest" tab lists the site's "Drama Movie" page (recently added
 * series AND movies), /recently-added-movie/ (30 cards/page, verified 2026-09-28):
 *   <a href=".../series/<slug>/" title="Title (Year)">
 *     <div class="cover" style="background-image: url('<poster>');">
 *     <p class="title">Title (Year)</p>
 *   </a>
 * Pages past a country catalog either 301 to the homepage or render an empty
 * listing, so toCards() only accepts <h3> titles — the homepage "Drama Movie"
 * widget anchors (title attribute, no <h3>) must never become cards.
 */
class KissAsian : MainAPI() {

    override var name = "KissAsian"
    override var mainUrl = SiteConfig.mirror("kissasian")
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie)
    override var lang = "en"
    override val mainPage = mainPageOf(
        "popular" to "Popular",
        "kr" to "K-Dramas",
        "cn" to "C-Dramas",
        "jp" to "J-Dramas",
        "th" to "Thai Dramas",
        "hk" to "Hong Kong",
        "tw" to "Taiwan",
        "ph" to "Philippines",
        "fantasy" to "Fantasy",
        "historical" to "Historical",
        "romance" to "Romance",
        "action" to "Action",
        "latest" to "Latest",
    )

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private val REST = SiteConfig.mirror("kissasian") + "/wp-json/wp/v2/"

        private val epRe = Regex("episode-(\\d+)")
        private val bgImageRe = Regex("url\\(\\s*['\"]?([^'\")]+)['\"]?\\s*\\)")

        private val countryPaths = mapOf(
            "kr" to "country/south-korea",
            "cn" to "country/china",
            "jp" to "country/japan",
            "th" to "country/thailand",
            "hk" to "country/hong-kong",
            "tw" to "country/taiwan",
            "ph" to "country/philippines",
        )

        // Server-side genre catalogs (/genres/<slug>/, paginated /page/N/).
        // Same <li><a class="img"><h3> grid as the country tabs, so the
        // standard toCards() parser applies. Verified 2026-10-01: xianxia has
        // no page (301 to home); wuxia has an archive but only 2 series
        // (term count 49 is inflated by per-episode posts) — kept out of the
        // tabs until the site populates it.
        private val genrePaths = mapOf(
            "fantasy" to "genres/fantasy",
            "historical" to "genres/historical",
            "romance" to "genres/romance",
            "action" to "genres/action",
        )

    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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

    private fun Document.toCards(): List<SearchResponse> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<SearchResponse>()
        for (a in select("a[href*=/series/]")) {
            val rawHref = a.attr("href")
            val url = toAbsoluteUrl(rawHref) ?: continue
            if (!url.contains("/series/")) continue
            if (url.contains("-episode-")) continue
            if (!seen.add(url)) continue

            // Title must come from the card's <h3>; title-attribute/text
            // fallbacks would pick up the homepage "Drama Movie" widget that
            // appears when a country page over-runs its catalog.
            val nm = a.selectFirst("h3")?.text()?.trim().orEmpty()
            if (nm.isBlank()) continue

            val img = a.selectFirst("img")
                ?: a.parent()?.selectFirst("img")
                ?: a.closest("li")?.selectFirst("img")
            val poster = img
                ?.let { it.attr("data-original").ifBlank { it.attr("src") } }
                ?.takeIf { !it.startsWith("data:") }
                ?.let { toAbsoluteUrl(it) }
                ?.takeIf { it.startsWith("http") }

            out += newTvSeriesSearchResponse(nm, url, TvType.AsianDrama) {
                posterUrl = poster
            }
        }
        return out
    }

    /**
     * Cards for the "recently added" listing (site's "Drama Movie" page,
     * /recently-added-movie/). Markup differs from the country tabs: the
     * title lives in <p class="title"> (or the anchor's title attribute)
     * and the poster in <div class="cover"'s background-image.
     */
    private fun Document.toLatestCards(): List<SearchResponse> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<SearchResponse>()
        for (a in select("a[href*=/series/]")) {
            val rawHref = a.attr("href")
            val url = toAbsoluteUrl(rawHref) ?: continue
            if (!url.contains("/series/") || url.contains("-episode-")) continue
            if (!seen.add(url)) continue

            val nm = a.selectFirst("p.title")?.text()?.trim()
                ?: a.attr("title").trim().orEmpty()
            if (nm.isBlank()) continue

            val poster = a.selectFirst("div.cover")
                ?.attr("style")
                ?.let { bgImageRe.find(it)?.groupValues?.get(1) }
                ?.let { toAbsoluteUrl(it) }
                ?.takeIf { it.startsWith("http") }
                ?: a.selectFirst("img")
                    ?.let { it.attr("data-original").ifBlank { it.attr("src") } }
                    ?.takeIf { !it.startsWith("data:") }
                    ?.let { toAbsoluteUrl(it) }
                    ?.takeIf { it.startsWith("http") }

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
        ExtLog2.log("ka", "getMainPage ENTER")
        val data = request.data
        val latest = data == "latest"
        val path = when {
            data == "popular" -> "most-popular-drama"
            latest -> "recently-added-movie"
            else -> countryPaths[data] ?: genrePaths[data]
        }
        val base = mainUrl.removeSuffix("/")
        val url = if (path == null) base
        else if (page <= 1) "$base/$path/"
        else "$base/$path/page/$page/"
        // UG-5 dead tier: dead listing pages are not re-tried for 1 h.
        if (Cache.isDead(url)) return newHomePageResponse(request, emptyList())
        ExtLog2.log("ka", "getMainPage exit")
        return try {
            val resp = app.get(url, headers = mapOf("User-Agent" to UA))
            if (resp.code == 404 || resp.code == 410) {
                Cache.markDead(url)
        ExtLog2.log("ka", "getMainPage exit")
                return newHomePageResponse(request, emptyList())
            }
            val doc = resp.document
            newHomePageResponse(request, if (latest) doc.toLatestCards() else doc.toCards())
        } catch (_: Throwable) {
            newHomePageResponse(request, emptyList())
        }
    }

    // ------------------------------------------------------------------
    // Search (WordPress REST API, client-side fallback)
    // ------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val results = try {
            searchRest(q)
        } catch (_: Throwable) {
            null
        }
        if (!results.isNullOrEmpty()) return results
        return try {
            // Fallback: scan the site's drama list pages and filter locally.
            val seen = LinkedHashSet<String>()
            val out = mutableListOf<SearchResponse>()
            val needle = q.lowercase()
            val base = mainUrl.removeSuffix("/")
            for (p in 1..6) {
                val url = if (p == 1) "$base/drama-list/" else "$base/drama-list/page/$p/"
                val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
                for (card in doc.toCards()) {
                    if (!seen.add(card.url)) continue
                    if (card.name.lowercase().contains(needle)) out += card
                }
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Server-side title search via the exposed WP REST API.
     * Returns [] when the site has no match for the query.
     */
    private suspend fun searchRest(query: String): List<SearchResponse> {
        val doc = app.get(
            "${REST}series", params = mapOf(
                "search" to query,
                "per_page" to "20",
                "_fields" to "link,title,featured_media",
            ), headers = mapOf("User-Agent" to UA)
        ).text
        val arr = JSONArray(doc)
        if (arr.length() == 0) return emptyList()

        val ids = mutableListOf<Int>()
        val entries = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val link = toAbsoluteUrl(o.optString("link")) ?: continue
            val nm = o.optJSONObject("title")?.optString("rendered")?.trim()
                ?.let { Parser.unescapeEntities(it, false) }?.trim() ?: continue
            if (nm.isBlank()) continue
            entries += o
            ids += o.optInt("featured_media", 0)
        }
        if (entries.isEmpty()) return emptyList()

        // Batch-resolve featured media IDs to poster URLs (single request).
        val mediaIds = ids.filter { it > 0 }.distinct().take(50)
        val posters = if (mediaIds.isEmpty()) emptyMap()
        else try {
            val md = app.get(
                "${REST}media", params = mapOf(
                    "include" to mediaIds.joinToString(","),
                    "_fields" to "id,source_url",
                ), headers = mapOf("User-Agent" to UA)
            ).text
            val mArr = JSONArray(md)
            val map = HashMap<Int, String>()
            for (i in 0 until mArr.length()) {
                val m = mArr.optJSONObject(i) ?: continue
                val src = m.optString("source_url")
                if (src.startsWith("http")) map[m.optInt("id")] = src
            }
            map
        } catch (_: Throwable) {
            emptyMap()
        }

        return entries.map { o ->
            val link = toAbsoluteUrl(o.optString("link")).orEmpty()
            val nm = o.optJSONObject("title")?.optString("rendered")?.trim()
                ?.let { Parser.unescapeEntities(it, false) }?.trim().orEmpty()
            val poster = posters[o.optInt("featured_media", 0)]
            newTvSeriesSearchResponse(nm, link, TvType.AsianDrama) {
                posterUrl = poster
            }
        }
    }

    // ------------------------------------------------------------------
    // Detail / episodes
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        ExtLog2.log("ka", "load ENTER")
        // UG-5: re-opened detail pages are served from the episode cache.
        Cache.episodes.get(url)?.let { return it }
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document

        val h1 = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val name = h1.ifBlank { url.substringAfterLast("/").substringBefore("/").trim() }

        // Detail page poster lives in <div class="details"><div class="img">;
        // the og:image / first upload-img fallbacks do NOT work on this site.
        val poster = doc.selectFirst(".details .img img")?.attr("src")?.let { toAbsoluteUrl(it) }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { toAbsoluteUrl(it) }

        val info = mutableMapOf<String, String>()
        for (p in doc.select(".info p")) {
            val label = p.selectFirst("span")?.text()?.trim()
                ?.removeSuffix(":")?.lowercase()?.trim().orEmpty()
            val value = p.text().trim()
                .removePrefix(label).trimStart(':', ' ').trim()
            if (label.isNotEmpty() && value.isNotEmpty()) info[label] = value
        }

        val year = Regex("\\((19\\d{2}|20\\d{2})\\)").find(h1)
            ?.groupValues?.get(1)?.toIntOrNull()

        val epLinks = doc.select("ul.list-episode-item-2.all-episode a, ul.all-episode a, a[href*=-episode-]")
        val eps = mutableListOf<Episode>()
        val seenEp = LinkedHashSet<String>()
        for (a in epLinks) {
            val raw = a.attr("href")
            val epUrl = toAbsoluteUrl(raw) ?: continue
            val ep = epRe.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (!seenEp.add(epUrl)) continue
            eps += newEpisode(epUrl) {
                this.name = name
                season = 1
                episode = ep
            }
        }
        eps.sortBy { it.episode ?: 0 }

        val applyCommon: LoadResponse.() -> Unit = {
            posterUrl = poster
            this.year = year
            plot = info["synopsis"] ?: info["overview"] ?: info["story"]
            tags = info["genre"]
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                ?.plus(info["country"]?.let { listOf(it) } ?: emptyList())
        }
        val showStatus = when {
            info["status"]?.contains("ongoing", true) == true ||
                info["status"]?.contains("running", true) == true ||
                info["status"]?.contains("continuing", true) == true -> ShowStatus.Ongoing
            info["status"]?.contains("complet", true) == true ||
                info["status"]?.contains("ended", true) == true -> ShowStatus.Completed
            else -> null
        }

        val resp = if (eps.isNotEmpty()) newTvSeriesLoadResponse(name, url, TvType.AsianDrama, eps) {
            applyCommon()
            this.showStatus = showStatus
        } else newMovieLoadResponse(name, url, TvType.AsianDrama, url) {
            applyCommon()
        }
        Cache.episodes.put(url, resp)
        ExtLog2.log("ka", "load exit")
        return resp
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
        ExtLog2.log("ka", "loadLinks ENTER")
        val url = data
        // UG-5: cached embed selection means re-opened episodes make no
        // page fetch; the sources tier then skips the whole player chain.
        var embed = Cache.lists.get(url)?.firstOrNull()
        if (embed == null) {
            val doc = try {
                app.get(url, headers = mapOf("User-Agent" to UA)).document
            } catch (_: Throwable) {
        ExtLog2.log("ka", "loadLinks exit")
                return false
            }
            val raw = doc.selectFirst("li[data-video]")?.attr("data-video")
                ?: doc.selectFirst("iframe[src*='dramavibe']")?.attr("src")
                ?: doc.selectFirst("iframe")?.attr("src")
                ?: return false
            embed = toAbsoluteUrl(raw) ?: return false
            Cache.lists.put(url, listOf(embed))
        }
        if (embed.isBlank()) return false

        // UG-4: the single server resolves under the shared ~20 s budget.
        val ctx = ResolveContext(name, url)
        val results = Resolvers.resolveAll(
            ctx,
            ResolveContext.TOTAL_BUDGET_MS,
            listOf(EmbedTask(embed, "KissAsian")),
        )
        var added = false
        for ((_, res) in results) {
            if (res.ok) added = emitResult(res, name, UA, subtitleCallback, callback) || added
        }
        ExtLog2.log("ka", "loadLinks exit")
        return added
    }

}
