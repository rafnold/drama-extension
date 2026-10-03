package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Document


/**
 * KDrama.in (https://k-drama.in) - K/C/J dramas and movies.
 *
 * Content is served through TMDB ids. The watch page embeds
 * `https://vidsync.pro/embed/{tv|movie}/<tmdbId>/...` and the final streams
 * come from the JSON-lines API
 * `https://vidsync.pro/api/extraction/session?type={tv|movie}&id=<tmdbId>&season=<s>&episode=<e>`
 * which lists sources per provider (m3u8 via relay.vidsync.pro or direct
 * CDN mp4/hls). The relay URLs are self-contained (they rewrite playlist
 * segments), so they are preferred for playback.
 *
 * The vidsync API is intermittent (timeouts / rate limits), so the last good
 * curation per (type, id, season, episode) is cached in memory (12 h TTL,
 * 256 entries) and served when a live call fails or returns zero sources.
 */
class KDramaIn : MainAPI() {

    override var name = "KDrama.in"
    override var mainUrl = SiteConfig.mirror("kdramain")
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie)
    override var lang = "en"

    /**
     * k-drama.in sits behind a Cloudflare managed challenge that rejects
     * every non-browser client (verified 2026-10-03: plain OkHttp/NiceHttp
     * and curl get `403 cf-mitigated: challenge` on every path, including
     * /robots.txt, while a real browser on the same IP gets the page).
     *
     * `CloudflareKiller` solves it the supported way: on a challenge it
     * loads the URL in a hidden Android WebView so Cloudflare's JS runs
     * natively, then replays the resulting `cf_clearance` cookie on later
     * OkHttp requests together with the WebView's own user agent. The
     * device's real browser therefore supplies both the matching UA and the
     * cookie, from the device's own IP - which is exactly the binding
     * Cloudflare enforces, so no UA spoofing or shipped token is involved.
     *
     * Declaring `usesWebView` lets the host disable this provider on a
     * device with no WebView instead of showing a permanently empty tab.
     */
    override val usesWebView = true
    override val mainPage = mainPageOf(
        "all" to "All",
        "kdrama" to "K-Dramas",
        "cdrama" to "C-Dramas",
        "jdrama" to "J-Dramas",
        "movies" to "Movies",
        "top_rated" to "Top Rated",
        "ranking" to "Ranking",
        "watchlist" to "Watchlist",
    )

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private val tvEpRe = Regex("id=(\\d+)&(amp;)?season=(\\d+)&(amp;)?episode=(\\d+)")
        private val movieRe = Regex("id=(\\d+)&(amp;)?type=movie")
        private val idRe = Regex("id=(\\d+)")

        // Catalog card hrefs carry the TMDB id (verified: id=2734 ->
        // "Law & Order: SVU", the detail page embeds 'tmdbId: 2734').
        private val cardTmdbRe = Regex("id=(\\d+)&(amp;)?type=(tv|movie)")

        /** TMDB API v4 token, read from the environment so the expiring
         *  JWT is not baked into the shipped binary. When unset, catalog
         *  cards simply don't get the episode-count suffix. */
        private val TMDB_KEY: String? = System.getenv("TMDB_TOKEN")?.takeIf { it.isNotBlank() }
        private const val TMDB_API = "https://api.tmdb.org/3"
        private const val TMDB_EP_TTL_MS = 7L * 24 * 3600 * 1000
        private const val TMDB_FAIL_TTL_MS = 3600_000L
        private const val TMDB_EP_CACHE_MAX = 1024
        private val tmdbEpCache = HashMap<String, Pair<Int, Long>>()
        private val tmdbLock = Any()

        /**
         * One shared [FlareSolverrInterceptor] for the whole provider.
         *
         * FlareSolverr (a real headful Chrome) clears the Cloudflare challenge
         * that CloudStream's own hidden-WebView solver could not: on device
         * that solver timed out at both its 60 s default (v16/v17) and the
         * 180 s budget v18 gave `WebViewResolver`. Verified live against
         * FlareSolverr 3.5.2: challenge solved, 200, 20 cards.
         *
         * Generic by design - any Cloudflare-blocked provider can reuse this
         * interceptor. Endpoint comes from `SiteConfig.flareSolverrUrl()`
         * (remote config.json > default > $FLARESOLVERR_URL).
         *
         * Created lazily so a blank/misconfigured endpoint costs nothing.
         */
        @Volatile
        private var cfKillerSingleton: FlareSolverrInterceptor? = null

        @Synchronized
        private fun cfKillerSingleton(): FlareSolverrInterceptor =
            cfKillerSingleton ?: FlareSolverrInterceptor()
                .also { cfKillerSingleton = it }

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

    /** TMDB ids of the TV cards on this page (for episode enrichment). */
    private fun Document.toCardTmdbIds(): List<String> {
        val out = mutableListOf<String>()
        for (a in select("a[href*=detail.php]")) {
            val m = cardTmdbRe.find(a.attr("href")) ?: continue
            if (m.groupValues[3] == "tv") out.add(m.groupValues[1])
        }
        return out
    }

    /**
     * Builds catalog cards. [episodesByTmdbId] optionally appends
     * " (N EP)" to TV card names (episode totals come from TMDB; the
     * site's cards show a star rating + country flag but no counts).
     */
    private fun Document.toCards(episodesByTmdbId: Map<String, Int> = emptyMap()): List<SearchResponse> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<SearchResponse>()
        for (a in select("a[href*=detail.php]")) {
            val url = toAbsoluteUrl(a.attr("href")) ?: continue
            if (!seen.add(url)) continue
            val nm0 = a.selectFirst("h3")?.text()?.trim() ?: continue
            if (nm0.isBlank()) continue
            val isMovie = url.contains("type=movie")
            val poster = a.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") }

            // Card rating badge: <i class="fas fa-star"></i> 5.4 in the
            // top-left corner div -> app rating badge ("Show rating" setting).
            // One decimal on the site ("5.4" / 10) -> [0,100]:
            // Score.from(54, 100) renders as "5.4" in the app.
            val score = a.selectFirst("i.fa-star")?.parent()?.text()
                ?.trim()?.toFloatOrNull()
                ?.takeIf { it in 0.1f..10f }
                ?.let { Score.from(Math.round(it * 10f).toInt().coerceIn(0, 100), 100) }

            val episodes = if (isMovie) 0 else {
                cardTmdbRe.find(url)?.groupValues?.get(1)?.let { episodesByTmdbId[it] } ?: 0
            }
            val nm = if (episodes > 0) "$nm0 ($episodes EP)" else nm0

            out += if (isMovie) {
                newMovieSearchResponse(nm, url, TvType.Movie) {
                    posterUrl = poster
                    this.score = score
                }
            } else {
                newTvSeriesSearchResponse(nm, url, TvType.AsianDrama) {
                    posterUrl = poster
                    this.score = score
                }
            }
        }
        return out
    }

    /** One TMDB call per id; 0 = unknown/failed (card stays un-suffixed). */
    private suspend fun tmdbEpisodeCount(id: String): Int {
        val key = TMDB_KEY ?: return 0
        return try {
            val body = app.get(
                "$TMDB_API/tv/$id",
                headers = mapOf("Authorization" to "Bearer $key"),
            ).text
            JSONObject(body).optInt("number_of_episodes", 0)
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * Episode counts for [ids]. 7-day in-memory cache (1 h for failures,
     * so a flaky TMDB recovers quickly), 20 s hard budget per page — when
     * the budget runs out, remaining cards keep their plain names.
     * Sequential: the plugin compile classpath has no kotlinx-coroutines,
     * so there is no cheap fan-out; ~20 calls x ~300 ms is acceptable for
     * a home refresh.
     */
    private suspend fun fetchTmdbEpisodeCounts(ids: List<String>): Map<String, Int> {
        val out = HashMap<String, Int>()
        if (ids.isEmpty() || TMDB_KEY == null) return out
        val deadline = System.currentTimeMillis() + 20_000
        for (id in ids.distinct()) {
            if (System.currentTimeMillis() > deadline) break
            val now = System.currentTimeMillis()
            val c = synchronized(tmdbLock) { tmdbEpCache[id] }
            if (c != null) {
                val ttl = if (c.first > 0) TMDB_EP_TTL_MS else TMDB_FAIL_TTL_MS
                if (now - c.second <= ttl) {
                    out[id] = c.first
                    continue
                }
            }
            val n = tmdbEpisodeCount(id)
            out[id] = n
            synchronized(tmdbLock) {
                if (tmdbEpCache.size > TMDB_EP_CACHE_MAX) tmdbEpCache.clear()
                tmdbEpCache[id] = n to now
            }
        }
        return out
    }

    private fun tvTypeOf(url: String): TvType =
        if (url.contains("type=movie")) TvType.Movie else TvType.AsianDrama

    // ------------------------------------------------------------------
    // MainAPI
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = mainUrl.removeSuffix("/") + "/dramas.php?type=${request.data}&page=${page.coerceAtLeast(1)}"
        // UG-5 dead tier: dead listing pages are not re-tried for 1 h.
        if (Cache.isDead(url)) return newHomePageResponse(request, emptyList())
        return try {
            val resp = app.get(url, interceptor = cfKillerSingleton())
            if (resp.code == 404 || resp.code == 410) {
                Cache.markDead(url)
                return newHomePageResponse(request, emptyList())
            }
            val doc = resp.document
            val counts = fetchTmdbEpisodeCounts(doc.toCardTmdbIds())
            newHomePageResponse(request, doc.toCards(counts))
        } catch (_: Throwable) {
            newHomePageResponse(request, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val doc = app.get(
                mainUrl.removeSuffix("/") + "/dramas.php",
                params = mapOf("type" to "all", "q" to query),
                interceptor = cfKillerSingleton(),
            ).document
            val counts = fetchTmdbEpisodeCounts(doc.toCardTmdbIds())
            doc.toCards(counts)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        // UG-5: re-opened detail pages are served from the episode cache.
        Cache.episodes.get(url)?.let { return it }
        val doc = app.get(url, interceptor = cfKillerSingleton()).document
        val nm = doc.selectFirst("h1")?.text()?.trim()
            ?: throw Exception("Could not parse title from $url")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img[src*=" + "image.tmdb.org]")?.attr("src")
        val description = doc.selectFirst("meta[name=description]")?.attr("content")
        val year = Regex("\\((19\\d{2}|20\\d{2})\\)").find(
            doc.selectFirst("title")?.text() ?: nm
        )?.value?.trim('(', ')')?.toIntOrNull()
            ?: Regex("(19\\d{2}|20\\d{2})").find(nm)?.value?.toIntOrNull()
        val score = Regex("(?is)fa-star.{0,120}?>([0-9]+[.,][0-9]+)").find(doc.outerHtml())
            ?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()

        val isMovie = url.contains("type=movie")
        val episodes = if (isMovie) {
            listOf(
                newEpisode(url) {
                    name = "Movie"
                }
            )
        } else {
            val seen = LinkedHashSet<String>()
            val list = mutableListOf<Episode>()
            for (a in doc.select("a[href*=watch.php]")) {
                val href = a.attr("href")
                if (href.contains("type=movie")) continue
                val m = tvEpRe.find(href) ?: continue
                val s = m.groupValues[3].toInt()
                val e = m.groupValues[5].toInt()
                if (!seen.add("$s-$e")) continue
                val eUrl = toAbsoluteUrl(href) ?: continue
                list += newEpisode(eUrl) {
                    name = "Episode $e"
                    season = s
                    episode = e
                }
            }
            list.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            list
        }

        val resp = if (isMovie) {
            newMovieLoadResponse(nm, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                plot = description
                this.score = score?.let { Score.from10(it) }
            }
        } else {
            newTvSeriesLoadResponse(nm, url, TvType.AsianDrama, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.score = score?.let { Score.from10(it) }
            }
        }
        Cache.episodes.put(url, resp)
        return resp
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            val id = idRe.find(data)?.groupValues?.get(1) ?: return false
            val isMovie = data.contains("type=movie")
            val season = Regex("season=(\\d+)").find(data)?.groupValues?.get(1)?.toIntOrNull()
            val episode = Regex("episode=(\\d+)").find(data)?.groupValues?.get(1)?.toIntOrNull()
            val vb = SiteConfig.vidsyncBase()
            val embedUrl = if (isMovie) {
                "$vb/embed/movie/$id/"
            } else {
                "$vb/embed/tv/$id/?season=${season ?: 1}&episode=${episode ?: 1}"
            }

            // UG-4: the single vidsync embed resolves under the shared
            // ~20 s budget; the resolver owns the live-API call, the curation
            // cache fallback, dedupe and quality ranking.
            val ctx = ResolveContext(name, data)
            val results = Resolvers.resolveAll(
                ctx,
                ResolveContext.TOTAL_BUDGET_MS,
                listOf(EmbedTask(embedUrl, name)),
            )
            var added = false
            for ((_, res) in results) {
                if (res.ok) added = emitResult(res, name, UA, subtitleCallback, callback) || added
            }

            // Fallback discipline (AI_RULES §5): vidsync is intermittent
            // (521/unreachable seen 2026-10-03), so when it yields nothing
            // playable fall through to watch-page server 6 (YOY ->
            // kisskh.megaplay.su), which carries English subtitles and was
            // verified end to end. Runs only when the primary produced
            // nothing, so it costs nothing on the happy path.
            if (!added) {
                val yoy = Yoy4Resolver().resolve(data)
                if (yoy.ok) {
                    added = emitResult(yoy, name, UA, subtitleCallback, callback)
                }
            }
            added
        } catch (_: Throwable) {
            false
        }
    }

}
