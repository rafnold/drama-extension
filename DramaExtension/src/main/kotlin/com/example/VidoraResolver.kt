package com.example

import org.json.JSONObject

/**
 * Vidora (moviesapi.to / moviesapi.club) movie embed.
 *
 *   vault iframe  ->  https://moviesapi.to/movie/<tmdbId>?theme=...
 *   GET           ->  https://moviesapi.to/api/vidora/v1/movie/<tmdbId>
 *                     (x-player-key: <key>, Origin: moviesapi.to)
 *                     -> {result, sources:[{url, source, tracks:[{file,label}]}]}
 */
object VidoraResolver : Resolver {
    override val hosts = listOf("moviesapi")

    private val movieIdRe = Regex("/movie/(\\d+)")
    private const val MAPI_KEY = "3a67e8866ae1d2bb9e81fe7f73315a56eb3bdf5e3e755c7554c8be6910aa6b13"

    override fun resolve(ctx: ResolveContext, embedUrl: String, label: String?): ResolveResult {
        if (ctx.remainingMs() <= 0) return ResolveResult.EMPTY
        val tmdbId = movieIdRe.find(embedUrl)?.groupValues?.get(1)
            ?: return ResolveResult.EMPTY
        val origin = try {
            val u = java.net.URI(embedUrl)
            "${u.scheme}://${u.host}" + (if (u.port >= 0) ":${u.port}" else "")
        } catch (_: Throwable) {
            return ResolveResult.EMPTY
        }
        val resp = try {
            Http.get(
                "$origin/api/vidora/v1/movie/$tmdbId",
                headers = mapOf(
                    "User-Agent" to UA,
                    "x-player-key" to MAPI_KEY,
                    "Origin" to origin,
                ),
                referer = embedUrl,
                timeoutMs = ctx.remainingMs(),
            )
        } catch (_: Throwable) {
            return ResolveResult.EMPTY
        }
        if (resp.code in intArrayOf(404, 410)) {
            Cache.markDead(embedUrl)
            return ResolveResult.EMPTY
        }
        val j = try {
            JSONObject(resp.text)
        } catch (_: Throwable) {
            return ResolveResult.EMPTY
        }
        if (!j.optBoolean("result", false)) return ResolveResult.EMPTY
        val arr = j.optJSONArray("sources") ?: return ResolveResult.EMPTY

        val sources = mutableListOf<ResolvedSource>()
        val subs = mutableListOf<ResolvedSubtitle>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val m3u8 = s.optString("url")
            if (!m3u8.startsWith("http")) continue
            sources += ResolvedSource(
                name = s.optString("source").ifBlank { label ?: "Vidora" },
                url = m3u8,
                referer = "$origin/",
                type = "hls",
            )
            s.optJSONArray("tracks")?.let { tracks ->
                for (k in 0 until tracks.length()) {
                    val t = tracks.optJSONObject(k) ?: continue
                    val f = t.optString("file")
                    if (!f.startsWith("http")) continue
                    subs += ResolvedSubtitle(
                        lang = langOf(t.optString("label"), f),
                        url = f,
                        headers = mapOf("User-Agent" to UA),
                    )
                }
            }
        }
        return ResolveResult(sources.isNotEmpty(), sources, subs)
    }
}
