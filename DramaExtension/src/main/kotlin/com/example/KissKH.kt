package com.example

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.select.Elements

/**
 * KissKH (kisskh.or.at)
 *
 * WordPress "dramastream" theme. Catalog, search, series (multi-episode) and movies.
 *  - Series episodes resolve via a two-hop chain:
 *      detail -> episode "watch" page -> data-matrix-vault (base64 JSON) ->
 *      inner base64 iframe -> kisskh.megaplay.su embed -> #player-payload JSON
 *      { source: <m3u8>, tracks: [{file:.srt,label}] }.
 *  - Movies resolve from the watch page's data-matrix-vault servers:
 *      * moviesapi.to  -> vidora API (needs x-player-key + Referer/Origin)
 *      * vidmoly.biz   -> m3u8 is embedded directly in the embed page HTML
 *      * videasy/vidlink -> skipped (origin down / wasm-encrypted)
 *
 * All m3u8 sources are emitted as direct M3U8 links with the required Referer,
 * so no extractor dependency is needed.
 */
class KissKH : MainAPI() {
    override var name = "KissKH"
    override var mainUrl = "https://kisskh.or.at"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie)
    override var lang = "en"
    override val mainPage = mainPageOf(
        "all" to "All",
        "drama" to "Dramas",
        "movie" to "Movies",
    )

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        // Megaplay (series episodes) requires a root referer from its own origin.
        private const val MEGA_REFERER = "https://kisskh.megaplay.su/"

        // Moviesapi.to (vidora) API gate: x-player-key + Referer + Origin.
        private const val MAPI_ORIGIN = "https://moviesapi.to"
        private const val MAPI_REFERER = "https://moviesapi.to/"
        private const val MAPI_KEY =
            "3a67e8866ae1d2bb9e81fe7f73315a56eb3bdf5e3e755c7554c8be6910aa6b13"

        private val moviesApiRe = Regex("moviesapi\\.to/movie/(\\d+)")
        // Greedy trailing class so the full query string is captured, stopping at a quote.
        private val m3u8Re = Regex("(https?://[^\"'\\s<>]*\\.m3u8[^\"'\\s<>]*)")
        private val yearRe = Regex("(19\\d{2}|20\\d{2})")

        /** Lenient base64 -> UTF-8 string. Tolerates missing padding and whitespace. */
        private fun b64(s: String): String {
            if (s.isBlank()) return ""
            val clean = s.filterNot { it.isWhitespace() }
            val padded = clean + when (clean.length % 4) {
                2 -> "=="
                3 -> "="
                else -> ""
            }
            return try {
                String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Throwable) {
                ""
            }
        }
    }

    // ------------------------------------------------------------------
    // URL helper
    // ------------------------------------------------------------------
    private fun abs(href: String?): String? {
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

    private fun isMovie(classNames: List<String>): Boolean =
        classNames.any { it.equals("Movie", ignoreCase = true) }

    private fun guessLang(url: String): String {
        val base = url.substringAfterLast('/').substringBeforeLast('.')
        val codes = Regex("\\b[a-zA-Z]{2,3}\\b").findAll(base).map { it.value }
        return codes.lastOrNull() ?: "en"
    }

    // ------------------------------------------------------------------
    // Card parsing
    // ------------------------------------------------------------------
    private fun Document.toCards(): List<SearchResponse> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<SearchResponse>()
        for (a in select("a.tip[href]")) {
            val url = abs(a.attr("href")) ?: continue
            if (!seen.add(url)) continue
            val nm = a.attr("title")
                .ifBlank { a.selectFirst("h2")?.text() ?: "" }
                .trim()
            if (nm.isBlank()) continue
            val poster = a.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") }
            val movie = isMovie(a.selectFirst("div.typez")?.classNames()?.toList() ?: emptyList())
            out += if (movie) {
                newMovieSearchResponse(nm, url, TvType.Movie) { posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(nm, url, TvType.AsianDrama) { posterUrl = poster }
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // MainAPI
    // ------------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val params = mutableMapOf<String, String>()
            val p = page.coerceAtLeast(1)
            if (p > 1) params["page"] = p.toString()
            if (request.data != "all") params["type"] = request.data
            val doc = app.get("$mainUrl/series/", params = params).document
            newHomePageResponse(request, doc.toCards())
        } catch (_: Throwable) {
            newHomePageResponse(request, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val doc = app.get("$mainUrl/series/", params = mapOf("s" to query)).document
            doc.toCards()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val nm = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: throw Exception("Could not parse title from $url")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img.ts-post-image")?.attr("src")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
        val info = infoFields(doc)
        val year = yearRe.find(nm)?.value?.toIntOrNull()
            ?: info.values.firstNotNullOfOrNull { yearRe.find(it)?.value?.toIntOrNull() }
        val tags = doc.select(".genxed a").map { it.text().trim() }.filter { it.isNotBlank() }

        // Movie? (detail page has a "Watch" trigger, no episode list)
        val watchUrl = doc.selectFirst("a.watch-movie-trigger")?.attr("href")?.let { abs(it) }
        if (watchUrl != null) {
            return newMovieLoadResponse(nm, url, TvType.Movie, watchUrl) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                if (tags.isNotEmpty()) this.tags = tags
            }
        }

        // Series
        val eps = mutableListOf<Episode>()
        for (a in doc.select("a.ep-item[href]")) {
            val eUrl = abs(a.attr("href")) ?: continue
            val num = a.attr("data-number").toIntOrNull() ?: (eps.size + 1)
            eps += newEpisode(eUrl) {
                name = "Episode $num"
                episode = num
                season = 1
            }
        }
        return newTvSeriesLoadResponse(nm, url, TvType.AsianDrama, eps) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            if (tags.isNotEmpty()) this.tags = tags
            val st = info["Status"]
            if (st != null) {
                if (st.equals("Ongoing", ignoreCase = true)) this.showStatus = ShowStatus.Ongoing
                else if (st.equals("Completed", ignoreCase = true)) this.showStatus = ShowStatus.Completed
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
            val doc = app.get(data, headers = mapOf("User-Agent" to UA)).document
            val vault = doc.selectFirst("[data-matrix-vault]")?.attr("data-matrix-vault")
                ?: return false
            val servers = parseServers(b64(vault))
            if (servers.isEmpty()) return false
            var any = false
            for ((srvName, iframe) in servers) {
                if (handleIframe(iframe, srvName, subtitleCallback, callback)) any = true
            }
            any
        } catch (_: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------------
    // data-matrix-vault -> [(server name, iframe url)]
    // ------------------------------------------------------------------
    private fun parseServers(json: String): List<Pair<String, String>> {
        val arr = try {
            JSONArray(json)
        } catch (_: Throwable) {
            null
        } ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val nm = o.optString("name").ifBlank { "Server ${i + 1}" }
            val inner = b64(o.optString("vault"))
            val src = Regex("<iframe[^>]*\\ssrc=['\"]([^'\"]*)['\"]").find(inner)
                ?.groupValues?.get(1)
                ?: Regex("src=['\"]([^'\"]*)['\"]").find(inner)?.groupValues?.get(1)
            if (src.isNullOrBlank()) continue
            val u = abs(src) ?: src
            out += nm to u
        }
        return out
    }

    private suspend fun handleIframe(
        iframe: String,
        srvName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return when {
            iframe.contains("kisskh.megaplay.su") ->
                resolveMegaplay(iframe, srvName, subtitleCallback, callback)

            moviesApiRe.containsMatchIn(iframe) ->
                resolveVidora(iframe, srvName, subtitleCallback, callback)

            iframe.contains("vidmoly.biz") ->
                resolveVidmoly(iframe, srvName, subtitleCallback, callback)

            // videasy (origin down) and vidlink (wasm-encrypted) are not usable.
            else -> false
        }
    }

    // ------------------------------------------------------------------
    // Megaplay (series episodes)
    // ------------------------------------------------------------------
    private suspend fun resolveMegaplay(
        iframe: String,
        srvName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val page = app.get(iframe, referer = MEGA_REFERER, headers = mapOf("User-Agent" to UA))
        val payload = page.document.select("script#player-payload").firstOrNull()?.data()
            ?: Regex("id=['\"]?player-payload['\"]?[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
                .find(page.text)?.groupValues?.get(1)
            ?: return false
        val j = try {
            JSONObject(payload)
        } catch (_: Throwable) {
            return false
        }
        val source = j.optString("source")
        if (!source.startsWith("http")) return false
        callback(newExtractorLink(name, srvName, source, ExtractorLinkType.M3U8) {
            referer = MEGA_REFERER
            quality = 0
            headers = mapOf("User-Agent" to UA)
        })
        val tracks = j.optJSONArray("tracks")
        if (tracks != null) {
            for (i in 0 until tracks.length()) {
                val t = tracks.optJSONObject(i) ?: continue
                val f = t.optString("file")
                if (!f.startsWith("http")) continue
                subtitleCallback(newSubtitleFile(langOf(t.optString("label"), f), f) {
                    headers = mapOf("User-Agent" to UA, "Referer" to MEGA_REFERER)
                })
            }
        }
        return true
    }

    // ------------------------------------------------------------------
    // Vidora (moviesapi.to)
    // ------------------------------------------------------------------
    private suspend fun resolveVidora(
        iframe: String,
        srvName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val tmdbId = moviesApiRe.find(iframe)?.groupValues?.get(1) ?: return false
        val resp = app.get(
            "https://moviesapi.to/api/vidora/v1/movie/$tmdbId",
            referer = "https://moviesapi.to/movie/$tmdbId?theme=8b5cf6",
            headers = mapOf(
                "User-Agent" to UA,
                "x-player-key" to MAPI_KEY,
                "Origin" to MAPI_ORIGIN,
            ),
        )
        if (!resp.isSuccessful) return false
        val j = try {
            JSONObject(resp.text)
        } catch (_: Throwable) {
            return false
        }
        if (!j.optBoolean("result", false)) return false
        val sources = j.optJSONArray("sources") ?: return false
        var any = false
        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            val m3u8 = s.optString("url")
            if (!m3u8.startsWith("http")) continue
            val label = s.optString("source").ifBlank { srvName }
            callback(newExtractorLink(name, label, m3u8, ExtractorLinkType.M3U8) {
                referer = MAPI_REFERER
                quality = 0
                headers = mapOf("User-Agent" to UA)
            })
            val tracks = s.optJSONArray("tracks")
            if (tracks != null) {
                for (k in 0 until tracks.length()) {
                    val t = tracks.optJSONObject(k) ?: continue
                    val f = t.optString("file")
                    if (!f.startsWith("http")) continue
                    subtitleCallback(newSubtitleFile(langOf(t.optString("label"), f), f) {
                        headers = mapOf("User-Agent" to UA)
                    })
                }
            }
            any = true
        }
        return any
    }

    // ------------------------------------------------------------------
    // Vidmoly (m3u8 embedded in the embed page HTML)
    // ------------------------------------------------------------------
    private suspend fun resolveVidmoly(
        iframe: String,
        srvName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val page = app.get(iframe, headers = mapOf("User-Agent" to UA)).text
        val m3u8 = m3u8Re.find(page)?.groupValues?.get(1)?.trimEnd('\'', '"', ';', ')')
            ?: return false
        if (!m3u8.startsWith("http")) return false
        callback(newExtractorLink(name, srvName, m3u8, ExtractorLinkType.M3U8) {
            referer = iframe
            quality = 0
            headers = mapOf("User-Agent" to UA)
        })
        return true
    }

    // ------------------------------------------------------------------
    // Metadata helpers
    // ------------------------------------------------------------------
    private fun langOf(label: String, file: String): String =
        if (label.isNotBlank()) label else guessLang(file)

    private fun infoFields(doc: Document): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val spans: Elements = doc.select(".spe > span")
        for (sp in spans) {
            val b = sp.selectFirst("b") ?: continue
            val label = b.text().trim().removeSuffix(":")
            if (label.isBlank()) continue
            val raw = sp.text().trim()
                .replace(Regex("^\\s*${Regex.escape(label)}\\s*:?\\s*"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (raw.isNotBlank()) out[label] = raw
        }
        return out
    }
}
