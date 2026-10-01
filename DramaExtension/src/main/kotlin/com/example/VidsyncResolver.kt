package com.example

/**
 * Vidsync (vidsync.pro) extraction-session resolver.
 *
 * The provider page (or embed URL) carries the TMDB id (+ season/episode);
 * the extraction API returns a curated multi-provider source list. Live
 * first; on failure or a zero-source response falls back to the last good
 * curation cached for this episode (12 h), so intermittent API outages
 * don't kill playback.
 */
object VidsyncResolver : Resolver {
    override val hosts = listOf("vidsync")

    private val HEADERS = mapOf("User-Agent" to UA)

    private val idRe = Regex("id=(\\d+)")
    private val embedIdRe = Regex("embed/(?:tv|movie)/(\\d+)")
    private val seasonRe = Regex("season=(\\d+)")
    private val episodeRe = Regex("episode=(\\d+)")

    private class VidsyncIds(
        val id: String,
        val isMovie: Boolean,
        val season: Int?,
        val episode: Int?,
    )

    override fun resolve(ctx: ResolveContext, embedUrl: String, label: String?): ResolveResult {
        if (ctx.remainingMs() <= 0) return ResolveResult.EMPTY
        val ids = parseIds(ctx.pageUrl) ?: parseIds(embedUrl)
            ?: return ResolveResult.EMPTY
        val cacheKey = if (ids.isMovie) "movie|${ids.id}"
        else "tv|${ids.id}|${ids.season ?: 1}|${ids.episode ?: 1}"

        val params = mutableMapOf(
            "type" to if (ids.isMovie) "movie" else "tv",
            "id" to ids.id,
        )
        if (!ids.isMovie) {
            params["season"] = (ids.season ?: 1).toString()
            params["episode"] = (ids.episode ?: 1).toString()
        }

        // Live API first (the vidsync API is intermittent: timeouts / rate
        // limits); fall back to the last good cached curation.
        var cur: VidsyncCuration? = null
        try {
            val r = Http.get(Vidsync.API, headers = HEADERS, params = params,
                referer = embedUrl, timeoutMs = ctx.remainingMs())
            if (r.code == 200) {
                cur = Vidsync.parseCuration(r.text, ids.season, ids.episode)
            }
        } catch (_: Throwable) {
            // network failure - fall through to the cache
        }
        if (cur == null || cur.sources.isEmpty()) {
            cur = Cache.curation.get(cacheKey)
                ?: return ResolveResult.EMPTY
            Cache.curation.put(cacheKey, cur) // re-put slides the TTL
        } else {
            Cache.curation.put(cacheKey, cur)
        }

        val links = Vidsync.selectLinks(cur.sources)
        if (links.isEmpty()) return ResolveResult.EMPTY
        val sources = links.map { c ->
            ResolvedSource(
                name = "${c.displayName} ${c.quality}" +
                    (c.audioLabel?.let { " ($it)" } ?: ""),
                url = c.url,
                referer = embedUrl,
                quality = c.qualityRank,
                type = c.type,
                audioLabel = c.audioLabel,
            )
        }
        val subs = cur.subtitles.map { (trust, lang, url) ->
            ResolvedSubtitle(lang, url, trust = trust)
        }
        return ResolveResult(true, sources, subs)
    }

    private fun parseIds(url: String): VidsyncIds? {
        val id = idRe.find(url)?.groupValues?.get(1)
            ?: embedIdRe.find(url)?.groupValues?.get(1)
            ?: return null
        return VidsyncIds(
            id = id,
            isMovie = url.contains("type=movie") || url.contains("/embed/movie/"),
            season = seasonRe.find(url)?.groupValues?.get(1)?.toIntOrNull(),
            episode = episodeRe.find(url)?.groupValues?.get(1)?.toIntOrNull(),
        )
    }
}
