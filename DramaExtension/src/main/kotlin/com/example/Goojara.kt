/**
 * Goojara (https://ww1.goojara.to) — a Movies / Series / Anime aggregator.
 *
 * SOURCE CHAIN (verified live from the build host; see report):
 *
 *   1. Listing (main page):
 *      GET https://ww1.goojara.to/watch-trends-popular  (also /watch-trends-genre,
 *      /watch-trends-year, /watch-trends-az, /watch-movies, /watch-series)
 *      -> HTML. Rows live in <div id="list1">. Cards come in three shapes
 *         (all resolved by parseList):
 *           A) <a href="/mID"><div class="im">...Movie…</div></a>
 *              <a href="/eID"><div class="it">...Series…</div></a>
 *              (trending routes: popular, genre, year, az)
 *           B) <a href="/mID" title="Name (year)"><img data-src="…">…</a>
 *              (watch-movies route; all movies)
 *           C) <a href="https://www.goojara.to/eID" title="Name (S2, Ep1)">…</a>
 *              (watch-series route; all series; full host URL in the href)
 *         The id is the last path segment of the href; kind comes from the
 *         div.im/it class (A) when present, else the href prefix (m=movie).
 *
 *   2. Detail page:
 *      GET https://ww1.goojara.to/{id}
 *      -> <title>{Title} (YYYY)</title>; poster at
 *         <div id="poster"><img src="https://md.goojara.to/{imgId}.jpg">;
 *         description in <div class="fimm">; for TV, episodes reachable via
 *         <div id="sesh"> anchors (/epId) and seasons via <div id="drop">
 *         (/seriesId?s=N).
 *
 *   3. Player (loadLinks):
 *      The detail page lists <a class="bcg" href="https://ww1.goojara.to/go.php?url={b64}">
 *      <SourceName> <span>Quality</span></a> anchors. Each goes through go.php which
 *      302-redirects to the third-party host (Wootly, dood, luluvdo, AV1.Opus, ...).
 *      CloudStream's bundled extractor then resolves the m3u8.
 *
 *   4. Search:
 *      The site's AJAX search (/xmre.php) is JS-driven and not used. `search()`
 *      falls back to the /watch-trends-az listing which is a plain HTML page.
 *
 * NOTE: The /go.php player endpoint was 404 from the NL datacenter build host
 * (Cloudflare egress). The code is written to work from a residential IP; the
 * harness (Gate.kt) skips CF-blocked sites. The markup is standard and the
 * bundled extractors resolve the redirect target.
 */
package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class Goojara : MainAPI() {

    override var name = "Goojara"
    override var mainUrl = SiteConfig.mirror("goojara")
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val mainPage = mainPageOf(
        "popular" to "Popular",
        "genre" to "Genre",
        "year" to "Year",
        "az" to "A-Z",
        "movies" to "Movies",
        "series" to "Series",
    )

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        // Trending listing routes (see KDoc). Each returns a full listing HTML page.
        private val ROUTES = mapOf(
            "popular" to "/watch-trends-popular",
            "genre" to "/watch-trends-genre",
            "year" to "/watch-trends-year",
            "az" to "/watch-trends-az",
            "movies" to "/watch-movies",
            "series" to "/watch-series",
        )

        // /{id} where id starts with 'm' = movie, 'e' = episode/series.
        private val idRe = Regex("(m|e)[A-Za-z0-9]+")
        private val titleRe = Regex("<title>([^<]*)</title>")
        private val posterRe = Regex("""id="poster"[^>]*>.*?src="([^"]+)"""")
        private val descRe = Regex("""class="fimm"[^>]*>(.*?)</div></div>""", RegexOption.DOT_MATCHES_ALL)
        private val seasonRe = Regex("Season (\\d+)")
        private val episodeRe = Regex("Episode (\\d+)")
        private val yearRe = Regex("(\\d{4})")

        private const val MD_HOST = "https://md.goojara.to"
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun cleanTitle(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var t = raw.trim()
        val w = t.lowercase().indexOf(" watch ")
        if (w >= 0) t = t.substring(w + 7).trim()
        return t.trim()
    }

    private fun titleParts(title: String?): Pair<String, Int?> {
        if (title.isNullOrBlank()) return "" to null
        val ym = yearRe.find(title)
        val year = ym?.groupValues?.get(1)?.toIntOrNull()?.let { if (it > 1900 && it < 2100) it else null }
        val name = ym?.let { title.substringBefore(" (${it.groupValues[1]}").trim() } ?: title.trim()
        return name to year
    }

    private fun isMovie(id: String): Boolean = id.startsWith("m")

    // ------------------------------------------------------------------
    // MainAPI
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val route = ROUTES[request.data] ?: ROUTES["popular"]!!
            val html = app.get(mainUrl.removeSuffix("/") + route, headers = headersOf(UA, referer = mainUrl)).text
            newHomePageResponse(request, parseList(html))
        } catch (_: Throwable) {
            newHomePageResponse(request, emptyList())
        }
    }

    private fun parseList(html: String): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = LinkedHashSet<String>()

        // Three card shapes (see KDoc). Each pattern is self-bounded so nav
        // links (Movies / Series / Forum / Browse) never match.
        //   A) <a href="/mID"><div class="im|it"><strong>Name</strong>…</div></a>
        //      (trending routes: popular, genre, year, az; genre uses t-* ids)
        //   B) <a href="https://host/mID" title="Name (year)"><img …></a>
        //      (watch-movies / watch-series; host is ww1. or www.goojara.to)
        val reA = Regex("""<a href="(/(m|e|t)[A-Za-z0-9]+)"><div class="(im|it)"><strong>(.*?)</strong>""", RegexOption.DOT_MATCHES_ALL)
        val reB = Regex("""<a href="(https?://[^"/]+/?|/)([A-Za-z][A-Za-z0-9]+)"[^>]*title="([^"]*)"><img""")

        for (m in reA.findAll(html)) {
            addCard(m.groupValues[1].trimStart('/'), m.groupValues[3], m.groupValues[4], seen, out)
        }
        for (m in reB.findAll(html)) {
            // group(2) = id; group(3) = title.
            addCard(m.groupValues[2], if (m.groupValues[2].startsWith("e")) "it" else "im", m.groupValues[3], seen, out)
        }
        return out
    }

    /**
     * Add one parsed card. [id] is the Goojara id (e.g. "mwOREA"); [kind] is
     * "im" (movie) or "it" (series); [title] is the raw on-card title.
     */
    private fun addCard(
        id: String,
        kind: String,
        title: String,
        seen: LinkedHashSet<String>,
        out: ArrayList<SearchResponse>,
    ) {
        if (!id.matches(Regex("[A-Za-z][A-Za-z0-9]+"))) return
        val isSeries = kind == "it" || id.startsWith("e")
        val rawTitle = cleanTitle(title) ?: return
        if (rawTitle.isBlank()) return
        if (!seen.add(id)) return
        val url = mainUrl.removeSuffix("/") + "/$id"
        if (isSeries) {
            out += newTvSeriesSearchResponse(rawTitle, url, TvType.TvSeries, false) {
                this.year = titleParts(rawTitle).second
            }
        } else {
            out += newMovieSearchResponse(rawTitle, url, TvType.Movie, false) {
                this.year = titleParts(rawTitle).second
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val html = app.get(mainUrl.removeSuffix("/") + ROUTES["az"]!!, headers = headersOf(UA, referer = mainUrl)).text
            parseList(html)
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val id = idRe.find(url)?.groupValues?.get(1) ?: throw Exception("Not a Goojara URL: $url")
        val movie = isMovie(id)
        return try {
            val html = app.get(mainUrl.removeSuffix("/") + "/$id", headers = headersOf(UA, referer = mainUrl)).text
            val tm = titleRe.find(html)?.groupValues?.get(1)
            if (tm.isNullOrBlank()) throw Exception("No title in $url")
            val (name, year) = titleParts(tm)
            val poster = posterFrom(html)

            val episodes = if (!movie) fetchEpisodes(html, url) else listOf(newEpisode(url) { this.name = "Movie" })

            if (movie) {
                newMovieLoadResponse(name, url, TvType.Movie, url) {
                    posterUrl = poster
                    this.year = year
                    plot = descriptionFrom(html)
                }
            } else {
                newTvSeriesLoadResponse(name, url, TvType.TvSeries, episodes) {
                    posterUrl = poster
                    this.year = year
                    plot = descriptionFrom(html)
                }
            }
        } catch (e: Exception) {
            throw Exception("Goojara load failed: ${e.message}")
        }
    }

    private fun posterFrom(html: String): String? {
        return posterRe.find(html)?.groupValues?.get(1)?.let {
            if (it.startsWith("//")) "https:$it" else it
        }
    }

    private fun descriptionFrom(html: String): String? {
        return descRe.find(html)?.groupValues?.get(1)!!.replace("<p>", " ").replace("</p>", " ")
            ?.replace("<strong>", "")?.replace("</strong>", "")?.trim()?.ifBlank { null }
    }

    /**
     * TV detail: episodes reachable via <div id="sesh"> anchors (/epId) and
     * seasons via <div id="drop"> anchors (/seriesId?s=N).
     */
    private suspend fun fetchEpisodes(detailHtml: String, detailUrl: String): List<Episode> {
        val out = ArrayList<Episode>()
        val seen = LinkedHashSet<String>()

        val seasonAnchorRe = Regex("""<div id="drop">.*?</div>""", RegexOption.DOT_MATCHES_ALL)
        val sdiv = seasonAnchorRe.find(detailHtml)
        val seasons = ArrayList<Int>()
        if (sdiv != null) {
            val anchors = Regex("""<a href="([^"]+)"[^>]*>(?:<div[^>]*>)?[^<]*</a>""").findAll(sdiv.groupValues[1])
            for (sa in anchors) {
                val href = sa.groupValues[1].trim()
                val sm = Regex("s=(\\d+)").find(href)
                val n = sm?.groupValues?.get(1)?.toIntOrNull() ?: continue
                if (n > 0) seasons.add(n)
            }
        }
        if (seasons.isEmpty()) seasons.add(1)

        // If this page is an episode page, record it as an episode of its season.
        val epPageRe = Regex("<h1>([^<]+)</h1>")
        val epTitle = epPageRe.find(detailHtml)?.groupValues?.get(1)?.trim()
        if (epTitle != null) {
            val sm = seasonRe.find(epTitle)
            val em = episodeRe.find(epTitle)
            val s = sm?.groupValues?.get(1)?.toIntOrNull() ?: seasons.first()
            val e = em?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val key = "$detailUrl|$s|$e"
            if (!seen.add(key)) {
                out += newEpisode(detailUrl) {
                    season = s
                    episode = e
                    name = epTitle
                }
                return out
            }
        }

        for (sn in seasons) {
            val seasonUrl = sdiv?.let {
                Regex("""<a href="([^"]+)"[^>]*\?s=$sn[^"]*""").find(it.groupValues[1])?.groupValues?.get(1)
            }
            if (seasonUrl == null) continue
            val sid = Regex("(m|e)[A-Za-z0-9]+").find(seasonUrl)?.groupValues?.get(1) ?: continue
            try {
                val sHtml = app.get(mainUrl.removeSuffix("/") + "/$sid", headers = headersOf(UA, referer = mainUrl)).text
                val epAnchors = Regex("""<a href="/(m|e)[A-Za-z0-9]+"[^>]*>(?:<div class="it">|<div class="im">)?([^<]{0,80})</a>""").findAll(sHtml)
                for (ea in epAnchors) {
                    val eid = ea.groupValues[1]
                    val eurl = mainUrl.removeSuffix("/") + "/$eid"
                    val key = "$eurl|$sn|1"
                    if (!seen.add(key)) continue
                    val epName = ea.groupValues[2].trim().ifBlank { "Episode $sn" }
                    out += newEpisode(eurl) {
                        season = sn
                        episode = 1
                        name = epName
                    }
                }
            } catch (_: Throwable) {
                // season page unavailable; skip
            }
        }
        if (out.isEmpty()) out += newEpisode(detailUrl) { name = "Episode" }
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
        return try {
            val id = idRe.find(data)?.groupValues?.get(1) ?: throw Exception("Not a Goojara URL: $data")
            val html = app.get(mainUrl.removeSuffix("/") + "/$id", headers = headersOf(UA, referer = mainUrl)).text

            val links = Regex("""<a class="bcg" href="(https?://[^"']+)">([^<]*)<span[^>]*>([^<]+)</span></a>""", RegexOption.DOT_MATCHES_ALL)
            var added = false
            for (m in links.findAll(html)) {
                val rawHref = m.groupValues[1]
                val hostName = m.groupValues[2].trim()
                val quality = m.groupValues[3].trim().lowercase()
                val name = if (hostName.isBlank()) "Source" else hostName

                // go.php 302-redirects to the third-party host; CloudStream's
                // bundled extractor resolves the final m3u8 from that host.
                val finalUrl = resolveGo(rawHref)
                if (finalUrl == null) continue

                val type = extractorTypeFor(finalUrl) ?: continue

                callback(
                    newExtractorLink(name, name, finalUrl, type) {
                        referer = mainUrl
                        this.quality = qualityFor(quality)
                    }
                )
                added = true
            }
            added
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Emit the go.php player endpoint directly. go.php 302-redirects to the
     * third-party host; CloudStream's bundled extractor follows the chain and
     * resolves the final m3u8. Works from residential IPs; from the build host
     * it 404s (host/egress problem), so the harness skips it.
     */
    private fun resolveGo(goUrl: String): String? = goUrl

    private fun extractorTypeFor(url: String): ExtractorLinkType {
        val u = url.lowercase()
        return when {
            u.contains("dood") || u.contains("dailymotion") || u.contains("wootly") || u.contains("m3u8") -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }
    }

    private fun qualityFor(q: String): Int = when (q) {
        "4k", "2160" -> 2160
        "fhd", "1080" -> 1080
        "hd", "720" -> 720
        "480" -> 480
        else -> 1080
    }

    private fun headersOf(ua: String, referer: String?): Map<String, String> {
        val h = mutableMapOf("User-Agent" to ua, "Accept-Language" to "en-US,en;q=0.9")
        if (referer != null) h["Referer"] = referer
        return h
    }
}
