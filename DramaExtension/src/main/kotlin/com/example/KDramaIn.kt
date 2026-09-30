package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.json.JSONArray
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
 *
 * The vidsync API is intermittent (timeouts / rate limits), so the last good
 * curation per (type, id, season, episode) is cached in memory (12 h TTL,
 * 256 entries) and served when a live call fails or returns zero sources.
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
        "ranking" to "Ranking",
        "watchlist" to "Watchlist",
    )

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val VIDSYNC_API = "https://vidsync.pro/api/extraction/session"
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

        /** Reliability ranking of vidsync backend providers. 0 = junky
         *  sources (gambling-site streams, wrong-language dubs); only used
         *  as a last resort when no reliable provider has anything. */
        private val PROVIDER_TRUST = mapOf(
            "castle" to 3,
            "vidsrc" to 2,
            "vidsrc-rescrape" to 2,
            "kisskh" to 1,
        )

        private fun qualityRank(quality: String): Int = when (quality.lowercase().trim()) {
            "4k", "2160p", "uhd" -> 2160
            "1440p", "2k" -> 1440
            "1080p", "fhd" -> 1080
            "720p" -> 720
            "540p" -> 540
            "480p" -> 480
            "360p" -> 360
            "240p" -> 240
            else -> 0 // Auto / unknown
        }

        private data class VideoSource(
            val provider: String,
            val trust: Int,
            val displayName: String,
            val quality: String,
            val qualityRank: Int,
            val type: String,
            val url: String,
            val audioLabel: String?,
        )

        /** One cached curation for a (type, id, season, episode) request. */
        private data class CachedCuration(
            val sources: List<VideoSource>,
            val subtitles: List<Triple<Int, String, String>>,
            val fetchedAt: Long,
        )

        private const val CURATION_CACHE_TTL_MS = 12L * 60L * 60L * 1000L // 12 h
        private const val CURATION_CACHE_MAX = 256
        private val curationCache = HashMap<String, CachedCuration>()
        private val curationCacheLock = Any()

        private fun readCuration(key: String): CachedCuration? =
            synchronized(curationCacheLock) {
                val e = curationCache[key]
                if (e != null &&
                    System.currentTimeMillis() - e.fetchedAt > CURATION_CACHE_TTL_MS
                ) {
                    curationCache.remove(key)
                    null
                } else {
                    e
                }
            }

        private fun cacheCuration(
            key: String,
            sources: List<VideoSource>,
            subtitles: List<Triple<Int, String, String>>,
        ) {
            synchronized(curationCacheLock) {
                curationCache[key] = CachedCuration(
                    sources, subtitles, System.currentTimeMillis()
                )
                while (curationCache.size > CURATION_CACHE_MAX) {
                    val oldest = curationCache.entries
                        .minByOrNull { it.value.fetchedAt }?.key ?: break
                    curationCache.remove(oldest)
                }
            }
        }

        /** Language name / ISO code → BCP-47-ish 2-3 letter code. */
        private val langNames = mapOf(
            "english" to "en", "eng" to "en", "en" to "en",
            "korean" to "ko", "kor" to "ko", "ko" to "ko",
            "japanese" to "ja", "jpn" to "ja", "ja" to "ja",
            "chinese" to "zh", "chi" to "zh", "zho" to "zh", "zh" to "zh",
            "mandarin" to "zh", "cantonese" to "yue", "yue" to "yue",
            "thai" to "th", "tha" to "th", "th" to "th",
            "indonesian" to "id", "ind" to "id", "id" to "id",
            "malay" to "ms", "msa" to "ms", "ms" to "ms",
            "french" to "fr", "fra" to "fr", "fre" to "fr", "fr" to "fr",
            "german" to "de", "deu" to "de", "ger" to "de", "de" to "de",
            "spanish" to "es", "spa" to "es", "es" to "es",
            "italian" to "it", "ita" to "it", "it" to "it",
            "russian" to "ru", "rus" to "ru", "ru" to "ru",
            "arabic" to "ar", "ara" to "ar", "ar" to "ar",
            "dutch" to "nl", "nld" to "nl", "dut" to "nl", "nl" to "nl",
            "portuguese" to "pt", "por" to "pt", "pt" to "pt",
            "turkish" to "tr", "tur" to "tr", "tr" to "tr",
            "vietnamese" to "vi", "vie" to "vi", "vi" to "vi",
            "hindi" to "hi", "hin" to "hi", "hi" to "hi",
            "czech" to "cs", "ces" to "cs", "cze" to "cs", "cs" to "cs",
            "polish" to "pl", "pol" to "pl", "pl" to "pl",
            "hungarian" to "hu", "hun" to "hu", "hu" to "hu",
            "finnish" to "fi", "fin" to "fi", "fi" to "fi",
            "swedish" to "sv", "swe" to "sv", "sv" to "sv",
            "norwegian" to "no", "nor" to "no", "no" to "no",
            "danish" to "da", "dan" to "da", "da" to "da",
            "romanian" to "ro", "ron" to "ro", "rum" to "ro", "ro" to "ro",
            "bulgarian" to "bg", "bul" to "bg", "bg" to "bg",
            "albanian" to "sq", "sqi" to "sq", "sq" to "sq",
            "greek" to "el", "gre" to "el", "ell" to "el", "el" to "el",
            "hebrew" to "he", "heb" to "he", "he" to "he",
            "urdu" to "ur", "urd" to "ur", "ur" to "ur",
            "persian" to "fa", "farsi" to "fa", "fas" to "fa", "fa" to "fa",
            "khmer" to "km", "khm" to "km", "km" to "km",
            "myanmar" to "my", "burmese" to "my", "mya" to "my", "my" to "my",
            "lao" to "lo", "lo" to "lo",
            "croatian" to "hr", "hrv" to "hr", "hr" to "hr",
            "serbian" to "sr", "srp" to "sr", "sr" to "sr",
            "ukrainian" to "uk", "ukr" to "uk", "uk" to "uk",
        )

        private fun String?.langCode(): String? {
            val s = this?.lowercase()?.trim() ?: return null
            if (s.length in 1..3 && s.all { it.isLetter() }) langNames[s]?.let { return it }
            // Try tokens from file names: "English-Dub.eng.srt", "-Forced.kor.srt"
            val tokens = s.split(Regex("[^a-z]+"))
            for (t in tokens) if (t.length in 2..3 && langNames[t] != null) return langNames[t]
            // Try full words: "English 2" → english
            for (t in tokens) if (t.length >= 4 && langNames[t] != null) return langNames[t]
            return null
        }
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
        return try {
            val url = mainUrl.removeSuffix("/") + "/dramas.php?type=${request.data}&page=${page.coerceAtLeast(1)}"
            val doc = app.get(url).document
            val counts = fetchTmdbEpisodeCounts(doc.toCardTmdbIds())
            newHomePageResponse(request, doc.toCards(counts))
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
            val counts = fetchTmdbEpisodeCounts(doc.toCardTmdbIds())
            doc.toCards(counts)
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
            val cacheKey = if (isMovie) "movie|$id" else "tv|$id|${season ?: 1}|${episode ?: 1}"

            // Try the live API. The vidsync API is intermittent (timeouts /
            // rate limits), so on failure or a zero-source response fall back
            // to the last good curation cached for this episode.
            val candidates = mutableListOf<VideoSource>()
            val subtitleCands = mutableListOf<Triple<Int, String, String>>() // trust, lang, url
            try {
                val text = app.get(VIDSYNC_API, params = params, referer = embedUrl).text
                parseCuration(text, season, episode, candidates, subtitleCands)
            } catch (_: Throwable) {
                // network failure — fall through to the cache below
            }

            val effSources: List<VideoSource>
            val effSubs: List<Triple<Int, String, String>>
            if (candidates.isNotEmpty()) {
                cacheCuration(cacheKey, candidates, subtitleCands)
                effSources = candidates
                effSubs = subtitleCands
            } else {
                val cached = readCuration(cacheKey) ?: return false
                effSources = cached.sources
                effSubs = cached.subtitles
            }

            val links = selectLinks(effSources)
            for (c in links) {
                val isHls = c.type.equals("hls", true) || c.url.contains(".m3u8")
                val isDash = c.type.equals("mpd", true) || c.url.contains(".mpd")
                val label = "${c.displayName} ${c.quality}" + (c.audioLabel?.let { " ($it)" } ?: "")
                callback(
                    newExtractorLink(
                        name,
                        label,
                        c.url,
                        when {
                            isDash -> ExtractorLinkType.DASH
                            isHls -> ExtractorLinkType.M3U8
                            else -> ExtractorLinkType.VIDEO
                        },
                    ) {
                        referer = embedUrl
                        this.quality = c.qualityRank
                        headers = mapOf("User-Agent" to UA)
                    }
                )
            }

            // Subtitles: dedupe by URL, then by language (most trusted wins).
            val subSeenUrl = LinkedHashSet<String>()
            val subSeenLang = LinkedHashSet<String>()
            val subs = effSubs
                .sortedWith(compareByDescending<Triple<Int, String, String>> { it.first })
            for ((trust, lang, url) in subs) {
                if (!subSeenUrl.add(url)) continue
                val key = if (lang.isBlank()) url else lang.lowercase()
                if (lang.isNotBlank() && !subSeenLang.add(key)) continue
                subtitleCallback(newSubtitleFile(lang, url))
            }
            links.isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Parses the vidsync JSON-lines response. provider-result lines carry a
     * nested "sources" array whose objects contain nested objects ("headers",
     * "audioTracks", ...), so each line is parsed with a real JSON parser.
     * Sources that resolve to a different season/episode are skipped
     * (vidsync sometimes maps an episode to a wrong file).
     */
    private fun parseCuration(
        text: String,
        season: Int?,
        episode: Int?,
        outCandidates: MutableList<VideoSource>,
        outSubs: MutableList<Triple<Int, String, String>>,
    ) {
            for (line in text.lines()) {
                if (!line.contains("provider-result")) continue
                val obj = try {
                    JSONObject(line)
                } catch (_: Throwable) {
                    continue
                }
                if (obj.optString("type") != "provider-result") continue
                val provider = obj.optString("provider").ifBlank { continue }
                val trust = PROVIDER_TRUST[provider] ?: 0
                val sources = obj.optJSONArray("sources") ?: continue
                for (i in 0 until sources.length()) {
                    val s = sources.optJSONObject(i) ?: continue
                    var url = s.optString("url").ifBlank { s.optString("rawUrl") }.ifBlank { continue }
                    if (url.startsWith("/")) url = "https://vidsync.pro$url"
                    if (!url.startsWith("http")) continue
                    // Skip sources that resolved to a different episode
                    // (vidsync sometimes maps an episode to a wrong file).
                    val edition = s.optJSONObject("edition")
                    val resE = edition?.opt("resolvedEpisode")?.let { (it as? Number)?.toInt() }
                    val resS = edition?.opt("resolvedSeason")?.let { (it as? Number)?.toInt() }
                    if (resE != null && episode != null && resE != episode) continue
                    if (resS != null && season != null && resS != season) continue
                    val quality = s.optString("quality").ifBlank { "Auto" }
                    // Dub label: if the stream includes an original-audio track
                    // (multi-audio), leave it unlabeled; otherwise show the
                    // dubbed language so users can tell dubs apart.
                    val audioTracks = s.optJSONArray("audioTracks")
                    var audioLabel: String? = null
                    if (audioTracks != null && audioTracks.length() > 0) {
                        var hasOriginal = false
                        var firstDub: String? = null
                        for (j in 0 until audioTracks.length()) {
                            val at = audioTracks.optJSONObject(j) ?: continue
                            if (at.optBoolean("original", false) ||
                                at.optString("language").equals("original", true)
                            ) {
                                hasOriginal = true
                                continue
                            }
                            if (firstDub == null) {
                                firstDub = at.optString("label")
                                    .ifBlank { at.optString("language") }
                            }
                        }
                        if (!hasOriginal && firstDub != null) audioLabel = firstDub
                    }
                    val displayName = s.optJSONObject("provider")?.optString("name")
                        ?.ifBlank { null } ?: provider.replaceFirstChar { it.uppercase() }
                    outCandidates += VideoSource(
                        provider = provider,
                        trust = trust,
                        displayName = displayName,
                        quality = quality,
                        qualityRank = qualityRank(quality),
                        type = s.optString("type"),
                        url = url,
                        audioLabel = audioLabel,
                    )
                    // Per-source subtitles (e.g. kisskh)
                    val subs = s.optJSONArray("subtitles") ?: JSONArray()
                    collectSubs(subs, trust, outSubs)
                }
                // Provider-level subtitles (e.g. castle, vidsrc)
                collectSubs(obj.optJSONArray("subtitles") ?: JSONArray(), trust, outSubs)
            }
    }

    /**
     * Picks the extractor links from the parsed candidates: reliable
     * providers first (everything as a last resort), dedupe per
     * (provider, audio, quality) keeping the first occurrence, then sort
     * (original audio first, then trust, then quality). vidsrc-rescrape
     * URLs are relay-wrapped and more robust than the direct vidapi.cloud
     * ones, so the direct twin is dropped when both exist.
     */
    private fun selectLinks(candidates: List<VideoSource>): List<VideoSource> {
        val reliable = candidates.filter { it.trust > 0 }
        val pickedAll = if (reliable.isNotEmpty()) reliable else candidates
        val picked = pickedAll.filterNot { c ->
            c.provider == "vidsrc" && pickedAll.any {
                it.provider == "vidsrc-rescrape" &&
                    it.qualityRank == c.qualityRank && it.audioLabel == c.audioLabel
            }
        }
        val deduped = linkedMapOf<String, VideoSource>()
        for (c in picked) {
            val key = "${c.provider}|${c.audioLabel ?: "orig"}|${c.quality}"
            if (!deduped.containsKey(key)) deduped[key] = c
        }
        return deduped.values
            .sortedWith(compareByDescending<VideoSource> { it.audioLabel == null } // original audio first
                .thenByDescending { it.trust }
                .thenByDescending { it.qualityRank }
                .thenBy { it.quality.lowercase() })
    }

    private fun collectSubs(subs: JSONArray, trust: Int, out: MutableList<Triple<Int, String, String>>) {
        for (i in 0 until subs.length()) {
            val s = subs.optJSONObject(i) ?: continue
            var subUrl = s.optString("file").ifBlank { s.optString("url") }
            if (subUrl.startsWith("/")) subUrl = "https://vidsync.pro$subUrl"
            if (!subUrl.startsWith("http")) continue
            out += Triple(trust, s.langCodeFromAny() ?: "", subUrl)
        }
    }

    /** Resolves a subtitle entry's language: code field → file name → label. */
    private fun JSONObject.langCodeFromAny(): String? {
        val fromField = optString("language")
        if (fromField.length in 2..3 && fromField.all { it.isLetter() }) return fromField
        val fileName = optString("file").ifBlank { optString("url") }
            .substringAfterLast('/')
        for (suf in listOf(".vtt", ".srt", ".ass", ".ssa")) {
            if (fileName.endsWith(suf)) return fileName.removeSuffix(suf).langCode()
        }
        return fileName.langCode() ?: optString("label").langCode()
    }
}
