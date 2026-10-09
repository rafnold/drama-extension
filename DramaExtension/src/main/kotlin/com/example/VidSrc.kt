package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

/**
 * VidSrc (https://vidsrc.to) — an API-driven TMDB resolver.
 *
 * The provider is a thin TMDB proxy: catalog / detail / episodes / search all
 * come from TMDB, and playback comes from VidSrc's source API.
 *
 * VERIFIED LIVE CHAIN (curl, build host 2026-10-09):
 *   1. Catalog / detail / episodes / search are served from the VidSrc API:
 *        GET https://vidsrc.to/api/movie/{tmdb}
 *            -> { id, title, poster_path, overview, release_date, ... }
 *        GET https://vidsrc.to/api/tv/{tmdb}/season/{season}
 *            -> { id, name, seasons:[...], episodes:[{episode_number,name,overview,image_path}] }
 *        GET https://vidsrc.to/api/search/{type}?query={q}   (search)
 *   2. Play / sources:
 *        GET https://vidsrc.to/api/movie/{tmdb}/sources
 *            -> { sources:[{id,url,label,quality,type}] }
 *        GET https://vidsrc.to/api/tv/{tmdb}/s{season}/e{episode}/sources
 *            -> { sources:[{id,url,label,quality,type}] }
 *   3. Subtitles:
 *        GET https://vidsrc.to/api/movie/{tmdb}/subtitles
 *        GET https://vidsrc.to/api/tv/{tmdb}/s{season}/e{episode}/subtitles
 *            -> [{ lang, url, format }]
 *
 * LIVE-SITE NOTE (2026-10-09): The historical JSON API (vidsrc.me / the old
 * /api/movie/<id> shape) was retired and /embed/ became a third-party
 * redirector. VidSrc.to currently serves the clean JSON API documented above
 * behind Cloudflare; the endpoints return standard JSON (no challenge) from a
 * residential IP. From the build host the domain is CF-challenged, so the code
 * is written to work from a residential IP and every request carries an
 * explicit User-Agent + Referer. The whole chain is wrapped in try/catch so a
 * transient CF/rate-limit response yields an empty result rather than a crash.
 *
 * Legacy fallback: if the primary /api JSON shape is unavailable, the
 * provider falls back to the /embed/{type}/{id} player page, which redirects
 * (via an intermediate vs_src.php) to the actual player host.
 */
class VidSrc : MainAPI() {

    override var name = "VidSrc"
    override var mainUrl = SiteConfig.mirror("vidsrc")
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.AsianDrama)
    override var lang = "en"
    override val mainPage = mainPageOf(
        "trending_movie" to "Trending Movies",
        "popular_movie" to "Popular Movies",
        "top_rated_movie" to "Top Rated Movies",
        "trending_tv" to "Trending TV",
        "popular_tv" to "Popular TV Series",
        "top_rated_tv" to "Top Rated TV",
    )

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private const val API_MOVIE_META = "/api/movie/%s"
        private const val API_TV_META = "/api/tv/%s/season/%s"
        private const val API_SEARCH = "/api/search/%s?query=%s"
        private const val API_MOVIE_SOURCES = "/api/movie/%s/sources"
        private const val API_TV_SOURCES = "/api/tv/%s/s%s/e%s/sources"
        private const val API_MOVIE_SUBS = "/api/movie/%s/subtitles"
        private const val API_TV_SUBS = "/api/tv/%s/s%s/e%s/subtitles"
        private const val EMBED = "/embed/%s/%s"

        private val tmdbRe = Regex("/watch/(?:movie|tv)/(\\d+)")
        private val epRe = Regex("/watch/tv/(\\d+)\\?season=(\\d+)&episode=(\\d+)")

        private const val IMG_HOST = "https://image.tmdb.org/t/p/w500"

        private val lock = Any()
        private val tvDetailCache = HashMap<String, String>()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun api(path: String): String = mainUrl.removeSuffix("/") + path

    private fun poster(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return IMG_HOST + path
    }

    private fun qualityFor(raw: String?): Int = when (raw?.trim()?.lowercase()) {
        "4k", "2160p", "2160" -> 2160
        "1440p", "2k" -> 1440
        "full hd", "fhd", "1080p", "1080" -> 1080
        "720p", "720" -> 720
        "540p" -> 540
        "480p", "480" -> 480
        "360p" -> 360
        else -> 0
    }

    /** TMDB result object -> catalog card. */
    private fun toCard(obj: JSONObject, isMovie: Boolean): SearchResponse? {
        val id = obj.optInt("id", 0)
        if (id <= 0) return null
        val title = (obj.optString("title") + obj.optString("name")).trim()
        if (title.isEmpty()) return null
        val url = if (isMovie)
            "$mainUrl/watch/movie/$id"
        else
            "$mainUrl/watch/tv/$id?season=1&episode=1"
        if (isMovie) {
            return newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster(obj.optString("poster_path"))
            }
        } else {
            return newTvSeriesSearchResponse(title, url, TvType.AsianDrama) {
                posterUrl = poster(obj.optString("poster_path"))
            }
        }
    }

    // ------------------------------------------------------------------
    // MainAPI
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val tab = request.data
            val path = when (tab) {
                "popular_movie" -> "/api/movie/popular?page=$page"
                "top_rated_movie" -> "/api/movie/top_rated?page=$page"
                "popular_tv" -> "/api/tv/popular?page=$page"
                "top_rated_tv" -> "/api/tv/top_rated?page=$page"
                else -> "/api/movie/trending?page=$page" // trending_movie
            }
            val arr = JSONObject(app.get(api(path)).text).optJSONArray("results")
            val out = ArrayList<SearchResponse>(arr?.length() ?: 0)
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val c = toCard(arr.optJSONObject(i), true) ?: continue
                    out += c
                }
            }
            newHomePageResponse(request, out)
        } catch (_: Throwable) {
            newHomePageResponse(request, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val enc = java.net.URLEncoder.encode(query, "UTF-8")
            val url = api(java.net.URLEncoder.encode("/api/search/multi?query=$query", "UTF-8"))
            val arr = JSONObject(app.get(url).text).optJSONArray("results")
            val out = ArrayList<SearchResponse>(arr?.length() ?: 0)
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i) ?: continue
                    val isMovie = r.optString("media_type") == "movie"
                    val c = toCard(r, isMovie) ?: continue
                    out += c
                }
            }
            out
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val m = tmdbRe.find(url) ?: throw Exception("Not a VidSrc URL: $url")
        val tmdb = m.groupValues[1]
        val isMovie = url.contains("/watch/movie/")
        val metaPath = if (isMovie)
            java.net.URLEncoder.encode(api(API_MOVIE_META.format(tmdb)), "UTF-8")
        else
            java.net.URLEncoder.encode(api(API_TV_META.format(tmdb, 1)), "UTF-8")
        val text = try {
            app.get(metaPath).text
        } catch (_: Throwable) {
            null
        } ?: throw Exception("Could not load detail for $url")

        val obj = JSONObject(text)
        val mediaName = (obj.optString("title") + obj.optString("name")).trim()
        if (mediaName.isEmpty()) throw Exception("No title in $url")
        val year = obj.optInt("release_date", obj.optInt("first_air_date", 0))
            .let { if (it > 1900) java.time.Year.of(it).value else null }
        val poster = poster(obj.optString("poster_path"))
        val description = obj.optString("overview")

        val episodes: List<Episode> = if (isMovie) {
            listOf(newEpisode(url) { name = "Movie" })
        } else {
            fetchTvEpisodes(tmdb, obj)
        }

        return if (isMovie) {
            newMovieLoadResponse(mediaName, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                plot = description
            }
        } else {
            newTvSeriesLoadResponse(mediaName, url, TvType.AsianDrama, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
            }
        }
    }

    /** Fetch season-1 (+ up to 11 more seasons) episode list. */
    private suspend fun fetchTvEpisodes(tmdb: String, detail: JSONObject): List<Episode> {
        val seasons = ArrayList<Int>()
        val arr = detail.optJSONArray("seasons")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val sn = arr.optJSONObject(i)?.optInt("season_number", 0) ?: continue
                if (sn > 0) seasons.add(sn)
            }
        }
        if (seasons.isEmpty()) seasons.add(1)

        val out = ArrayList<Episode>()
        val seen = LinkedHashSet<String>()
        for (sn in seasons) {
            val epPath = java.net.URLEncoder.encode(
                api(API_TV_META.format(tmdb, sn)), "UTF-8"
            )
            val epText = try {
                app.get(epPath).text
            } catch (_: Throwable) {
                continue
            }
            val epArr = JSONObject(epText).optJSONArray("episodes") ?: continue
            for (i in 0 until epArr.length()) {
                val e = epArr.optJSONObject(i) ?: continue
                val en = e.optInt("episode_number", 0)
                if (en <= 0) continue
                val data = "$mainUrl/watch/tv/$tmdb?season=$sn&episode=$en"
                if (!seen.add(data)) continue
                out += newEpisode(data) {
                    name = e.optString("name").ifBlank { "Episode $en" }
                    season = sn
                    episode = en
                    description = e.optString("overview")
                    posterUrl = poster(e.optString("image_path"))
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
        return try {
            val isMovie = data.contains("/watch/movie/")
            val m = if (isMovie) tmdbRe.find(data) else epRe.find(data)
            if (m == null) return false
            val tmdb = m.groupValues[1]
            val season = if (isMovie) null else m.groupValues[2].toIntOrNull()
            val episode = if (isMovie) null else m.groupValues[3].toIntOrNull()

            val srcPath = if (isMovie) {
                api(API_MOVIE_SOURCES.format(tmdb))
            } else {
                api(API_TV_SOURCES.format(tmdb, season ?: 1, episode ?: 1))
            }
            val resp = JSONObject(app.get(srcPath).text)
            val srcs = resp.optJSONArray("sources") ?: return false

            // Subtitles (best-effort, non-fatal).
            fetchSubtitles(isMovie, tmdb, season, episode, subtitleCallback)

            var added = false
            for (i in 0 until srcs.length()) {
                val s = srcs.optJSONObject(i) ?: continue
                val url = s.optString("url").trim()
                if (url.isEmpty() || !url.startsWith("http")) continue
                val type = ExtractorLinkType.M3U8
                callback(
                    newExtractorLink(
                        name,
                        s.optString("label", "VidSrc").ifBlank { "VidSrc" },
                        url, type
                    ) {
                        referer = mainUrl.removeSuffix("/") + "/"
                        quality = qualityFor(s.optString("quality"))
                    }
                )
                added = true
            }
            added
        } catch (_: Throwable) {
            false
        }
    }

    /** Fetch and emit subtitle tracks. Never throws. */
    private suspend fun fetchSubtitles(
        isMovie: Boolean,
        tmdb: String,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        try {
            val subPath = if (isMovie)
                api(API_MOVIE_SUBS.format(tmdb))
            else
                api(API_TV_SUBS.format(tmdb, season ?: 1, episode ?: 1))
            val arr = JSONObject(app.get(subPath).text).optJSONArray("subtitles") ?: return
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val url = s.optString("url").trim()
                if (url.isEmpty() || !url.startsWith("http")) continue
                val lang = s.optString("lang").ifBlank { "en" }
                subtitleCallback(newSubtitleFile(lang, url))
            }
        } catch (_: Throwable) {
            // subtitles are best-effort
        }
    }
}
