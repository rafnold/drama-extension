package com.example

import com.lagradost.cloudstream3.LoadResponse

/**
 * Tiered process-lifetime caches (UG-5). All tiers are TtlCache: bounded,
 * insertion-ordered eviction, thread-safe. Clear with [clearAll] (or the
 * process restarts).
 *
 *   playerHosts  7 d   dramavideo player.js -> host (rare rotation)
 *   sources      15 m   embed URL -> last non-empty ResolveResult
 *   episodes     12 h   episode URL -> full LoadResponse (re-open = no net)
 *   lists        12 h   list URL -> episode URL list (sitemaps etc.)
 *   tmdbHits     7 d   TMDB search hit -> id (drama IDs are stable)
 *   tmdbMisses   1 h   TMDB search miss -> null (re-try later)
 *   curation     12 h   vidsync curation (provider + source + subtitle list)
 *   dead          1 h   embed URL -> last 404/410 seen (skip re-fetch)
 */
object Cache {
    val playerHosts = TtlCache<String>(7 * 24 * 3_600_000L, 64)
    val sources = TtlCache<ResolveResult>(15 * 60_000L, 512)
    val episodes = TtlCache<LoadResponse>(12 * 3_600_000L, 2048)
    val lists = TtlCache<List<String>>(12 * 3_600_000L, 32)
    val tmdbHits = TtlCache<Int>(7 * 24 * 3_600_000L, 1024)
    val tmdbMisses = TtlCache<Boolean>(3_600_000L, 1024)
    val curation = TtlCache<VidsyncCuration>(12 * 3_600_000L, 256)
    val dead = TtlCache<Boolean>(3_600_000L, 256)

    /** UG-5 dead tier: 404/410 mirrors are not retried within the TTL. */
    fun isDead(url: String): Boolean = dead.get(url) == true

    fun markDead(url: String) {
        dead.put(url, true)
    }

    fun clearAll() {
        playerHosts.clear()
        sources.clear()
        episodes.clear()
        lists.clear()
        tmdbHits.clear()
        tmdbMisses.clear()
        curation.clear()
        dead.clear()
    }
}
