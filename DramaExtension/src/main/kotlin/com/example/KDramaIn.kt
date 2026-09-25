package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
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
 */
class KDramaIn : MainAPI() {

    override var name = "KDrama.in"
    override var mainUrl = "https://k-drama.in/"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie)
    override var lang = "en"
    override val mainPage = mainPageOf(
        "all" to "All",
        "kdrama" to "K-Dramas",
        "cdrama" to "C-Dramas",
        "jdrama" to "J-Dramas",
        "movies" to "Movies",
        "top_rated" to "Top Rated",
    )

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val VIDSYNC_API = "https://vidsync.pro/api/extraction/session"
        private val tvEpRe = Regex("id=(\\d+)&(amp;)?season=(\\d+)&(amp;)?episode=(\\d+)")
        private val movieRe = Regex("id=(\\d+)&(amp;)?type=movie")
        private val idRe = Regex("id=(\\d+)")
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun Document.toCards(): List<SearchResponse> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<SearchResponse>()
        for (a in select("a[href*=detail.php]")) {
            val url = a.attr("abs:href")
            if (!seen.add(url)) continue
            val nm = a.selectFirst("h3")?.text()?.trim() ?: continue
            if (nm.isBlank()) continue
            val isMovie = url.contains("type=movie")
            val poster = a.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") }
            out += if (isMovie) {
                newMovieSearchResponse(nm, url, TvType.Movie) {
                    posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(nm, url, TvType.AsianDrama) {
                    posterUrl = poster
                }
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
        return try {
            val url = "$mainUrl/dramas.php?type=${request.name}&page=${page.coerceAtLeast(1)}"
            val doc = app.get(url).document
            newHomePageResponse(request, doc.toCards())
        } catch (_: Throwable) {
            newHomePageResponse(request, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val doc = app.get(
                mainUrl + "dramas.php",
                params = mapOf("type" to "all", "q" to query),
            ).document
            doc.toCards()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
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
                list += newEpisode(fixUrl(href)) {
                    name = "Episode $e"
                    season = s
                    episode = e
                }
            }
            list.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            list
        }

        return if (isMovie) {
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

            val params = mutableMapOf(
                "type" to if (isMovie) "movie" else "tv",
                "id" to id,
            )
            if (!isMovie) {
                params["season"] = (season ?: 1).toString()
                params["episode"] = (episode ?: 1).toString()
            }
            val embedUrl = if (isMovie) {
                "https://vidsync.pro/embed/movie/$id/"
            } else {
                "https://vidsync.pro/embed/tv/$id/?season=${params["season"]}&episode=${params["episode"]}"
            }

            val text = app.get(VIDSYNC_API, params = params, referer = embedUrl).text
            val seen = LinkedHashSet<String>()
            var added = false
            for (line in text.lines()) {
                if (!line.contains("provider-result")) continue
                val provider = Regex("\"provider\"\\s*:\\s*\"([^\"]+)\"")
                    .find(line)?.groupValues?.get(1) ?: "server"
                for (obj in Regex("\\{[^{}]*\"rawUrl\"[^{}]*\\}").findAll(line)) {
                    val s = obj.value
                    val url = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(s)
                        ?.groupValues?.get(1)
                        ?: Regex("\"rawUrl\"\\s*:\\s*\"([^\"]+)\"").find(s)
                        ?.groupValues?.get(1)
                        ?: continue
                    if (!seen.add(url)) continue
                    val quality = Regex("\"quality\"\\s*:\\s*\"([^\"]+)\"").find(s)
                        ?.groupValues?.get(1)
                        ?: "auto"
                    val isHls = url.contains(".m3u8") || quality.contains("hls", true)
                    callback(
                        newExtractorLink(
                            name,
                            "$provider $quality",
                            url,
                            if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                        ) {
                            referer = embedUrl
                            this.quality = Regex("(\\d{2,4})p?").find(quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            headers = mapOf("User-Agent" to UA)
                        }
                    )
                    added = true
                }
            }
            added
        } catch (_: Throwable) {
            false
        }
    }
}
