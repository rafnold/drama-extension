package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.util.Base64

/**
 * Primeshows.org (https://primeshows.org) - a Next.js catalog that proxies
 * TMDB and streams via the wecollege / vidy.st source stack.
 *
 * Catalog / detail / episodes / search all come from the site's TMDB proxy:
 *   https://primeshows.org/api/proxy/tmdb?endpoint=<url-encoded TMDB path>
 * e.g. `/trending/movie/week`, `/tv/1402/season/1`, `/search/multi?query=...`.
 *
 * Playback: the watch route `/watch/{movie|tv}/{tmdbId}` carries everything
 * the resolver needs. Sources come from wecollege:
 *   1. GET https://api.wecollege.net/seed?mediaId=<tmdbId> -> {seed}
 *   2. GET https://api.wecollege.net/miami/sources?...&enc=2&seed=<seed>
 *        -> base64 ciphertext (non-deterministic per request)
 *   3. decrypt(ct, seed, tmdbId) -> JSON {"sources":[{quality,url},...]}
 * The decrypted URLs are HLS playlists served from a CDN that requires the
 * Referer `https://www.vidy.st/`, so every emitted link carries it.
 *
 * The decrypt (below) is a byte-exact Kotlin port of the site's official
 * algorithm: a KSA over a 61-entry S-array seeded by splitmix64, then a
 * rotl-based PRNG. Verified live against a live ciphertext.
 */
class Primeshows : MainAPI() {

    override var name = "Primeshows"
    override var mainUrl = SiteConfig.mirror("primeshows")
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.AsianDrama)
    override var lang = "en"
    override val mainPage = mainPageOf(
        "trending_all" to "Trending",
        "movies" to "Movies",
        "tv" to "TV Series",
        "top_rated" to "Top Rated",
        "action" to "Action",
        "sci_fi" to "Sci-Fi",
    )

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private const val PROXY = "/api/proxy/tmdb"
        private const val SEED_HOST = "https://api.wecollege.net"
        private const val MIAMI = "https://api.wecollege.net/miami/sources"
        private const val VIDY_REFER = "https://www.vidy.st/"

        // Watch route: /watch/{movie|tv}/{tmdbId} optional ?season=&episode=
        private val watchRe = Regex("/watch/(?:movie|tv)/(\\d+)(?:\\?season=(\\d+)&episode=(\\d+))?")

        private const val CACHE_MAX = 512
        private val detailCache = HashMap<String, String>() // tmdbId -> JSON (TV detail)
        private val lock = Any()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun proxyUrl(endpoint: String, page: Int = 0): String {
        var enc = java.net.URLEncoder.encode(endpoint, "UTF-8")
        if (page > 0) enc = if (endpoint.contains("?")) "$enc&page=$page" else "$enc&page=$page"
        return mainUrl.removeSuffix("/") + PROXY + "?endpoint=$enc&language=en-US"
    }

    /** Append ?page=N (TMDB discovery/trending endpoints accept it). */
    private fun withPage(endpoint: String, page: Int): String {
        val sep = if (endpoint.contains("?")) "&" else "?"
        return "$endpoint${sep}page=${page.coerceAtLeast(1)}"
    }

    private fun poster(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return "https://${java.net.URI(mainUrl).host}/tmdb/image/w500$path"
    }

    /** Map a quality/resolution label to a raw CloudStream quality int
     *  (matches the resolver convention: 2160/1440/1080/720/480/0=auto). */
    private fun qualityFor(raw: String): Int = when (raw.trim().lowercase()) {
        "4k", "2160p", "2160" -> 2160
        "1440p", "2k" -> 1440
        "full hd", "fhd", "1080p", "1080" -> 1080
        "720p", "720" -> 720
        "540p" -> 540
        "480p", "480" -> 480
        "360p" -> 360
        else -> 0
    }

    /** TMDB results array -> catalog cards. [mediaType] forces the type. */
    private fun parseResults(obj: JSONObject, preferType: String?): List<SearchResponse> {
        val arr = obj.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<SearchResponse>(arr.length())
        val seen = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val id = r.optInt("id", 0)
            if (id <= 0) continue
            val title = (r.optString("title") + r.optString("name")).trim()
            if (title.isEmpty()) continue
            val mediaType = r.optString("media_type").ifBlank { preferType ?: "movie" }
            val isMovie = mediaType == "movie"
            val watchUrl = if (isMovie)
                "$mainUrl/watch/movie/$id" else "$mainUrl/watch/tv/$id"
            if (!seen.add(watchUrl)) continue
            val poster = poster(r.optString("poster_path"))
            if (isMovie) {
                out += newMovieSearchResponse(title, watchUrl, TvType.Movie) {
                    posterUrl = poster
                }
            } else {
                out += newTvSeriesSearchResponse(title, watchUrl, TvType.AsianDrama) {
                    posterUrl = poster
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // MainAPI
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val endpoint = when (request.data) {
            "movies" -> "/movie/popular"
            "tv" -> "/tv/popular"
            "top_rated" -> "/movie/top_rated"
            "action" -> "/discover/movie?with_genres=28&sort_by=popularity"
            "sci_fi" -> "/discover/tv?with_genres=10765&sort_by=popularity"
            else -> "/trending/all/week" // trending_all
        }
        return try {
            val res = app.get(proxyUrl(withPage(endpoint, page)))
            newHomePageResponse(request, parseResults(JSONObject(res.text), null))
        } catch (_: Throwable) {
            newHomePageResponse(request, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val enc = java.net.URLEncoder.encode("/search/multi?query=$query", "UTF-8")
            val url = mainUrl.removeSuffix("/") + PROXY + "?endpoint=$enc&language=en-US"
            parseResults(JSONObject(app.get(url).text), null)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val m = watchRe.find(url) ?: throw Exception("Not a Primeshows watch URL: $url")
        val tmdb = m.groupValues[1]
        val isMovie = url.contains("/watch/movie/")
        val text = try {
            val endpoint = if (isMovie) "/movie/$tmdb" else "/tv/$tmdb"
            val u = mainUrl.removeSuffix("/") + PROXY + "?endpoint=${java.net.URLEncoder.encode(endpoint, "UTF-8")}&language=en-US"
            app.get(u).text
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

    /** Season 1 (+ other seasons under a 12 s budget) episode list. */
    private suspend fun fetchTvEpisodes(tmdb: String, detail: JSONObject): List<Episode> {
        val seasons = detail.optJSONArray("seasons")
        val seasonNumbers = ArrayList<Int>()
        if (seasons != null) {
            for (i in 0 until seasons.length()) {
                val sn = seasons.optJSONObject(i)?.optInt("season_number", 0) ?: continue
                if (sn > 0) seasonNumbers.add(sn)
            }
        }
        if (seasonNumbers.isEmpty()) seasonNumbers.add(1)

        val out = ArrayList<Episode>()
        val seen = LinkedHashSet<String>()
        val deadline = System.currentTimeMillis() + 12_000
        for (sn in seasonNumbers) {
            if (System.currentTimeMillis() > deadline) break
            val epText = try {
                val u = mainUrl.removeSuffix("/") + PROXY +
                    "?endpoint=${java.net.URLEncoder.encode("/tv/$tmdb/season/$sn", "UTF-8")}&language=en-US"
                app.get(u).text
            } catch (_: Throwable) {
                null
            } ?: continue
            val arr = JSONObject(epText).optJSONArray("episodes") ?: continue
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
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
            val m = watchRe.find(data) ?: return false
            val tmdb = m.groupValues[1].toLong()
            val isMovie = data.contains("/watch/movie/")
            val season = m.groupValues[2].toIntOrNull()
            val episode = m.groupValues[3].toIntOrNull()

            // 1. seed
            val seedObj = app.get(
                "$SEED_HOST/seed",
                params = mapOf("mediaId" to tmdb.toString()),
            ).text
            val seed = JSONObject(seedObj).optString("seed")
            if (seed.isBlank()) return false

            // 2. sources (ciphertext)
            val params = LinkedHashMap<String, String>()
            params["mediaType"] = if (isMovie) "movie" else "tv"
            params["tmdbId"] = tmdb.toString()
            params["enc"] = "2"
            params["seed"] = seed
            if (!isMovie) {
                if (season != null) params["s"] = season.toString()
                if (episode != null) params["e"] = episode.toString()
            }
            val ct = app.get(MIAMI, params = params).text
            if (ct.isBlank()) return false

            // 3. decrypt -> JSON
            val json = VidyDecrypt.decrypt(ct, seed, tmdb)
            val srcObj = JSONObject(json)
            val srcs = srcObj.optJSONArray("sources") ?: return false
            var added = false
            for (i in 0 until srcs.length()) {
                val s = srcs.optJSONObject(i) ?: continue
                val url = s.optString("url").trim()
                if (url.isEmpty() || !url.startsWith("http")) continue
                val q = qualityFor(s.optString("quality").ifBlank { s.optString("resolution") })
                callback(
                    newExtractorLink(name, s.optString("label", "Source").ifBlank { "Source" },
                        url, ExtractorLinkType.M3U8) {
                        referer = VIDY_REFER
                        quality = q
                    }
                )
                added = true
            }
            added
        } catch (_: Throwable) {
            false
        }
    }
}
