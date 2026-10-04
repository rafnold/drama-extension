package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONArray
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
    override var mainUrl = SiteConfig.mirror("kisskh")
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie)
    override var lang = "en"
    override val mainPage = mainPageOf(
        "all" to "All",
        "drama" to "Dramas",
        "movie" to "Movies",
        "fantasy" to "Fantasy",
        "historical" to "Historical",
        "romance" to "Romance",
        "action" to "Action",
        "scifi" to "Sci-Fi",
        "thriller" to "Thriller",
    )

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        // Server-side genre catalogs (/genres/<slug>/, paginated /page/N/).
        // Same a.tip card grid as the /series/ listing, so toCards() applies.
        // /series/?genre=<g> returns HTTP 500 — the /genres/ paths are the
        // only working genre endpoint (verified 2026-10-01).
        private val genrePaths = mapOf(
            "fantasy" to "genres/fantasy",
            "historical" to "genres/historical",
            "romance" to "genres/romance",
            "action" to "genres/action",
            "scifi" to "genres/sci-fi",
            "thriller" to "genres/thriller",
        )

        private val yearRe = Regex("(19\\d{2}|20\\d{2})")

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
        val p = page.coerceAtLeast(1)
        val genre = genrePaths[request.data]
        val url = if (genre != null) {
            if (p > 1) "$mainUrl/$genre/page/$p/" else "$mainUrl/$genre/"
        } else {
            "$mainUrl/series/"
        }
        // UG-5 dead tier: over-run listing pages are not re-tried for 1 h.
        if (Cache.isDead(url)) return newHomePageResponse(request, emptyList())
        return try {
            val doc = if (genre != null) {
                app.get(url).document
            } else {
                app.get(url, params = buildMap {
                    if (p > 1) put("page", p.toString())
                    if (request.data != "all") put("type", request.data)
                }).document
            }
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
        // UG-5: re-opened detail pages are served from the episode cache.
        Cache.episodes.get(url)?.let { return it }
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
            val resp = newMovieLoadResponse(nm, url, TvType.Movie, watchUrl) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                if (tags.isNotEmpty()) this.tags = tags
            }
            Cache.episodes.put(url, resp)
            return resp
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
            // UG-5: cached vaults mean re-opened episodes make no page fetch.
            var vault = Cache.lists.get(data)?.firstOrNull()
            if (vault == null) {
                val doc = app.get(data, headers = mapOf("User-Agent" to UA)).document
                vault = doc.selectFirst("[data-matrix-vault]")?.attr("data-matrix-vault")
                    ?: return false
                Cache.lists.put(data, listOf(vault))
            }
            val servers = parseServers(ResolverCrypto.b64(vault))
            if (servers.isEmpty()) return false

            // UG-4: every working server resolves in parallel under one
            // ~20 s budget; dead and slow servers drop out silently.
            val ctx = ResolveContext(name, data)
            val results = Resolvers.resolveAll(
                ctx,
                ResolveContext.TOTAL_BUDGET_MS,
                servers.map { EmbedTask(it.second, it.first) },
            )

            // Global dedupe across servers: subtitles by URL, links by URL.
            val seenSubs = LinkedHashSet<String>()
            val deduped = results.map { (_, res) ->
                res.copy(subtitles = res.subtitles.filter { seenSubs.add(it.url) })
            }
            val seenUrls = LinkedHashSet<String>()
            var added = false
            for (res in deduped) {
                if (!res.ok) continue
                val fresh = res.sources.filter { seenUrls.add(it.url) }
                if (fresh.isEmpty()) continue
                added =
                    emitResult(res.copy(ok = true, sources = fresh), name, UA, subtitleCallback, callback) ||
                    added
            }
            added
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
            val inner = ResolverCrypto.b64(o.optString("vault"))
            val src = Regex("<iframe[^>]*\\ssrc=['\"]([^'\"]*)['\"]").find(inner)
                ?.groupValues?.get(1)
                ?: Regex("src=['\"]([^'\"]*)['\"]").find(inner)?.groupValues?.get(1)
            if (src.isNullOrBlank()) continue
            val u = abs(src) ?: src
            out += nm to u
        }
        return out
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
