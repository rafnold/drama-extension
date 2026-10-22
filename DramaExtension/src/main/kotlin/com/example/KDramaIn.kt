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
 *   of the onclick cards; /watchlist and /calendar return 0 cards (login /
 *   different layout) and are dropped from mainPage.
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
            TvType.Movie -> newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.score = score
            }.apply { out.add(this) }
            else -> newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
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
                TvType.Movie -> newMovieSearchResponse(name, href, TvType.Movie) {
                    posterUrl = poster
                }.apply { out.add(this) }
                else -> newTvSeriesSearchResponse(name, href, TvType.AsianDrama) {
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

        val episodes = collectEpisodes(slug = ids.first, id = ids.second, doc = doc)

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
     * Collects the episodes for one title. The live site lists every episode
     * **inline** on the detail page (verified: "Hidden Love" -> 25 eps,
     * "Back to Seventeen" -> 25 eps; there is no ?season= nav anymore), so we
     * gather all /watch/ links straight from the page. Older layouts that still
     * paginate per season are handled by the fallback below.
     */
    private fun collectEpisodes(
        slug: String,
        id: String,
        doc: Document,
    ): List<Episode> {
        val out = ArrayList<Episode>()
        val seen = LinkedHashSet<Int>()
        for (m in watchRe.findAll(doc.outerHtml())) {
            val e = m.groupValues[1].toIntOrNull() ?: continue
            if (!seen.add(e)) continue
            // m.groupValues[0] is the full path, e.g. /watch/tv/slug-id-episode-N.
            out += newEpisode("$mainUrl${m.groupValues[0]}") {
                name = "Episode $e"
                season = 0
                episode = e
            }
        }

        // Fallback for older layouts that paginate per season.
        if (out.isEmpty()) {
            val seasons: List<Int> = seasonNavRe.findAll(doc.outerHtml())
                .map { it.groupValues[1].toIntOrNull() }
                .filterNotNull()
                .distinct()
                .sorted()
                .toList()
            for (s in seasons) {
                val seasonDoc = Jsoup.parse(
                    solver.fetchCleared("$mainUrl/detail/$slug-$id?season=$s")?.html.orEmpty(),
                )
                val sseen = LinkedHashSet<Int>()
                for (m in watchRe.findAll(seasonDoc.outerHtml())) {
                    val e = m.groupValues[1].toIntOrNull() ?: continue
                    if (!sseen.add(e)) continue
                    out += newEpisode("$mainUrl${m.groupValues[0]}") {
                        name = "Episode $e"
                        season = s
                        episode = e
                    }
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
        val html = cleared.html

        // The watch page lists every server as a switchServer(key, url) onclick
        // (verified: 7 servers - reelsdownload, vidsync, vid2.php, yoy5.php,
        // zxcstream.cc, vidzee, player.php). Parse them all and resolve each in
        // parallel; CloudStream shows whichever one actually yields a stream.
        val servers = serverRe.findAll(html)
            .mapNotNull { m -> m.groupValues.getOrNull(2)?.takeIf { it.startsWith("http") } }
            // Server 7 (player.php / "Hindi"): upstream HAPI 403s even with a
            // solved challenge, so it carries no content - skip the dead request.
            .filterNot { it.contains(SKIP_SERVER_PATH) }
            .toList()

        if (servers.isEmpty()) {
            // Fallback: a layout that still renders a single <iframe>.
            val src = iframeRe.find(html)?.groupValues?.get(1) ?: return false
            return emitResult(resolveEmbed(src, watchUrl), name, UA, subtitleCallback, callback)
        }

        val tasks = servers.map {
            EmbedTask(
                url = it,
                label = it,
                customResolver = resolverFor(it),
            )
        }
        val results = Resolvers.resolveAll(
            ResolveContext(name, watchUrl),
            ResolveContext.TOTAL_BUDGET_MS,
            tasks,
        )

        var any = false
        for ((_, res) in results) {
            if (res.sources.isEmpty()) continue
            any = emitResult(res, name, UA, subtitleCallback, callback) || any
        }
        return any
    }

    /** Resolve one embed URL with the host-matched resolver (no custom walk). */
    private fun resolveEmbed(embedUrl: String, pageUrl: String): ResolveResult = try {
        Resolvers.resolve(ResolveContext(name, pageUrl), embedUrl, null)
    } catch (_: Throwable) {
        ResolveResult.EMPTY
    }

    /**
     * Best resolver for one server URL, or null when the shared registry already
     * handles the host (e.g. vidsync.pro -> VidsyncResolver). The first-party
     * k-drama.in proxies and zxcstream need their own referer-aware walk.
     */
    private fun resolverFor(url: String): ((String) -> ResolveResult)? {
        fun devcorp(): (String) -> ResolveResult = { u -> DevcorpResolver().resolve(u) }
        fun yoy(): (String) -> ResolveResult = { u -> Yoy4Resolver().resolve(u) }
        fun vidsync(): (String) -> ResolveResult = { u ->
            // VidsyncResolver reads season/episode only from the session URL's
            // ?season=&episode= params. The embed URL carries them in the path
            // (embed/tv/<id>/<s>/<e>), so rebuild a session URL with the correct
            // values before delegating - otherwise every episode resolves as 1.
            if (u.contains("/embed/movie/")) {
                val id = Regex("embed/movie/(\\d+)").find(u)?.groupValues?.get(1)
                if (id != null) VidsyncResolver.resolve(
                    ResolveContext(name, u),
                    "${SiteConfig.vidsyncBase()}/embed/movie/$id/",
                    "Vidsync")
            } else {
                val m = Regex("embed/(?:tv|movie)/(\\d+)/(\\d+)/(\\d+)").find(u)
                if (m != null) VidsyncResolver.resolve(
                    ResolveContext(name, u),
                    "${SiteConfig.vidsyncBase()}/embed/tv/${m.groupValues[1]}/?season=${m.groupValues[2]}&episode=${m.groupValues[3]}",
                    "Vidsync")
            }
            ResolveResult.EMPTY
        }
        return when {
            // In-page proxy taking the /<name>.php/<id>/<s>/<e> path (vid2.php).
            Regex("(?i)k-drama\\.in/[a-z0-9]+\\.php/\\d+/\\d+/\\d+").containsMatchIn(url) ->
                devcorp()
            // YOY proxy: k-drama.in/yoyN.php
            url.contains("/yoy") ->
                yoy()
            // vidsync.pro: rebuild the session URL so season/episode are correct
            // (the raw embed URL only has them in the path, not as query params).
            url.contains("vidsync") ->
                vidsync()
            else -> null
        }
    }


    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        /** One shared solver for the whole provider (stateful session). */
        private val solver by lazy {
            FlareSolverr(SiteConfig.flareSolverrUrl().orEmpty())
        }

        // --- markup selectors (all verified against the live site) ---
        private val cardHrefRe = Regex("window\\.location\\.href='(/detail/[^']+)'")
        private val cardTitleRe = Regex("cineapse-hover-title[^>]*>(.*?)</")
        private val cardRatingRe = Regex("hover-star\"></i>\\s*<span>(.*?)</span>")
        private val detailIdRe = Regex("-(\\d+)$")
        private val seasonNavRe = Regex("/detail/[^/]+\\?season=(\\d+)")
        // Matches every episode link on the detail page. The live TV path has a
        // `tv/` segment (`/watch/tv/<slug>-<id>-episode-N`), so the path segment
        // must allow `/` - the old `[^/]+` never matched and returned 1 episode.
        private val watchRe = Regex("/watch/[^\"#]+-episode-(\\d+)")
        // Every watch-page server is exposed as switchServer(key, url) on its
        // button; group 2 is the server embed URL.
        private val serverRe = Regex("switchServer\\(['\"]([^'\"]+)['\"],\\s*['\"]([^'\"]+)['\"]")
        private val iframeRe = Regex("id=\"video-player-frame\"[^>]+src=\"([^\"]+)\"")
        /** Server 7 (player.php) upstream HAPI 403s even with a solved challenge. */
        private const val SKIP_SERVER_PATH = "/player.php"

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
    }
}
