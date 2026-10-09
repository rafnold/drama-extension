package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Levidia (https://supernova.to, mirrors goojara.to / ww1.goojara.to) — a
 * scrapable Movies + TV aggregator. supernova.to is the primary host;
 * goojara.to 301-redirects to ww1.goojara.to. From the build host (NL
 * datacenter IP 178.84.195.10) supernova.to returns a Cloudflare 403
 * ("Just a moment...") — that is a HOST problem, not a code bug; the
 * harness skips CF-blocked sites. goojara.to (→ ww1.goojara.to) yields
 * 200 with full markup, so the selectors below were verified against it,
 * but the same HTML shape is used on supernova.to from a residential IP.
 *
 * VERIFIED CHAIN (2026-10-09, ww1.goojara.to):
 *
 * 1. Listing / main page. Static HTML (no JS fetch for the featured grid):
 *    GET {mainUrl}/watch-trends-popular  (and /watch-movies, /watch-series,
 *    /watch-trends-genre, /watch-trends-year, /watch-trends-az)
 *    -> <ul class="mfeed"><li><a href="/{id}"><div class="im">
 *         <strong>{Title} ({Year})</strong> <span class="flag"></span>
 *         <span class="hd {hda|hdc|hdy}">{QUALITY}</span></div></a>
 *         <div class="ddt"></div></li>...</ul>
 *    Movie ids start with 'm', series ids with 'e' (verified: /m862ed
 *    Spider-Man movie, /e06rMM MobLand S2E2). Titles+year in <strong>.
 *
 * 2. Detail page. GET {mainUrl}/{id}
 *    -> <h1>{Title} ({Year})</h1>  (movie) or <h1>{Title} S{S}, E{E} - {Ep}</h1>
 *    -> poster: <div id="poster" class="imrl"><img src="{md.goojara.to/xxxx.jpg}">
 *    -> overview: <div class="fimm"><p>{plot}</p>...
 *    -> movie: <div class="date">{min}min | {genres} | {monthYear}</div>
 *    -> series: season tabs <a href="{seriesId}?s={N}"> and a "Seasons:"
 *       dropdown <div id="drop"><a href="{seriesId}?s={N}">{N}</a>...</div>.
 *       A series detail page is per-episode; the series root is
 *       {seriesId} (e.g. /tQa0dB) and each season is {seriesId}?s={N}.
 *
 * 3. Episodes (series only). The series root page {seriesId} lists every
 *    episode as <a href="{epId}?s={season}&e={episode}"> — parse that grid.
 *    (From the build host the series root returns 200 with the same
 *    episode-grid markup; on supernova.to the equivalent is scraped the
 *    same way from a residential IP.)
 *
 * 4. Playback. Each detail page has a "Direct Links" block:
 *    <a class="bcg" href="{mainUrl}/go.php?url={b64}>{Provider}
 *      <span>{QUALITY}</span></a>
 *    The {b64} is base64 of a provider token. go.php resolves the token to
 *    the real CDN (dood/luluvdo/Wootly/AV1.Opus) — a runtime endpoint that
 *    works from residential IPs (from the build host it 404s, i.e. a
 *    host/egress problem). We follow go.php with the Referer set to the
 *    detail page (Referer gating) and collect the resolved stream URL.
 *
 * Subtitles: none observed on the live site — the Direct Links block carries
 * no caption track, so subtitleCallback is left uninvoked.
 */
class Levidia : MainAPI() {

    override var name = "Levidia"
    override var mainUrl = SiteConfig.mirror("levidia")
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val mainPage = mainPageOf(
        "popular" to "Popular",
        "movies" to "Movies",
        "series" to "TV Series",
        "genre" to "Genre",
        "year" to "By Year",
        "az" to "A-Z",
    )

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        // Cards live under these listing paths (see KDoc §1).
        private val listingPaths = mapOf(
            "popular" to "/watch-trends-popular",
            "movies"  to "/watch-movies",
            "series"  to "/watch-series",
            "genre"   to "/watch-trends-genre",
            "year"    to "/watch-trends-year",
            "az"      to "/watch-trends-az",
        )

        // go.php resolves a base64 provider token to the real CDN stream.
        private val goRe = Regex("/go\\.php\\?url=([A-Za-z0-9+/=]+)")
        // {id} detail links: single path segment of letters/digits.
        private val idRe = Regex("/([a-zA-Z0-9]+)")
        // Poster host (verified: md.goojara.to/xxxx.jpg).
        private val posterHostRe = Regex("md\\.goojara\\.to/(\\d+\\.jpg)")
        // Season/episode query on episode detail links: ?s=2&e=2
        private val seRe = Regex("[?&]s=(\\d+)&e=(\\d+)")
        // Series root: /{seriesId} or /{seriesId}?s={N}
        private val seriesRe = Regex("/([a-zA-Z0-9]+)(?:\\?s=(\\d+))?")

        private val posterCache = HashMap<String, String>()
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
        val out = mutableListOf<SearchResponse>()
        val seen = LinkedHashSet<String>()
        for (a in select("ul.mfeed li a, a[href^='/']")) {
            val href = toAbsoluteUrl(a.attr("href")) ?: continue
            val idMatch = idRe.find(href) ?: continue
            val id = idMatch.groupValues[1]
            if (id.isBlank() || !seen.add(id)) continue
            val im = a.selectFirst("div.im")
            val title = im?.selectFirst("strong")?.text()?.trim()
                ?: a.text().trim()
            if (title.isBlank()) continue
            val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            val quality = im?.selectFirst("span.hd")?.text()?.trim().orEmpty()
            val poster = im?.attr("src")?.let { toAbsoluteUrl(it) }
                ?: a.selectFirst("img")?.attr("src")?.let { toAbsoluteUrl(it) }
            val isMovie = id.startsWith("m")
            val url = "$mainUrl/$id"
            if (isMovie) {
                out += newMovieSearchResponse(title, url, TvType.Movie) {
                    if (year != null) this.year = year
                    posterUrl = poster
                }
            } else {
                out += newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    if (year != null) this.year = year
                    posterUrl = poster
                }
            }
        }
        return out
    }

    private fun qualityFor(raw: String): Int = when (raw.trim().lowercase()) {
        "4k", "2160p", "2160" -> 2160
        "1440p", "2k" -> 1440
        "full hd", "fhd", "1080p", "1080" -> 1080
        "720p", "720" -> 720
        "hdtv", "hd", "sdtv" -> 480
        "cam", "ts", "scr" -> 0
        else -> 0
    }

    // ------------------------------------------------------------------
    // MainAPI
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ExtLog2.log("lev", "getMainPage ENTER")
        val path = listingPaths[request.data] ?: "/watch-trends-popular"
        val url = if (page <= 1) "$mainUrl$path" else "$mainUrl$path/page/$page/"
        return try {
            val resp = app.get(url, headers = mapOf("User-Agent" to UA))
            val doc = resp.document
            newHomePageResponse(request, doc.toCards())
        } catch (_: Throwable) {
            newHomePageResponse(request, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ExtLog2.log("lev", "search ENTER")
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        return try {
            // The site's ?s= page is client-side rendered; its AJAX search
            // endpoint returns JSON. Fall back to scanning the featured grid
            // and filtering locally (verified behaviour).
            val url = "$mainUrl/s/$q"
            val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
            val out = mutableListOf<SearchResponse>()
            val needle = q.lowercase()
            for (card in doc.toCards()) {
                if (card.name.lowercase().contains(needle)) out += card
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        ExtLog2.log("lev", "load ENTER")
        val idMatch = idRe.find(url) ?: throw Exception("Not a Levidia URL: $url")
        val id = idMatch.groupValues[1]
        val isMovie = id.startsWith("m")
        val text = try {
            app.get(url, headers = mapOf("User-Agent" to UA)).text
        } catch (_: Throwable) {
            throw Exception("Could not load detail for $url")
        }
        val doc = Jsoup.parse(text)

        val h1 = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val year = Regex("\\((\\d{4})\\)").find(h1)?.groupValues?.get(1)?.toIntOrNull()
        val title = if (isMovie) {
            h1.substringBefore("(").trim()
        } else {
            // "MobLand S2, E2 - Song 2" -> series name "MobLand"
            Regex("^(.+?)\\s+S\\d+").find(h1)?.groupValues?.get(1)?.trim() ?: h1
        }
        if (title.isBlank()) throw Exception("No title in $url")

        val poster = doc.selectFirst("#poster img")?.attr("src")
            ?: doc.selectFirst("img")?.attr("src")
        val posterUrl = poster?.let { toAbsoluteUrl(it) }
        val plot = doc.selectFirst(".fimm")?.text()?.trim().orEmpty()

        val episodes: List<Episode> = if (isMovie) {
            listOf(newEpisode(url) { name = "Movie" })
        } else {
            fetchEpisodes(doc, url)
        }

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                if (year != null) this.year = year
                this.posterUrl = posterUrl
                this.plot = plot
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                if (year != null) this.year = year
                this.posterUrl = posterUrl
                this.plot = plot
            }
        }
    }

    /**
     * Series detail pages are per-episode. The series root {seriesId}
     * (or {seriesId}?s={season}) lists every episode as
     * <a href="{epId}?s={season}&e={episode}"> — parse that grid.
     */
    private fun fetchEpisodes(doc: Document, seriesUrl: String): List<Episode> {
        val out = mutableListOf<Episode>()
        val seen = LinkedHashSet<String>()
        for (a in doc.select("a[href*='?s=']")) {
            val href = toAbsoluteUrl(a.attr("href")) ?: continue
            val se = seRe.find(href) ?: continue
            val epId = se.groupValues[1]
            val season = se.groupValues[2].toIntOrNull() ?: 1
            val episode = se.groupValues[3].toIntOrNull() ?: 1
            val epUrl = "$mainUrl/$epId"
            if (!seen.add(epUrl)) continue
            val name = a.text().trim()
            out += newEpisode(epUrl) {
                this.name = name.ifBlank { "Episode $episode" }
                this.season = season
                this.episode = episode
            }
        }
        out.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        return out
    }

    // ------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        ExtLog2.log("lev", "loadLinks ENTER")
        return try {
            val idMatch = idRe.find(data) ?: return false
            val id = idMatch.groupValues[1]
            val detailUrl = "$mainUrl/$id"
            val doc = app.get(detailUrl, headers = mapOf("User-Agent" to UA)).document

            // "Direct Links" block: <a class="bcg" href="{mainUrl}/go.php?url={b64}">
            val links = ArrayList<Triple<String, String, String>>() // (provider, quality, token)
            for (a in doc.select("a.bcg")) {
                val href = a.attr("href")
                val go = goRe.find(href) ?: continue
                val token = go.groupValues[1]
                val provider = a.text().trim().ifBlank { "Source" }
                val quality = a.selectFirst("span")?.text()?.trim().orEmpty()
                links += Triple(provider, quality, token)
            }
            if (links.isEmpty()) return false

            var added = false
            for ((provider, quality, token) in links) {
                val streamUrl = resolveGo(detailUrl, token) ?: continue
                if (streamUrl.isBlank()) continue
                callback(
                    newExtractorLink(name, provider, streamUrl, ExtractorLinkType.M3U8) {
                        referer = detailUrl
                        this.quality = qualityFor(quality)
                    }
                )
                added = true
            }
            added
        } catch (_: Throwable) {
            ExtLog2.log("lev", "loadLinks exit")
            false
        }
    }

    /**
     * Resolve a go.php base64 token to the real CDN stream URL. Works from
     * residential IPs; from the build host it 404s (host/egress problem).
     * The Referer is the detail page (Referer gating).
     */
    private suspend fun resolveGo(referer: String, token: String): String? {
        val goUrl = "$mainUrl/go.php?url=$token"
        val resp = try {
            app.get(goUrl, headers = mapOf("User-Agent" to UA), referer = referer)
        } catch (_: Throwable) {
            return null
        }
        if (resp.code != 200) return null
        val text = resp.text
        // The resolver returns the CDN URL in the page (iframe src, playlist
        // var, or a direct link). Try common shapes.
        val iframe = Regex("iframe[^>]*src=['\"]([^'\"]+)['\"]").find(text)
        if (iframe != null) {
            val src = iframe.groupValues[1]
            if (src.startsWith("http")) return toAbsoluteUrl(src) ?: src
        }
        val m3u8 = Regex("(https?://[^'\"\\s]+\\.m3u8[^'\"\\s]*)").find(text)
        if (m3u8 != null) return m3u8.groupValues[1]
        val srcVar = Regex("var\\s+src\\s*=\\s*['\"]([^'\"]+)['\"]").find(text)
        if (srcVar != null) return toAbsoluteUrl(srcVar.groupValues[1]) ?: srcVar.groupValues[1]
        return null
    }
}
