package com.example

/**
 * Multi-server fan-out for watch pages that offer several interchangeable
 * sources.
 *
 * ## The problem this fixes
 *
 * The resolver layer picks **one** resolver per embed by host
 * (`Resolvers.forHost` -> `firstOrNull { hosts.any { ... } }`), and each
 * provider hands it a **single** embed URL. So when a site lists several
 * servers - as k-drama.in's watch page does, seven of them - and the one we
 * picked is dead or simply has no copy of that title, `loadLinks` returns
 * nothing and the episode is unplayable. A human in a browser clicks through
 * the server list until one works; the extension had no way to.
 *
 * Verified on k-drama.in: watch page servers are
 *   1 vidsync.pro   2 /13.php   3 /2.php   4 vidzee   5 zxcstream
 *   6 /yoy4.php (YOY -> kisskh.megaplay.su)   7 /player.php
 * and for a given episode several of those legitimately have no source.
 *
 * ## The fix
 *
 * Build *all* plausible embed URLs for an episode and let the existing
 * resolver layer try them under its shared time budget. `Resolvers.resolveAll`
 * already runs tasks concurrently, dedupes by URL, ranks by quality, and
 * survives individual failures (UG-4's "dead + slow + good" acceptance), so
 * emitting several tasks is the intended shape of that API - we were simply
 * only ever passing one.
 *
 * Order matters: the site's own preferred server stays first, with the ones
 * that need extra referers/decryption last, because `emitResult` preserves
 * resolver order and CloudStream shows the first entry first.
 */
class MultiServerResolver(
    private val label: String,
) {
    companion object {
        /** k-drama.in watch-page server 6 - the YOY -> KissKH chain. */
        fun yoy4(mainUrl: String, id: String, season: Int, episode: Int, isMovie: Boolean): String {
            val base = mainUrl.removeSuffix("/")
            return if (isMovie) {
                "$base/yoy4.php?id=$id&s=1&e=1"
            } else {
                "$base/yoy4.php?id=$id&s=$season&e=$episode"
            }
        }

        /**
         * k-drama.in servers 2 and 3, in-page proxies that take the episode
         * path form `/13.php/<id>/<s>/<e>`.
         */
        fun kdramaInProxy(mainUrl: String, id: String, season: Int, episode: Int, which: Int): String {
            val base = mainUrl.removeSuffix("/")
            return "$base/$which.php/$id/$season/$episode"
        }

        /** k-drama.in server 7 (Hindi). */
        fun kdramaInPlayer(mainUrl: String, id: String, season: Int, episode: Int): String =
            "${mainUrl.removeSuffix("/")}/player.php?tmdb=$id&season=$season&episode=$episode"

        /**
         * k-drama.in server 4 (Vidzee).
         *
         * Note the site's own `switchServer` builds the **movie** path with no
         * season/episode for every type, which misroutes TV episodes to a
         * different title entirely (verified: id=290699 s1e1 resolves to "The
         * Eleventh Aggression" on /movie/ but "Shadow Punisher S1E1" on /tv/).
         * We build the correct typed path instead.
         */
        fun vidzee(id: String, season: Int, episode: Int, isMovie: Boolean): String =
            if (isMovie) {
                "https://player.vidzee.wtf/embed/movie/$id"
            } else {
                "https://player.vidzee.wtf/embed/tv/$id/$season/$episode"
            }

        /** k-drama.in server 5 (Zxcstream) - same typed-path correction. */
        fun zxcstream(id: String, season: Int, episode: Int, isMovie: Boolean): String =
            if (isMovie) {
                "https://player.zxcstream.xyz/player/movie/$id"
            } else {
                "https://player.zxcstream.xyz/player/tv/$id/$season/$episode"
            }
    }

    /**
     * Every embed URL worth trying for one episode, best-first.
     *
     * The primary vidsync embed is *not* included here - callers pass that
     * separately as the first task, because its base URL comes from
     * `SiteConfig.vidsyncBase()` and must stay hot-swappable.
     */
    fun tasksFor(
        mainUrl: String,
        id: String,
        season: Int,
        episode: Int,
        isMovie: Boolean,
    ): List<EmbedTask> {
        val out = LinkedHashSet<String>()
        out += yoy4(mainUrl, id, season, episode, isMovie)
        out += kdramaInProxy(mainUrl, id, season, episode, 13)
        out += kdramaInProxy(mainUrl, id, season, episode, 2)
        if (!isMovie) {
            out += kdramaInPlayer(mainUrl, id, season, episode)
        }
        out += vidzee(id, season, episode, isMovie)
        out += zxcstream(id, season, episode, isMovie)
        return out.map { EmbedTask(it, label) }
    }
}