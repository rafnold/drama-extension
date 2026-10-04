package com.example

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

// ---------------------------------------------------------------------------
// Shared resolver layer (UG-1 / UG-4 / UG-5).
//
// Non-suspend resolvers that run on the Concurrency daemon pool; each call
// is bridged into NiceHttp via Http.blocking(). A ResolveContext carries a
// hard deadline (per-embed 15 s budget, ~20 s total) so UG-4's "dead + slow
// + normal" acceptance holds: every embed resolves or bails within budget,
// and working servers keep emitting links.
// ---------------------------------------------------------------------------

/** One resolved, playable source. */
data class ResolvedSource(
    val name: String,
    val url: String,
    val referer: String,
    val quality: Int = 0,
    val qualityLabel: String = "",
    val type: String = "hls", // "hls" | "dash" | "mp4"
    val audioLabel: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<ResolvedSubtitle> = emptyList(),
)

/** One resolved subtitle track. */
data class ResolvedSubtitle(
    val lang: String,
    val url: String,
    val trust: Int = 0,
    val headers: Map<String, String> = emptyMap(),
)

/** Resolver output. [ok] is true when at least one source was produced. */
data class ResolveResult(
    val ok: Boolean,
    val sources: List<ResolvedSource>,
    val subtitles: List<ResolvedSubtitle> = emptyList(),
) {
    /** Dedupes by URL (subtitles by URL, then language - highest trust wins). */
    fun dedupe(): ResolveResult {
        val sources = sources.distinctBy { it.url }
        val subByUrl = LinkedHashMap<String, ResolvedSubtitle>()
        for (s in subtitles) subByUrl.putIfAbsent(s.url, s)
        val subByLang = LinkedHashMap<String, ResolvedSubtitle>()
        for (s in subByUrl.values.sortedByDescending { it.trust }) {
            subByLang.putIfAbsent(s.lang, s)
        }
        return ResolveResult(ok = sources.isNotEmpty(), sources, subByLang.values.toList())
    }

    companion object {
        val EMPTY = ResolveResult(false, emptyList(), emptyList())
    }
}

/**
 * Per-call deadline. [deadlineMs] is the absolute epoch-ms cutoff;
 * [remainingMs] returns the budget left for a single HTTP call (0 = expired,
 * so resolvers must stop and return whatever they have).
 */
class ResolveContext(
    val providerName: String,
    val pageUrl: String,
    deadlineMs: Long = System.currentTimeMillis() + TOTAL_BUDGET_MS,
) {
    private val deadlineMs = deadlineMs
    companion object {
        const val PER_EMBED_MS = 15_000L

        /**
         * Raised 20s -> 40s on 2026-10-04.
         *
         * A cold Cloudflare solve through FlareSolverr measures **11.7 s** on
         * the owner's LAN (k-drama.in, verified twice), and a first solve that
         * returns an interstitial costs a second round-trip on top. At the old
         * 20 s total budget, six servers fanning out and each potentially paying
         * that solve, the budget expired mid-flight and `loadLinks` returned
         * nothing - which CloudStream surfaces as "Bad http status". The servers
         * that *don't* need a solve still finish in well under a second, so the
         * larger ceiling costs nothing when no challenge is present.
         */
        const val TOTAL_BUDGET_MS = 40_000L
    }

    fun remainingMs(default: Long = PER_EMBED_MS): Long =
        default.coerceAtMost(deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
}

/**
 * A single embed chain. [hosts] are the host tokens this resolver owns
 * (matched with "contains" so pirate domain rotation, e.g.
 * catalog.dramavibe.cfd, still matches "dramavibe"). The catch-all resolver
 * uses an empty list and must stay last in [Resolvers.all].
 */
interface Resolver {
    val hosts: List<String>

    fun resolve(ctx: ResolveContext, embedUrl: String, label: String?): ResolveResult
}

/** One embed to resolve, with the provider's display label for its sources. */
data class EmbedTask(
    val url: String,
    val label: String? = null,
    /** Optional custom resolver. When set, [Resolvers.resolveAll] calls this
     *  instead of the host-matched resolver. Used for dedicated resolvers
     *  (DevcorpResolver, Yoy4Resolver) that need their own referer-aware
     *  walk and cannot be parsed by the generic host-matched resolver. */
    val customResolver: ((String) -> ResolveResult)? = null,
)

object Resolvers {
    /** Registry order matters: catch-all must be last. */
    val all: List<Resolver> = listOf(
        DramavideoResolver,
        ZokoEmbedResolver,
        VidbasicResolver,
        MegaplayResolver,
        VidoraResolver,
        VidmolyResolver,
        KissasianResolver,
        VidsyncResolver,
        DirectM3u8Resolver,
    )

    fun hostOf(url: String): String = try {
        (java.net.URI(url).host ?: "").lowercase()
    } catch (_: Throwable) {
        ""
    }

    fun forHost(embedUrl: String): Resolver? {
        val host = hostOf(embedUrl)
        return all.firstOrNull { r ->
            r.hosts.any { host.contains(it) }
        } ?: run {
            // Catch-all: empty hosts list.
            all.lastOrNull { it.hosts.isEmpty() }
        }
    }

    /**
     * Resolve one embed with the tiered cache (UG-5):
     *   dead tier (1 h) -> sources tier (15 min) -> resolver -> cache.
     */
    fun resolve(
        ctx: ResolveContext,
        embedUrl: String,
        label: String?,
    ): ResolveResult {
        // Record entry/exit for every embed: a resolver returning EMPTY is
        // otherwise completely silent, which is how a whole fan-out can fail
        // with no trace anywhere.
        ExtLog.log("res", "enter ${label ?: "-"} $embedUrl")
        if (Cache.isDead(embedUrl)) {
            ExtLog.log("res", "DEAD tier $embedUrl")
            return ResolveResult.EMPTY
        }
        Cache.sources.get(embedUrl)?.let {
            ExtLog.log("res", "cache hit $embedUrl ok=${it.ok}")
            return it
        }
        val r = forHost(embedUrl)
        if (r == null) {
            ExtLog.log("res", "NO RESOLVER for host $embedUrl")
            return ResolveResult.EMPTY
        }
        val result = try {
            r.resolve(ctx, embedUrl, label).dedupe()
        } catch (t: Throwable) {
            ExtLog.log("res", "THREW ${t.javaClass.simpleName} ${t.message} on $embedUrl")
            ResolveResult.EMPTY
        }
        ExtLog.log("res", "exit ok=${result.ok} src=${result.sources.size} $embedUrl")
        if (result.ok) Cache.sources.put(embedUrl, result)
        return result
    }

    /**
     * UG-4: fan out over every embed in parallel under one total budget.
     * Dead and slow embeds drop out; working ones keep returning links.
     * Each result carries its own URL (timed-out slots are dropped, so
     * input-order alignment is not guaranteed).
     */
    fun resolveAll(
        ctx: ResolveContext,
        budgetMs: Long,
        embeds: Collection<EmbedTask>,
    ): List<Pair<String, ResolveResult>> {
        val started = System.currentTimeMillis()
        val remaining = { (budgetMs - (System.currentTimeMillis() - started)).coerceAtLeast(0L) }
        return Concurrency.fanOut(remaining(), embeds.map { task ->
            {
                val sub = ResolveContext(ctx.providerName, ctx.pageUrl,
                    deadlineMs = started + budgetMs)
                task.url to try {
                    if (task.customResolver != null) {
                        // Dedicated resolver: bypasses the host-matched lookup
                        // and the tiered cache. These resolvers do their own
                        // referer-aware walk and their own caching.
                        task.customResolver(task.url).dedupe()
                    } else {
                        resolve(sub, task.url, task.label)
                    }
                } catch (_: Throwable) {
                    ResolveResult.EMPTY
                }
            }
        })
    }
}

// ---------------------------------------------------------------------------
// Helpers shared by the resolvers.
// ---------------------------------------------------------------------------

/** Absolute-URL resolution against a base (jsoup-style, no parsing needed). */
fun absoluteUrl(url: String, base: String): String? {
    if (url.isBlank()) return null
    if (url.startsWith("//")) {
        return try {
            val u = java.net.URI(base)
            "${u.scheme ?: "https"}:$url"
        } catch (_: Throwable) {
            null
        }
    }
    if (url.startsWith("http://") || url.startsWith("https://")) return url
    if (url.startsWith("/")) {
        return try {
            val u = java.net.URI(base)
            val origin = "${u.scheme ?: "https"}://${u.host}" +
                (if (u.port >= 0) ":${u.port}" else "")
            "$origin${if (url.startsWith("/")) "" else "/"}$url"
        } catch (_: Throwable) {
            null
        }
    }
    try {
        return java.net.URI(base).resolve(url).toString()
    } catch (_: Throwable) {
        return null
    }
}

/** Origin root: https://host/ (used as a Referer for third-party fetches). */
fun originRoot(url: String): String = try {
    val u = java.net.URI(url)
    (u.scheme ?: "https") + "://" + (u.host ?: "") +
        (if (u.port >= 0) ":${u.port}" else "") + "/"
} catch (_: Throwable) {
    "https://"
}

/** 2-3 letter language code from a filename token, "en" when unknown. */
fun guessLang(url: String): String {
    val base = url.substringAfterLast('/').substringBeforeLast('.')
    val codes = Regex("\\b[a-zA-Z]{2,3}\\b").findAll(base).map { it.value }
    return codes.lastOrNull() ?: "en"
}

/** Label or filename-derived language code for subtitle tracks. */
fun langOf(label: String, file: String): String =
    if (label.isNotBlank()) label else guessLang(file)

// ---------------------------------------------------------------------------
// emitResult: shared link/subtitle emission, shared by every provider.
// ---------------------------------------------------------------------------

/**
 * Emits [res] through the CloudStream callbacks in stable order (links in
 * resolver order, subtitles last, deduped by URL then language - highest
 * trust wins). Returns true when at least one link was emitted.
 */
suspend fun emitResult(
    res: ResolveResult,
    source: String,
    ua: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    var any = false
    for (s in res.sources) {
        callback(
            newExtractorLink(source, s.name, s.url, linkType(s)) {
                referer = s.referer
                quality = s.quality
                headers = buildMap {
                    put("User-Agent", ua)
                    putAll(s.headers)
                }
            }
        )
        any = true
    }
    val seenSubs = LinkedHashSet<String>()
    for (s in res.subtitles.sortedByDescending { it.trust }) {
        if (!seenSubs.add(s.url)) continue
        subtitleCallback(
            newSubtitleFile(s.lang, s.url) {
                headers = buildMap {
                    put("User-Agent", ua)
                    putAll(s.headers)
                }
            }
        )
    }
    return any
}

private fun linkType(s: ResolvedSource): ExtractorLinkType = when {
    s.type.equals("hls", true) || s.url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
    s.type.equals("dash", true) || s.url.contains(".mpd", true) -> ExtractorLinkType.DASH
    else -> ExtractorLinkType.VIDEO
}
