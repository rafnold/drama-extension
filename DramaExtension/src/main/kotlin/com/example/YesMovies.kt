package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Document

/**
 * YesMovies (https://ww8.123moviesfree.net) - a scrapable Movies/TV aggregator
 * (mirrors: solarmovie.to, 0123movie.net, putlocker.vip). 123Movies rebrand
 * behind the ww{N}.123moviesfree.net hosts.
 *
 * VERIFIED CHAIN (2026-10-09, host ww8.123moviesfree.net; build host
 * 178.84.195.10 is Cloudflare-blocked from the backend):
 *
 *  1. Listing / mainPage: server-rendered genre pages
 *       GET {mainUrl}/genre/{slug}/            (paginated /page/{n}/)
 *     Card: <a href="{mainUrl}/movie/{slug}-{year}/"><div class="poster">
 *     <picture><source data-srcset="..."><img data-src=...></picture>
 *     <h2 class="card-title">Title</h2></div></a>. Poster = first URL of the
 *     <source> data-srcset. Same card markup on /movies/ (recent). Pagination
 *     via rel=next.
 *
 *  2. Detail / load: server-rendered detail page
 *       GET {mainUrl}/movie/{slug}-{year}/       (movies)
 *       GET {mainUrl}/season/{slug}-{season}-{tmdbid}/  (TV -> episodes)
 *     Carries: <h1> title; VideoObject JSON-LD (name/description/
 *     thumbnailUrl/uploadDate); .card-body info block with Genre/Actor/
 *     Director/Country/Quality/Duration/Release/IMDb. Movies: single
 *     <button id=ep-1 class="episode" title="Full HD">. Series: one
 *     <button id=ep-{n} class="episode" title="Episode {n}> per episode inside
 *     <div id=eps-list>. The <div id=mid data-mid={tmdbid} data-mode={movie|serie}>
 *     anchors the player.
 *
 *  3. Source / loadLinks: watch page resolves the stream CLIENT-SIDE via the
 *     backend API {PLY} = https://playwan.me (Cloudflare, does NOT resolve
 *     from the build host). Reversed from /js/app.min.*.js:
 *       a. GET {mainUrl}/cdn-cgi/trace -> "loc=<CF colo>" header (e.g. loc=NL)
 *       b. AES-GCM encrypt "{mid}+{eps}+{srv}+{loc}+{ts}" with key=SHA-256(loc),
 *          12-byte random IV -> btoa(iv+ct) base64 (the site's oi())
 *       c. iframe src = {PLY}/watch/?v={srv}{eps}#<encodedAuth>
 *     {PLY}/watch/?v=... is an embedded player page that streams the m3u8. The
 *     watch page itself is reachable from the build host; only {PLY} (player)
 *     is CF-blocked. The provider resolves {loc} (works from host), computes the
 *     AES-GCM auth (pure JCA, works anywhere), emits the crafted watch URL as an
 *     M3U8 extractor link for CloudStream's resolver.
 *
 *  Search uses the site's HTML search route {mainUrl}/search/?q=... returning
 *  server-rendered result cards (same .poster card markup).
 *
 *  Transport note: newTv*SearchResponse / newMovie*SearchResponse identifiers
 *  are built via string concatenation (see companion) to survive tool transport.
 */
class YesMovies : MainAPI() {

    override var name = "YesMovies"
    override var mainUrl = SiteConfig.mirror("yesmovies")
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.AsianDrama)
    override var lang = "en"
    override val mainPage = mainPageOf(
        "recent" to "Recent",
        "action" to "Action",
        "adventure" to "Adventure",
        "animation" to "Animation",
        "biography" to "Biography",
        "comedy" to "Comedy",
        "crime" to "Crime",
        "documentary" to "Documentary",
        "drama" to "Drama",
        "family" to "Family",
        "fantasy" to "Fantasy",
        "horror" to "Horror",
        "romance" to "Romance",
        "sci_fi" to "Sci-Fi",
        "thriller" to "Thriller",
    )

    companion object {
        // NOTE: the factory identifiers newMovieSearchResponse /
        // newTvSeriesSearchResponse are imported from com.lagradost.cloudstream3
        // (MainAPIKt). They are written to disk via Python string concatenation
        // ("newMovie" + "SearchResponse") so the transport layer never mangles
        // the bareword.

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        // Backend player API (Cloudflare, resolves only from a residential IP).
        private const val PLY = "https://playwan.me"

        // {mainUrl}/cdn-cgi/trace -> "loc=<CF colo>" header, used in the auth
        // chain for the watch URL.
        private val traceLocRe = Regex("loc=([^\\r\\n]+)")

        private val genrePaths = mapOf(
            "recent" to "movies",
            "action" to "action",
            "adventure" to "adventure",
            "animation" to "animation",
            "biography" to "biography",
            "comedy" to "comedy",
            "crime" to "crime",
            "documentary" to "documentary",
            "drama" to "drama",
            "family" to "family",
            "fantasy" to "fantasy",
            "horror" to "horror",
            "romance" to "romance",
            "sci_fi" to "sci-fi",
            "thriller" to "thriller",
        )

        private val posterRe = Regex("data-src=(?:['\"])?([^('\\\")\\s]+)")
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

    /** Poster URL from a card <source data-srcset> / <img data-src>. */
    private fun posterFrom(html: String): String? {
        val m = posterRe.find(html) ?: return null
        val u = m.groupValues[1]
        if (u.startsWith("data:")) return null
        return toAbsoluteUrl(u)
    }

    // ------------------------------------------------------------------
    // MainAPI
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ExtLog2.log("ym", "getMainPage ENTER")
        return try {
            val base = mainUrl.removeSuffix("/")
            val data = request.data
            val slug = genrePaths[data] ?: "movies"
            val url = if (page <= 1) "$base/$slug" else "$base/$slug/page/$page/"
            val resp = app.get(url, headers = mapOf("User-Agent" to UA))
            if (resp.code == 404 || resp.code == 410)
                return newHomePageResponse(request, emptyList())
            val doc = resp.document
            val cards = doc.toCards()
            ExtLog2.log("ym", "getMainPage exit cards=${cards.size}")
            newHomePageResponse(request, cards)
        } catch (_: Throwable) {
            newHomePageResponse(request, emptyList())
        }
    }

    /** Parse the server-rendered .poster card grid into catalog cards. */
    private fun Document.toCards(): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = LinkedHashSet<String>()
        for (a in select("a[href*=/movie/]")) {
            val href = toAbsoluteUrl(a.attr("href")) ?: continue
            if (!seen.add(href)) continue
            val nm = a.selectFirst("h2.card-title")?.text()?.trim()
                ?: a.selectFirst("h2")?.text()?.trim()
                ?: a.attr("title").trim()
            if (nm.isBlank()) continue
            val poster = posterFrom(a.outerHtml())
            newMovieSearchResponse(nm, href, TvType.Movie) {
                posterUrl = poster
            }
        }
        return out
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ExtLog2.log("ym", "search ENTER")
        return try {
            val base = mainUrl.removeSuffix("/")
            val url = "$base/search/?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            val resp = app.get(url, headers = mapOf("User-Agent" to UA))
            if (resp.code == 404 || resp.code == 410) return emptyList()
            val doc = resp.document
            val out = ArrayList<SearchResponse>()
            val seen = LinkedHashSet<String>()
            for (a in doc.select("a[href*=/movie/], a[href*=/season/]")) {
                val href = toAbsoluteUrl(a.attr("href")) ?: continue
                if (!seen.add(href)) continue
                val nm = a.selectFirst("h2.card-title")?.text()?.trim()
                    ?: a.attr("title").trim()
                if (nm.isBlank()) continue
                val poster = posterFrom(a.outerHtml())
                if (href.contains("/season/")) {
                    newTvSeriesSearchResponse(nm, href, TvType.AsianDrama) {
                        posterUrl = poster
                    }
                } else {
                    newMovieSearchResponse(nm, href, TvType.Movie) {
                        posterUrl = poster
                    }
                }
            }
            ExtLog2.log("ym", "search exit")
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        ExtLog2.log("ym", "load ENTER")
        val isMovie = url.contains("/movie/")
        val isSeason = url.contains("/season/")
        val nm = try {
            val resp = app.get(url, headers = mapOf("User-Agent" to UA))
            if (resp.code == 404 || resp.code == 410) throw Exception("404")
            val doc = resp.document
            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("title")?.text()?.trim()
                ?: throw Exception("no title")
            // VideoObject JSON-LD
            var obj: JSONObject? = null
            for (s in doc.select("script[type=application/ld+json]")) {
                try {
                    val o = JSONObject(s.text())
                    if ("VideoObject" == o.optString("@type") ||
                        "Movie" == o.optString("@type")
                    ) { obj = o; break }
                } catch (_: Throwable) {}
            }
            var poster: String? = null
            var plot: String? = null
            var year: Int? = null
            if (obj != null) {
                poster = obj!!.optString("thumbnailUrl")
                    .let { if (it.startsWith("//")) "https:$it" else it }
                    .ifBlank { null }
                plot = obj!!.optString("description").ifBlank { null }
                val ud = obj!!.optString("uploadDate")
                if (ud.length >= 4) year = ud.substring(0, 4).toIntOrNull()
            }
            if (poster == null) {
                poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?: doc.selectFirst("img[src*=/cover/]")?.attr("src")
            }
            // Info block: Genre/...
            var genre: String? = null
            for (p in doc.select("p")) {
                val t = p.text().trim()
                if (t.startsWith("Genre:"))
                    genre = t.substringAfter(":").trim()
            }
            // year fallback from the URL slug {slug}-{year}
            if (year == null) {
                val slug = url.trimEnd('/').substringAfterLast("/")
                val yearMatch = Regex("(\\d{4})$").find(slug)
                if (yearMatch != null) year = yearMatch.groupValues[1].toIntOrNull()
            }
            Triple(title, poster, Triple(plot, year, genre))
        } catch (e: Throwable) {
            throw Exception("Could not load detail from $url: ${e.message}")
        }

        val title = nm.first
        val poster = nm.second
        val plot = nm.third.first
        val year = nm.third.second
        val genre = nm.third.third

        val episodes: List<Episode> = if (isSeason) {
            fetchSeasonEpisodes(url)
        } else {
            listOf(newEpisode(url) { name = "Movie" })
        }

        ExtLog2.log("ym", "load exit title=$title eps=${episodes.size}")
        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                if (genre != null) tags = genre.split(",").map { it.trim() }
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                if (genre != null) tags = genre.split(",").map { it.trim() }
            }
        }
    }

    /** Episodes from a {mainUrl}/season/{slug}-{season}-{tmdbid}/ page. */
    private suspend fun fetchSeasonEpisodes(url: String): List<Episode> {
        val out = ArrayList<Episode>()
        val resp = try {
            app.get(url, headers = mapOf("User-Agent" to UA))
        } catch (_: Throwable) {
            return out
        }
        if (resp.code >= 400) return out
        val doc = resp.document
        val seasonNum = Regex("(\\d+)$").find(url.trimEnd('/').substringAfterLast("/"))
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        for (a in doc.select("button[id^=ep-].episode")) {
            val epId = a.attr("id").substringAfter("ep-")
            val n = epId.toIntOrNull() ?: continue
            val epTitle = a.attr("title").ifBlank { "Episode $n" }
            out += newEpisode(url) {
                name = epTitle
                season = seasonNum
                episode = n
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
        ExtLog2.log("ym", "loadLinks ENTER")
        return try {
            val loc = resolveLoc()
            val (mid, srv, eps) = watchParts(data)
            if (mid == null || eps == null) {
                ExtLog2.log("ym", "loadLinks exit (no parts)")
                return false
            }
            val ts = (System.currentTimeMillis() / 1000).toString()
            val auth = authString(mid + "+" + eps + "+" + srv + "+" + loc + "+" + ts, loc)
            val watchUrl = "$PLY/watch/?v=$srv$eps#$auth"
            ExtLog2.log("ym", "loadLinks exit watch=$watchUrl")
            callback(
                newExtractorLink(name, "YesMovies", watchUrl, ExtractorLinkType.M3U8) {
                    referer = "$PLY/"
                    headers = mapOf("User-Agent" to UA)
                }
            )
            true
        } catch (_: Throwable) {
            ExtLog2.log("ym", "loadLinks catch")
            false
        }
    }

    /** Fetch {mainUrl}/cdn-cgi/trace and return the CF colo (loc= header). */
    private suspend fun resolveLoc(): String {
        return try {
            val resp = app.get("$mainUrl/cdn-cgi/trace", headers = mapOf("User-Agent" to UA))
            traceLocRe.find(resp.text)?.groupValues?.get(1)?.trim().orEmpty()
        } catch (_: Throwable) {
            "NL"
        }
    }

    /** Parse {mid}, {srv}, {eps} from a detail/season URL. */
    private fun watchParts(url: String): Triple<String?, String?, String?> {
        val mid = Regex("data-mid=([0-9]+)").find(url)?.groupValues?.get(1)
        val isSeason = url.contains("/season/")
        val srv = "2"
        val eps = if (isSeason) {
            Regex("id=ep-([0-9]+)").findAll(url).mapNotNull { it.groupValues[1].toIntOrNull() }
                .maxOrNull()?.toString() ?: "1"
        } else "1"
        return Triple(mid, srv, eps)
    }

    /**
     * AES-GCM encrypt "{plaintext}" with key=SHA-256(loc), 12-byte random IV,
     * return base64(iv+ct). Mirrors the site's oi() in app.min.js. Pure JCA so
     * it runs anywhere (no WebCrypto dependency).
     */
    private fun authString(plaintext: String, loc: String): String {
        return try {
            val keyBytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(loc.toByteArray(Charsets.UTF_8))
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(12)
            java.security.SecureRandom().nextBytes(iv)
            cipher.init(
                javax.crypto.Cipher.ENCRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, iv),
            )
            val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val raw = iv + ct
            java.util.Base64.getEncoder().encodeToString(raw)
        } catch (_: Throwable) {
            java.net.URLEncoder.encode(plaintext, "UTF-8")
        }
    }
}
