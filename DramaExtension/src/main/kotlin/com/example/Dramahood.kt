package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document

/**
 * Dramahood (https://dramahood.mom) - Asian drama / KShow streaming.
 *
 * The site is a plain WordPress install ("asianmozi" theme) with
 * server-rendered catalog, search and detail pages. Every title is a
 * series (K-dramas, C-dramas, J-dramas, variety shows) with episodes;
 * there are no movie pages.
 *
 *   Catalog:  /category/<cat>/  (paginated /category/<cat>/page/N/,
 *            over-run pages 404, no homepage leakage)
 *   Search:   /?s=<query> (10 results, same card markup as the catalog)
 *   Note: the two "latest releases" categories list individual episode
 *         posts (<slug>-episode-N/); toCards() normalizes those back to
 *         the series page and de-duplicates repeated series.
 *   Series:   /<slug>/  -> <h1> title, <img class="poster">,
 *            info <p><strong>Label:</strong> <span>value</span></p>,
 *            plot in <div class="right"><div class="info"><p>...</p></div>,
 *            episodes in <ul class="list-episode"> (descending order)
 *   Episode:  /<slug>-episode-N/ -> <iframe> to one of three player hosts
 *            (or an empty player div for episodes not uploaded yet).
 *
 * Player chains (all verified live, 2026-09-29):
 *
 *  A. dramavideo.se/watch?v=<n>
 *     -> <li class="linkserver" data-video="<code>" data-provider="<sv>">
 *        (one or more servers per episode)
 *     -> https://player.dramavideo.se/?id=<code>&sv=<sv>  (host is built
 *        from base64 parts in player.js; falls back to the constant)
 *     -> inline script with encData=<b64>, keyHex, ivHex:
 *        AES-256-CBC (PKCS7, key/iv hex-encoded) decrypt -> injected HTML with
 *        const sources = JSON.parse(`[{"file":"<m3u8>","type":"hls",
 *        "label":"..."}]`) and const tracks = JSON.parse(`[ ... ]`).
 *
 *  B. embedload.cfd/watch?v=<n>
 *     -> inner <iframe src="https://zokoanime.video/<token>">
 *     -> window.__P="<b64>": base64-decode, XOR each byte with the
 *        repeating key "otaku-embed-v1" -> JSON {"src":"<m3u8>",
 *        "subtitles":[ ... ]}.
 *
 *  C. vidbasic.top/embed/<shortid>
 *     -> <iframe id="embedvideo" src="/3rdplayer.html?key=<b64>&id=..">
 *     -> 3rdplayer page: <div data-name="crypto" data-value="<b64>">
 *     -> AES-256-CBC (PKCS7) with the FIXED key
 *        "94588293375053432799222445521289" / iv "5259228356829423"
 *        (both ASCII digit strings) -> "<m3u8 url>".
 *
 * No chain currently ships real subtitle files (A: tracks=[], B:
 * subtitles=[], C: none) but all three subtitle slots are parsed when
 * present.
 */
class Dramahood : MainAPI() {

    override var name = "Dramahood"
    override var mainUrl = SiteConfig.mirror("dramahood")
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.AsianDrama)
    override var lang = "en"
    override val mainPage = mainPageOf(
        "drama" to "Dramas",
        "kshow" to "KShows",
        "latest-drama" to "Latest Dramas",
        "latest-kshow" to "Latest KShows",
    )

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private val HEADERS = mapOf("User-Agent" to UA)

        private val categoryPaths = mapOf(
            "drama" to "category/drama",
            "kshow" to "category/kshow",
            "latest-drama" to "category/latest-asian-drama-releases",
            "latest-kshow" to "category/latest-kshow-releases",
        )

        private val epNumRe = Regex("-episode-(\\d+)")
        // Latest-release category cards point at episode posts; map back to series.
        private val epUrlRe = Regex("^(.+?)-episode-\\d+/?$")
        private val epNameSuffixRe = Regex("(?i)\\s+(?:episode|ep)\\s*\\d+$")
        private val yearRe = Regex("\\((19\\d{2}|20\\d{2})\\)")
    }

    // ------------------------------------------------------------------
    // URL helpers
    // ------------------------------------------------------------------

    private fun toAbsoluteUrl(href: String?, base: String = mainUrl): String? {
        if (href.isNullOrBlank()) return null
        return when {
            href.startsWith("http://") || href.startsWith("https://") -> href
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> base.removeSuffix("/") + href
            else -> base.removeSuffix("/") + "/" + href
        }
    }

    /** True for root-level series URLs like https://dramahood.mom/<slug>/ */
    private fun isSeriesUrl(url: String): Boolean {
        if (!url.startsWith(mainUrl)) return false
        val path = url.removePrefix(mainUrl)
        if (path.contains("-episode-")) return false
        if (path.contains("/category/") || path.contains("/page/")) return false
        if (path.contains("wp-content") || path.contains("wp-json")) return false
        return true
    }

    // ------------------------------------------------------------------
    // Card parsing (catalog + search share the markup)
    // ------------------------------------------------------------------

    private fun Document.toCards(): List<SearchResponse> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<SearchResponse>()
        for (li in select("li")) {
            val a = li.selectFirst("a[href]") ?: continue
            val rawUrl = toAbsoluteUrl(a.attr("href")) ?: continue
            // Regular category pages link straight to series pages. The
            // "latest releases" categories list individual episode posts
            // (<slug>-episode-N/), so normalize those to the series page and
            // de-duplicate repeated series (latest episode first).
            val url = epUrlRe.find(rawUrl)?.let { it.groupValues[1] + "/" } ?: rawUrl
            if (!isSeriesUrl(url)) continue
            if (!seen.add(url)) continue

            val img = a.selectFirst("img") ?: li.selectFirst("img") ?: continue
            var nm = img.attr("alt").trim()
            if (nm.isBlank()) {
                val h2 = li.selectFirst("h2 a")?.text()?.trim().orEmpty()
                nm = h2.substringBefore(" (").ifBlank { a.text().trim() }
            }
            nm = epNameSuffixRe.replace(nm, "").trim()
            if (nm.isBlank()) continue

            val poster = toAbsoluteUrl(img.attr("src"))
                ?.takeIf { it.startsWith("http") && !it.contains("/themes/") }

            out += newTvSeriesSearchResponse(nm, url, TvType.AsianDrama) {
                posterUrl = poster
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Main page
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = categoryPaths[request.data] ?: return newHomePageResponse(request, emptyList())
        val base = mainUrl.removeSuffix("/")
        val url = if (page <= 1) "$base/$path/"
        else "$base/$path/page/$page/"
        // UG-5 dead tier: over-run pages 404 and are not re-tried for 1 h.
        if (Cache.isDead(url)) return newHomePageResponse(request, emptyList())
        return try {
            val resp = app.get(url, headers = HEADERS)
            if (resp.code == 404 || resp.code == 410) {
                Cache.markDead(url)
                return newHomePageResponse(request, emptyList())
            }
            newHomePageResponse(request, resp.document.toCards())
        } catch (_: Throwable) {
            newHomePageResponse(request, emptyList())
        }
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        return try {
            val doc = app.get(
                mainUrl, params = mapOf("s" to q), headers = HEADERS
            ).document
            doc.toCards()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Detail / episodes
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        // UG-5: re-opened series pages are served from the episode cache.
        Cache.episodes.get(url)?.let { return it }
        val doc = app.get(url, headers = HEADERS).document

        val h1 = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val name = h1.ifBlank {
            url.substringAfterLast("/").substringBefore("/").trim()
        }

        val poster = doc.selectFirst("img.poster")?.attr("src")?.let { toAbsoluteUrl(it) }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { toAbsoluteUrl(it) }

        // Scope to the real info block: the hidden "top views" widget further
        // down the page also emits <p><strong>Status:</strong> Ep N</p> lines
        // that would otherwise clobber these values (last one wins in a map).
        val info = mutableMapOf<String, String>()
        for (p in doc.select("div.movie div.left p")) {
            val strong = p.selectFirst("strong")?.text()?.trim() ?: continue
            val label = strong.removeSuffix(":").lowercase().trim()
            if (label.isBlank()) continue
            val value = p.text().trim().removePrefix(strong).trimStart(':', ' ').trim()
            if (value.isNotEmpty()) info[label] = value
        }
        val year = info["release year"]?.toIntOrNull()
            ?: yearRe.find(h1)?.groupValues?.get(1)?.toIntOrNull()
        val status = info["status"].orEmpty()
        val genre = info["genre"].orEmpty()
        val country = info["country"].orEmpty()
        val plot = doc.select("div.right div.info p")
            .joinToString("\n") { it.text().trim() }
            .ifBlank { doc.select("div.info p").joinToString("\n") { it.text().trim() } }

        val eps = mutableListOf<Episode>()
        val seenEp = LinkedHashSet<String>()
        for (a in doc.select("ul.list-episode a[href]")) {
            val epUrl = toAbsoluteUrl(a.attr("href")) ?: continue
            val ep = epNumRe.find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (!seenEp.add(epUrl)) continue
            eps += newEpisode(epUrl) {
                this.name = name
                season = 1
                episode = ep
            }
        }
        eps.sortBy { it.episode ?: 0 }

        val showStatus = when {
            status.contains("ongoing", true) || status.contains("running", true) ->
                ShowStatus.Ongoing
            status.contains("finish", true) || status.contains("complet", true) ||
                status.contains("ended", true) -> ShowStatus.Completed
            else -> null
        }

        val tags = buildList {
            addAll(genre.split(",").map { it.trim() }.filter { it.isNotBlank() })
            if (country.isNotBlank()) add(country)
        }

        val resp = newTvSeriesLoadResponse(name, url, TvType.AsianDrama, eps) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            if (tags.isNotEmpty()) this.tags = tags
            this.showStatus = showStatus
        }
        Cache.episodes.put(url, resp)
        return resp
    }

    // ------------------------------------------------------------------
    // Video + subtitles
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = data
        // UG-5: cached embed lists mean re-opened episodes make no page
        // fetch; the dead tier skips embeds known to 404.
        var embeds = Cache.lists.get(url)
        if (embeds == null) {
            val doc = try {
                app.get(url, headers = HEADERS).document
            } catch (_: Throwable) {
                return false
            }
            val set = LinkedHashSet<String>()
            for (iframe in doc.select("iframe[src]")) {
                val s = iframe.attr("src")
                if (s.startsWith("http")) set += s
            }
            for (el in doc.select("[data-video]")) {
                val s = el.attr("data-video")
                if (s.startsWith("http")) set += s
            }
            if (set.isEmpty()) return false
            embeds = set.toList()
            Cache.lists.put(url, embeds)
        }
        if (embeds.isEmpty()) return false

        // UG-4: every working server resolves in parallel under one
        // ~20 s budget; dead and slow servers drop out silently.
        val ctx = ResolveContext(name, url)
        val results = Resolvers.resolveAll(
            ctx,
            ResolveContext.TOTAL_BUDGET_MS,
            embeds.map { EmbedTask(it, null) },
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
        return added
    }
}
