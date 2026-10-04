package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit


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
        @Synchronized
        private fun cfKillerSingleton(): FlareSolverrInterceptor =
            // One interceptor for the whole extension, so the provider, the
            // fan-out and both dedicated resolvers share a single cached
            // clearance instead of each solving the same host.
            CloudflareGate.interceptor()

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
        // Control experiment: this method definitely runs when the user opens a
        // KDrama.in tab, so if dbg.log still does not appear afterwards then
        // ExtLog cannot write to that path and every other absence is
        // meaningless. If it DOES appear, the file channel works and the
        // loadLinks silence is real.
        ExtLog.log("kd", "getMainPage page=$page type=${request.data}")
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

    /**
     * Human label for a k-drama.in watch-page server, from its embed URL.
     *
     * The owner wants every working server listed by name when an episode is
     * opened, so each emitted ExtractorLink carries the server it came from
     * rather than a bare quality string. Returns null for URLs that are not
     * k-drama.in server embeds (e.g. the vidsync primary), which then keeps
     * whatever name its resolver gave.
     *
     * Server 7 (`/player.php`, labelled "Hindi" by the site) is absent on
     * purpose: verified 2026-10-04 that its upstream HAPI endpoint answers
     * **403 even through a fully solved FlareSolverr session with valid
     * cf_clearance**, so it yields no content in any language. Including it
     * would only cost a request and a dead entry in the list.
     */
    private fun serverLabel(url: String): String? {
        val u = url.lowercase()
        return when {
            u.contains("/13.php/") -> "Server 2"
            u.contains("/2.php/") -> "Server 3 (moviebox)"
            u.contains("yoy4.php") -> "Server 6 (YOY)"
            u.contains("vidzee") -> "Server 4 (Vidzee)"
            u.contains("zxcstream.xyz") || u.contains("zxcprime.xyz") -> "Server 5 (ZXC)"
            // Server 1 (vidsync) and server 7 (/player.php) intentionally absent.
            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // First statement on purpose: if this line never appears in logcat then
        // CloudStream is not calling loadLinks at all, and every theory about
        // our own code is moot. Verified reachable as `System.out` (the app's
        // `Loaded everything` line shows stdout is not suppressed).
        println("[kd] loadLinks ENTER data=$data")
        ExtLog.log("kd", "loadLinks ENTER casting=$isCasting data=$data")
        return try {
            val id = idRe.find(data)?.groupValues?.get(1)
            if (id == null) {
                ExtLog.log("kd", "id did NOT match in data=$data")
                return false
            }
            val isMovie = data.contains("type=movie")
            val season = Regex("season=(\\d+)").find(data)?.groupValues?.get(1)?.toIntOrNull()
            val episode = Regex("episode=(\\d+)").find(data)?.groupValues?.get(1)?.toIntOrNull()
            ExtLog.log("kd", "id=$id movie=$isMovie s=${season ?: 1} e=${episode ?: 1}")
            println("[kd] loadLinks id=$id isCasting=$isCasting")
            val vb = SiteConfig.vidsyncBase()
            val embedUrl = if (isMovie) {
                "$vb/embed/movie/$id/"
            } else {
                "$vb/embed/tv/$id/?season=${season ?: 1}&episode=${episode ?: 1}"
            }

            // Multi-server fan-out (see MultiServerResolver): the watch page
            // offers seven interchangeable servers and any one of them may
            // lack this title, so try them all under the shared budget instead
            // of betting everything on one embed. Verified 2026-10-03: for
            // id=290699 s1e1 server 1 (vidsync) is 522 dead while server 3
            // (/2.php) returns the episode with a direct m3u8.
            val msr = MultiServerResolver(name)
            val tasks = LinkedHashSet<EmbedTask>()
            tasks += EmbedTask(embedUrl, name)          // primary, stays first
            tasks += msr.tasksFor(
                mainUrl = mainUrl,
                id = id,
                season = season ?: 1,
                episode = episode ?: 1,
                isMovie = isMovie,
            )

            val ctx = ResolveContext(name, data)
            val budgetMs = ResolveContext.TOTAL_BUDGET_MS
            val fanOutStarted = System.currentTimeMillis()
            // The generic fan-out AND the two dedicated resolvers all run at
            // once. They used to run back-to-back, which doubled the wall clock
            // against one shared budget: the fan-out could spend its whole
            // allowance, then DevcorpResolver and Yoy4Resolver (which each cost
            // a ~12 s Cloudflare solve plus, for YOY, a 7 s render) started with
            // nothing left and were cancelled - `IOException: Canceled` then
            // `No Links Found` on device 2026-10-04.
            //
            // Yoy4Resolver is deliberately included *only* here rather than in
            // the task list: it needs its own referer-aware walk, and its page
            // is an SPA that only FlareSolverr can render.
            val dedicated = Concurrency.pool.submit<List<Pair<String, ResolveResult>>> {
                val acc = mutableListOf<Pair<String, ResolveResult>>()
                // Server 3 (moviebox) hides its stream and subtitles inside JSON
                // query parameters of a player.html iframe, which the generic
                // host-matched resolver cannot parse - it needs DevcorpResolver.
                // This is the server that serves titles vidsync lacks (verified:
                // "Fangs of Fortune" id=239389 - vidsync 521s, server 3 works).
                acc += "Server 3 (moviebox)" to DevcorpResolver().resolve(
                    MultiServerResolver.kdramaInProxy(
                        mainUrl, id, season ?: 1, episode ?: 1, 2,
                    ),
                )
                // Server 6 (YOY -> kisskh.megaplay.su) likewise.
                acc += "Server 6 (YOY)" to Yoy4Resolver().resolve(data)
                acc
            }

            val results = Resolvers.resolveAll(ctx, budgetMs, tasks.toList())
            ExtLog.log("kd", "fan-out done: ${results.size} results")
            // Whatever the fan-out left of the shared budget, capped so a
            // pathological case cannot block the UI thread for minutes.
            val dedicatedWaitMs =
                (budgetMs - (System.currentTimeMillis() - fanOutStarted))
                    .coerceIn(2_000L, 30_000L)

            // Emit EVERY server that resolves, not just the first.
            //
            // The owner asked for all servers listed when an episode is opened,
            // so this used to be the wrong shape twice over: `emitResult` was
            // only reached while a previous result was still false, and the
            // dedicated resolvers below were each gated behind `if (!added)`.
            // Effect on device: one working server suppressed the others, and a
            // title served by two servers showed only one. Verified 2026-10-04
            // for "The Scandal" (id=275102) s1e1, where servers 3 AND 6 both
            // carry the episode but only one was listed.
            var added = false
            val seenUrls = LinkedHashSet<String>()
            for ((url, res) in results) {
                if (!res.ok) continue
                // CloudStream shows each ExtractorLink's own `name`, so the
                // per-server label rides on the resolved source name instead of
                // on emitResult (whose `source` arg is the provider name).
                val label = serverLabel(url)
                val named = if (label != null && res.sources.all { it.name.isBlank() }) {
                    res.copy(sources = res.sources.map { it.copy(name = label) })
                } else {
                    res
                }
                // The same URL can arrive from both the fan-out and the
                // dedicated resolvers (e.g. /2.php is a task AND
                // DevcorpResolver's target) - emit it once.
                val fresh = named.copy(
                    sources = named.sources.filter { seenUrls.add(it.url) },
                )
                if (fresh.sources.isEmpty()) continue
                added = emitResult(fresh, name, UA, subtitleCallback, callback) || added
            }

            val dedicatedResults = try {
                dedicated.get(dedicatedWaitMs, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
                null
            }
            dedicatedResults?.forEach { (label, res) ->
                if (!res.ok) return@forEach
                ExtLog.log("kd", "dedicated $label ok sources=${res.sources.size}")
                val named = res.copy(
                    sources = res.sources
                        .filter { seenUrls.add(it.url) }
                        .map { s ->
                            if (s.name.isBlank()) s.copy(name = label) else s
                        },
                )
                if (named.sources.isEmpty()) return@forEach
                added = emitResult(named, name, UA, subtitleCallback, callback) || added
            }

            // Server 5 (zxcstream.icu) - discovery only, no source emitted.
            //
            // The site's own picker advertises six servers (Main Server,
            // Backup I-V) and we can now reproduce its discovery call exactly:
            // POST /backend/willierevillame returns {token,ts}, and those go
            // into GET /backend_/sources/<atlas|valstrax>, which returns the
            // real quality ladder (atlas -> hls 360/480/720/1080).
            //
            // Each per-quality `link` is an OpenSSL AES-CBC envelope whose key
            // never reaches the browser (32 chunks contain no Salted__/EVP/
            // passphrase; the token is not the key), so there is no URL we could
            // honestly hand over. Reporting the ladder is still worth doing -
            // it is the difference between "no source" and "this title has
            // 1080p here but the stream is worker-gated" - so surface it as an
            // explicit informational entry rather than inventing a link.
            //
            // The VIDEO manifest is gated, but the SUBTITLES for the same
            // server are not: /backend/subtitle answers in plain JSON with
            // signed .srt URLs (verified: 11 languages for id=239389 s1e1,
            // English included, and the .srt downloads clean). Those are emitted
            // below, so server 5 still contributes something real to the
            // episode while its video stays unresolved.
            // NOT gated on `added`: these are subtitles, not a competing video source, so
            // they should be offered for the episode regardless of which servers
            // resolved. Gating them meant that whenever servers 3/6 worked (the
            // common case) the server-5 English track silently never appeared.
            val zxcSubs = ZxcSubtitleResolver.subtitlesFor(
                id = id,
                season = season ?: 1,
                episode = episode ?: 1,
                isMovie = isMovie,
            )
            for ((_, sub) in zxcSubs) {
                // emitResult handles the isCasting / dedupe / callback
                // plumbing, but it needs a source too, so hand the subtitle
                // to the same emission path directly.
                subtitleCallback(
                    // Verified via javap on cloudstream.jar: SubtitleFile's
                    // only constructor is (lang, url); `headers` is a
                    // mutable property, not a third parameter.
                    SubtitleFile(sub.lang, sub.url).also {
                        it.headers = sub.headers
                    },
                )
            }
            if (zxcSubs.isNotEmpty()) {
                // android.util.Log is unavailable in this module's JVM harness
                // (NoClassDefFoundError), and the repo has no logging wrapper,
                // so use stdout - which is what reaches device logcat as
                // System.out for an extension.
                println(
                    "[$name] zxc(server 5) subtitles: " +
                        "${zxcSubs.size} languages (${zxcSubs.keys.joinToString()})",
                )
            }
            if (!added) {
                val zxc = ZxcResolver.probe(
                    id = id,
                    season = season ?: 1,
                    episode = episode ?: 1,
                    isMovie = isMovie,
                )
                // Deliberately NOT emitted as a link. A placeholder URL would
                // appear in the client as a source that silently never plays,
                // which is indistinguishable from "no source" and corrupts the
                // client's view of the catalogue. Log the ladder so it is
                // diagnosable, and report no source - honestly.
                // android.util.Log is unavailable in this module's JVM harness
                // (NoClassDefFoundError), and the repo has no logging wrapper,
                // so use stdout - which is what reaches device logcat as
                // System.out for an extension.
                if (zxc.isNotEmpty()) {
                    println("[$name] zxc discovery (no playable source): $zxc")
                    ExtLog.log("kd", "zxc ladder: $zxc")
                }
            }
            ExtLog.log("kd", "DONE added=$added")
            added
        } catch (t: Throwable) {
            // This blanket catch is exactly why every earlier failure was
            // invisible: it returned false with nothing recorded anywhere, and
            // CloudStream reported only "No Links Found". Always log it.
            ExtLog.log("kd", "THREW ${t.javaClass.simpleName}: ${t.message} @ ${t.stackTrace.take(4)}")
            false
        }
    }

}
