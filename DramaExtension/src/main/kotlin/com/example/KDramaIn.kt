package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * KDrama.in (https://k-drama.in) - K/C/J dramas and movies.
 *
 * The site was **redesigned in 2026-10-20** ("KatDrama", Cineapse theme v2.9).
 * Everything below is verified live against it through FlareSolverr
 * (`cmd=request.get`, `Challenge not detected!`); the old `dramas.php` /
 * `detail.php` / `watch.php` routes the previous version scraped no longer
 * exist - every path now renders at `/explore?...` as the "KatDrama Site".
 *
 * ## Catalog
 *   GET /explore?type=<kdrama|cdrama|jdrama|movies|top_rated>&sort=popular&page=<n>
 *   -> cards: <div class="cineapse-movie-card"
 *          onclick="window.location.href='/detail/<slug>-<id>'">
 *       title : .cineapse-hover-title   (e.g. "Breaking Bad")
 *       poster: first img (image.tmdb.org/t/p/w500/...)
 *       rating: <span> *after* <i class="fas fa-star hover-star"> (e.g. "9.0");
 *               the <span> *before* the star is the year. NB the star's parent
 *               text is the year, NOT the rating - a naive grab gets the year.
 *   Standalone /ranking uses plain <a href="/detail/<slug>-<id>"> links instead
 *   of the onclick cards, so getMainPage parses both shapes.
 *
 * ## Detail
 *   GET /detail/<slug>-<id>   (id = the trailing -<digits> of the href; the slug
 *   is everything before it - verified: "Breaking Bad" -> /detail/breaking-bad-1396,
 *   "Law & Order" -> /detail/law-order-549, the '&' is stripped to nothing).
 *       title     : h1
 *       year      : \((\d{4})\) from <title> / og:title ("... (2008) ...")
 *       poster    : meta[property=og:image]
 *       overview  : meta[name=description]
 *       seasons   : <a href="/detail/<slug>-<id>?season=<n>"> nav links
 *       each season page lists its episodes as /watch/<slug>-<id>-episode=<n>
 *
 * ## Watch / sources
 *   GET /watch/<slug>-<id>-episode=<n>  -> <iframe id="video-player-frame"
 *       src="https://vidsync.pro/embed/{tv|movie}/<id>/<season>/<episode>?...">
 *   season/episode are baked into that URL by the site, so the correct episode
 *   always resolves. The stream itself comes from the vidsync session API,
 *   resolved by the shared VidsyncResolver (live + 12 h cache fallback +
 *   subtitles) - this provider only hands it the embed URL.
 *
 * ## Search
 *   GET /api.php?action=search&q=<query>  -> HTML wrapping JSON in <pre>:
 *       {"results":[{"id":1396,"name":"Breaking Bad","poster_path":"/...",
 *                    "media_type":"tv", ...}]}
 *   (the `q` param on /explore is ignored and returns the catalog - do NOT use
 *   it for search). Build the detail URL from each result:
 *       /detail/<slug(name)>-<id>   slug = lowercased, runs of non-alphanumerics -> '-'
 *
 * ## Cloudflare
 *   k-drama.in serves a managed challenge to every non-browser client (verified
 *   2026-10-03: plain OkHttp/curl -> 403 cf-mitigated on every path, including
 *   /robots.txt); only a real browser clears it. FlareSolverr (headful Chrome)
 *   is the solver, so [usesWebView] is declared and every fetch goes through
 *   [FlareSolverr.fetchCleared]. The vidsync session API is called directly
 *   (not through the solver) and returns valid sources without special headers.
 */
class KDramaIn : MainAPI() {

    override var name = "KDrama.in"
    override var mainUrl = SiteConfig.mirror("kdramain")
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie)
    override var lang = "en"

    /**
     * The challenge needs a real browser (FlareSolverr supplies it); declaring
     * `usesWebView` lets the host disable this provider on a device with no
     * WebView instead of showing a permanently empty tab.
     */
    override val usesWebView = true

    override var mainPage = mainPageOf(
        "kdrama" to "K-Dramas",
        "cdrama" to "C-Dramas",
        "jdrama" to "J-Dramas",
        "movies" to "Movies",
        "top_rated" to "Top Rated",
        "ranking" to "Top 20",
    )

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        /** One shared solver for the whole provider (stateful session). */
        private val solver by lazy {
            FlareSolverr(SiteConfig.defaults().flareSolverrUrl.orEmpty())
        }

        // --- markup selectors (all verified against the live site) ---
        private val cardHrefRe = Regex("window\\.location\\.href='(/detail/[^']+)'")
        private val cardTitleRe = Regex("cineapse-hover-title[^>]*>(.*?)</")
        private val cardRatingRe = Regex("hover-star\"></i>\\s*<span>(.*?)</span>")
        private val detailIdRe = Regex("-(\\d+)$")
        private val seasonNavRe = Regex("/detail/[^/]+\\?season=(\\d+)")
        private val watchRe = Regex("/watch/[^/]+-episode-(\\d+)")
        private val iframeRe = Regex("id=\"video-player-frame\"[^>]+src=\"([^\"]+)\"")
        private val embedIdRe = Regex("embed/(?:tv|movie)/(\\d+)")
        private val embedPathRe = Regex("embed/(?:tv|movie)/\\d+/(\\d+)/(\\d+)")

        /**
         * Slug for a title, matching the site's detail-URL slugs exactly.
         * Verified: "Breaking Bad" -> breaking-bad, "Law & Order" -> law-order
         * (the '&' is stripped, not expanded), "Come Home Love: Lo and Behold"
         * -> come-home-love-lo-and-behold. The search API returns the *decoded*
         * name, so decode &amp; first.
         */
        fun slugify(name: String): String =
            name
                .replace("&amp;", "&").replace("&", " ")
                .replace(Regex("[^a-zA-Z0-9]+"), "-")
                .trim('-').lowercase()

        /** Parse (slug, id) from a /detail/<slug>-<id> href or absolute URL. */
        fun detailIds(href: String): Pair<String, String>? {
            val m = Regex("/detail/([^/]+)").find(href) ?: return null
            val slug = m.groupValues[1].substringAfterLast('/')
            val idm = detailIdRe.find(slug) ?: return null
            return Pair(slug, idm.groupValues[1])
        }

        // ------------------------------------------------------------------
        // Catalog
        // ------------------------------------------------------------------

        override suspend fun getMainPage(
            page: Int,
            request: MainPageRequest,
        ): HomePageResponse {
            val filter = request.data
            val url = when (filter) {
                "ranking" -> "$mainUrl/ranking"
                else -> "$mainUrl/explore?type=$filter&sort=popular&page=${page.coerceAtLeast(1)}"
            }
            val cleared = solver.fetchCleared(url) ?: return newHomePageResponse(request, emptyList())
            val doc = Jsoup.parse(cleared.html)

            val out = ArrayList<SearchResponse>()

            // Primary shape: .cineapse-movie-card divs with onclick hrefs.
            for (card in doc.select("div.cineapse-movie-card")) {
                val href = cardHrefRe.find(card.attr("onclick"))?.groupValues?.get(1)
                    ?: continue
                val title = cardTitleRe.find(card.outerHtml())?.groupValues?.get(1)
                    ?.trim().orEmpty()
                if (title.isBlank()) continue
                val poster = card.selectFirst("img")?.attr("src")
                    ?.takeIf { it.startsWith("http") }
                val rating = cardRatingRe.find(card.outerHtml())?.groupValues?.get(1)
                    ?.toFloatOrNull()
                addCard(title, "$mainUrl$href", poster, rating, out)
            }

            // Fallback shape (used by /ranking): plain <a href="/detail/...">.
            if (out.isEmpty()) {
                for (a in doc.select("a[href^=\"/detail/\"]")) {
                    val href = a.attr("href")
                    if (detailIds(href) == null) continue
                    val title = a.text().trim()
                        .ifBlank { slugify(href.substringAfterLast('/')) }
                    val poster = a.selectFirst("img")?.attr("src")
                        ?.takeIf { it.startsWith("http") }
                    addCard(title, "$mainUrl$href", poster, null, out)
                }
            }

            ExtLog.log("kd", "getMainPage page=$page -> ${out.size} cards from $url")
            return newHomePageResponse(request, out)
        }

        private fun addCard(
            title: String,
            href: String,
            poster: String?,
            rating: Float?,
            out: ArrayList<SearchResponse>,
        ) {
            // A movie detail href is /detail/<slug>-<id> too; type is only known
            // from the watch embed, so default to AsianDrama and let the detail
            // page decide.
            val type = if (slugify(title).contains("movie")) TvType.Movie else TvType.AsianDrama
            val score = rating?.let { Score.from10(it) }
            when (type) {
                TvType.Movie -> newMovieSearchResponse(title, href, type) {
                    posterUrl = poster
                    this.score = score
                }.apply { out.add(this) }
                else -> newTvSeriesSearchResponse(title, href, type) {
                    posterUrl = poster
                    this.score = score
                }.apply { out.add(this) }
            }
        }

        // ------------------------------------------------------------------
        // Search
        // ------------------------------------------------------------------

        override suspend fun search(query: String): List<SearchResponse> {
            val url = "$mainUrl/api.php?action=search&q=${query.trim()}"
            val cleared = solver.fetchCleared(url) ?: return emptyList()
            // The JSON is wrapped in an HTML <pre> by the endpoint.
            val pre = Regex("<pre[^>]*>(.*?)</pre>", RegexOption.DOT_MATCHES_ALL)
                .find(cleared.html)?.groupValues?.get(1) ?: return emptyList()
            val root = try { JSONObject(pre) } catch (_: Throwable) { return emptyList() }
            val arr = root.optJSONArray("results") ?: return emptyList()
            val out = ArrayList<SearchResponse>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optInt("id", 0)
                if (id == 0) continue
                val name = o.optString("name").ifBlank { o.optString("original_name") }
                    .ifBlank { continue }
                val type = if (o.optString("media_type") == "movie") TvType.Movie else TvType.AsianDrama
                val slug = slugify(name)
                val href = "$mainUrl/detail/$slug-$id"
                val poster = o.optString("poster_path").takeIf { it.isNotBlank() }
                    ?.let { if (it.startsWith("/")) "https://image.tmdb.org/t/p/w500$it" else it }
                when (type) {
                    TvType.Movie -> newMovieSearchResponse(name, href, type) {
                        posterUrl = poster
                    }.apply { out.add(this) }
                    else -> newTvSeriesSearchResponse(name, href, type) {
                        posterUrl = poster
                    }.apply { out.add(this) }
                }
            }
            ExtLog.log("kd", "search \"$query\" -> ${out.size} results")
            return out
        }

        // ------------------------------------------------------------------
        // Detail / episodes
        // ------------------------------------------------------------------

        override suspend fun load(url: String): LoadResponse {
            val ids = detailIds(url) ?: throw Exception("Not a k-drama.in detail URL: $url")
            val doc = Jsoup.parse(solver.fetchCleared(url)?.html.orEmpty())
            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: throw Exception("Could not parse title from $url")
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("img[src*=image.tmdb.org]")?.attr("src")
            val overview = doc.selectFirst("meta[name=description]")?.attr("content")
            val year = Regex("\\((19\\d{2}|20\\d{2})\\)")
                .find(doc.selectFirst("title")?.text() ?: title)
                ?.value?.trim('(', ')')?.toIntOrNull()
            val score = Regex("(\\d{1,2}\\.\\d)")
                .find(doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "")
                ?.value?.toFloatOrNull()
                ?.takeIf { it in 0.1f..10f }
                ?.let { Score.from10(it) }

            val episodes = collectEpisodes(slug = ids.first(), id = ids.second(), doc = doc)

            return if (episodes.isEmpty()) {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    posterUrl = poster
                    this.year = year
                    plot = overview
                    this.score = score
                }
            } else {
                newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
                    posterUrl = poster
                    this.year = year
                    plot = overview
                    this.score = score
                }
            }
        }

        /**
         * Collects episodes grouped by season. The detail page's episode list
         * is per-season (verified: ?season=1 -> eps 1-7, ?season=2 -> eps 1-13),
         * so each season in the nav is fetched and its /watch/ links parsed.
         * Falls back to a single episode when no season nav exists (movies).
         */
        private fun collectEpisodes(
            slug: String,
            id: String,
            doc: Document,
        ): List<Episode> {
            val seasons = seasonNavRe.findAll(doc.outerHtml())
                .map { it.groupValues[1].toIntOrNull() }
                .filterNotNull()
                .distinct()
                .sorted()

            val out = ArrayList<Episode>()
            if (seasons.isNotEmpty()) {
                for (s in seasons) {
                    val seasonUrl = "$mainUrl/detail/$slug-$id?season=$s"
                    val seasonDoc = Jsoup.parse(solver.fetchCleared(seasonUrl)?.html.orEmpty())
                    val seen = LinkedHashSet<Int>()
                    for (m in watchRe.findAll(seasonDoc.outerHtml())) {
                        val e = m.groupValues[1].toIntOrNull() ?: continue
                        if (!seen.add(e)) continue
                        out += newEpisode("$mainUrl/watch/$slug-$id-episode-$e") {
                            name = "Episode $e"
                            season = s
                            episode = e
                        }
                    }
                }
            } else {
                // Movie (or a drama page with no season nav): one watch link.
                val wm = watchRe.find(doc.outerHtml())
                if (wm != null) {
                    val e = wm.groupValues[1].toIntOrNull() ?: 1
                    out += newEpisode("$mainUrl/watch/$slug-$id-episode-$e") {
                        name = "Episode $e"
                        season = 0
                        episode = e
                    }
                }
            }
            out.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            return out
        }

        // ------------------------------------------------------------------
        // Sources
        // ------------------------------------------------------------------

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            // data is the /watch/<slug>-<id>-episode=<n> URL.
            val watchUrl = if (data.startsWith("http")) data else "$mainUrl$data"
            val cleared = solver.fetchCleared(watchUrl) ?: return false
            val src = iframeRe.find(cleared.html)?.groupValues?.get(1) ?: return false

            // Parse type/id/season/episode out of the site's embed URL, e.g.
            // https://vidsync.pro/embed/tv/1396/1/5?...
            val idm = embedIdRe.find(src) ?: return false
            val isMovie = src.contains("/embed/movie/")
            val embedUrl = if (isMovie) {
                "${SiteConfig.vidsyncBase()}/embed/movie/${idm.groupValues[1]}/"
            } else {
                val path = embedPathRe.find(src)
                val s = path?.groupValues[2]?.toIntOrNull() ?: 1
                val e = path?.groupValues[3]?.toIntOrNull() ?: 1
                "${SiteConfig.vidsyncBase()}/embed/tv/${idm.groupValues[1]}/?season=$s&episode=$e"
            }

            val res = VidsyncResolver.resolve(ResolveContext(name, watchUrl), embedUrl, "Vidsync")
            return emitResult(res, name, UA, subtitleCallback, callback)
        }
    }
}
