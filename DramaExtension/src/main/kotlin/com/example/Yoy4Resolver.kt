package com.example

import org.json.JSONObject

/**
 * Server 6 of the k-drama.in watch page ("YOY") — the chain that actually
 * plays, verified live 2026-10-05.
 *
 * ## How it works
 *
 * The watch page's `switchServer(6)` sets the player iframe to
 * `https://k-drama.in/yoy4.php?id=<tmdbId>&s=<season>&e=<episode>`.
 * That page is an SPA that fetches `?ajax=1` to get the server list:
 *
 * ```json
 * {
 *   "success": true,
 *   "backdrop": "...",
 *   "servers": [
 *     {"name": "Fast Server (Default)", "src": "https://kisskh.megaplay.su/kisskh/<id>"},
 *     {"name": "▶ Standard Server",     "src": "https://megavid.buzz/kisskh/<id>"}
 *   ]
 * }
 * ```
 *
 * Both servers carry the same content with English subtitles. The resolver
 * tries them in order until one yields a playable m3u8.
 *
 * ### kisskh.megaplay.su
 *
 * The player page embeds the m3u8 and .srt tracks directly in the HTML.
 * The m3u8 is referer-gated: it answers 403 for a k-drama.in referer and
 * 200 only for a self-referer (`https://kisskh.megaplay.su/`).
 *
 * ### megavid.buzz
 *
 * The player page is an SPA that fetches `/kisskh/<id>/source` for JSON:
 * ```json
 * {
 *   "status": "ok",
 *   "source": "https://megavid.buzz/vid/.../index.m3u8",
 *   "tracks": [
 *     {"file": "https://megavid.buzz/sub/.../en.srt", "label": "English", ...}
 *   ]
 * }
 * ```
 * The m3u8 here is also referer-gated (self-referer required).
 *
 * ## Why the old resolver failed
 *
 * The old implementation scraped the rendered HTML for an iframe, which
 * required a 7-second SPA render wait and only worked for the first server
 * (kisskh.megaplay.su). Movies often return "Episode 1 not found" from
 * kisskh but work fine on megavid.buzz, so the resolver needed to try
 * both.
 */
class Yoy4Resolver {

    companion object {
        private const val NAME = "Server 6 (YOY)"
        private val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        /** Gate for the yoy4.php proxy page on k-drama.in. */
        private const val PAGE_REFERER = "https://k-drama.in/"

        /** Gate for the kisskh player page (any k-drama.in page works). */
        private const val PLAYER_REFERER = PAGE_REFERER

        /** Gate for the kisskh m3u8 + .srt: the kisskh host itself. */
        private const val KISSKH_REFERER = "https://kisskh.megaplay.su/"

        /** Gate for the megavid m3u8 + .srt: the megavid host itself. */
        private const val MEGAVID_REFERER = "https://megavid.buzz/"

        private val langNames = mapOf(
            "en" to "English",
            "ar" to "Arabic",
            "id" to "Indonesian",
            "ms" to "Malay",
            "km" to "Khmer",
            "nl" to "Dutch",
            "th" to "Thai",
            "vi" to "Vietnamese",
            "zh" to "Chinese",
            "es" to "Spanish",
            "fr" to "French",
            "de" to "German",
            "pt" to "Portuguese",
            "ru" to "Russian",
            "ja" to "Japanese",
            "ko" to "Korean",
            "tr" to "Turkish",
        )
    }

    /**
     * Resolves one episode through the yoy4 chain.
     *
     * @param data the episode URL, e.g.
     *   `https://k-drama.in/watch.php?id=290699&season=1&episode=1&type=tv`
     * @return sources + subtitles, or [ResolveResult.EMPTY] on any failure.
     */
    fun resolve(data: String): ResolveResult {
        return try {
            val id = Regex("id=(\\d+)").find(data)?.groupValues?.get(1)
                ?: return ResolveResult.EMPTY
            val season = Regex("season=(\\d+)").find(data)?.groupValues?.get(1) ?: "1"
            val episode = Regex("episode=(\\d+)").find(data)?.groupValues?.get(1) ?: "1"
            // Movies use s=1&e=1 on the site (verified in playerState).
            val isMovie = data.contains("type=movie")
            val s = if (isMovie) "1" else season
            val e = if (isMovie) "1" else episode

            val yoyUrl = "$PAGE_REFERER" + "yoy4.php?id=$id&s=$s&e=$e"

            // Step 1: fetch the server list via the ajax endpoint.
            // This is the same call the SPA makes — no render wait needed.
            val ajaxResp = Http.get(
                yoyUrl + "&ajax=1",
                headers = mapOf("User-Agent" to UA, "Referer" to PAGE_REFERER),
                interceptor = CloudflareGate.interceptor(),
            )
            if (ajaxResp.code != 200) {
                ExtLog.log("yoy", "ajax HTTP ${ajaxResp.code}")
                return ResolveResult.EMPTY
            }
            val ajaxJson = JSONObject(ajaxResp.text)
            if (!ajaxJson.optBoolean("success", false)) {
                val err = ajaxJson.optString("error", "unknown")
                ExtLog.log("yoy", "ajax error: $err")
                return ResolveResult.EMPTY
            }
            val servers = ajaxJson.optJSONArray("servers")
            if (servers == null || servers.length() == 0) {
                ExtLog.log("yoy", "no servers in ajax response")
                return ResolveResult.EMPTY
            }
            ExtLog.log("yoy", "${servers.length()} servers available")

            // Step 2: try each server in order until one yields a playable m3u8.
            val allSources = mutableListOf<ResolvedSource>()
            val allSubs = LinkedHashMap<String, ResolvedSubtitle>()

            for (i in 0 until servers.length()) {
                val srv = servers.getJSONObject(i)
                val src = srv.optString("src", "")
                val srvName = srv.optString("name", "server $i")
                if (src.isBlank()) continue

                val (m3u8, subs) = resolveServer(src, srvName)
                if (m3u8 != null) {
                    allSources += m3u8
                    for (sub in subs) {
                        allSubs.putIfAbsent(sub.lang, sub)
                    }
                    ExtLog.log("yoy", "resolved via $srvName")
                    break
                }
                ExtLog.log("yoy", "server $i ($srvName) failed, trying next")
            }

            if (allSources.isEmpty()) {
                ExtLog.log("yoy", "all servers failed")
                return ResolveResult.EMPTY
            }

            ResolveResult(
                ok = true,
                sources = allSources,
                subtitles = allSubs.values.toList(),
            ).dedupe()
        } catch (t: Throwable) {
            ExtLog.log("yoy", "THREW ${t.javaClass.simpleName}: ${t.message}")
            ResolveResult.EMPTY
        }
    }

    /**
     * Resolves one server from the yoy4 server list.
     *
     * @return the m3u8 source + subtitles, or (null, emptyList()) on failure.
     */
    private fun resolveServer(src: String, srvName: String): Pair<ResolvedSource?, List<ResolvedSubtitle>> {
        return try {
            val host = try {
                java.net.URI(src).host?.lowercase() ?: ""
            } catch (_: Throwable) { "" }

            if (host.contains("kisskh")) {
                resolveKisskh(src)
            } else if (host.contains("megavid")) {
                resolveMegavid(src)
            } else {
                ExtLog.log("yoy", "unknown host: $host")
                null to emptyList()
            }
        } catch (t: Throwable) {
            ExtLog.log("yoy", "resolveServer THREW: ${t.javaClass.simpleName} ${t.message}")
            null to emptyList()
        }
    }

    /**
     * Resolves a kisskh.megaplay.su server.
     * The player page has the m3u8 and .srt tracks directly in the HTML.
     */
    private fun resolveKisskh(src: String): Pair<ResolvedSource?, List<ResolvedSubtitle>> {
        val playerHtml = Http.get(
            src,
            headers = mapOf("User-Agent" to UA, "Referer" to PLAYER_REFERER),
        ).text

        val m3u8Url = Regex("https://kisskh\\.megaplay\\.su/vid/[^\"\\s<]+\\.m3u8")
            .find(playerHtml)?.groupValues?.get(0)
        if (m3u8Url == null) {
            ExtLog.log("yoy", "kisskh: no m3u8 in player HTML (${playerHtml.length} B)")
            return null to emptyList()
        }

        // Probe the m3u8 with the self-referer.
        val probe = Http.get(
            m3u8Url,
            headers = mapOf("User-Agent" to UA, "Referer" to KISSKH_REFERER),
        )
        if (probe.code != 200) {
            ExtLog.log("yoy", "kisskh: m3u8 probe HTTP ${probe.code}")
            return null to emptyList()
        }
        val playlist = probe.text
        if (!playlist.contains("#EXTM3U")) {
            ExtLog.log("yoy", "kisskh: playlist is not m3u8 (${playlist.length} B)")
            return null to emptyList()
        }

        val subs = parseKisskhSubs(playerHtml)
        val quality = playlist.qualityLabel()

        val source = ResolvedSource(
            name = "$NAME ${quality}".trim(),
            url = m3u8Url,
            referer = KISSKH_REFERER,
            quality = 0,
            qualityLabel = quality,
            type = "hls",
            headers = mapOf("Referer" to KISSKH_REFERER),
        )
        return source to subs
    }

    /**
     * Resolves a megavid.buzz server.
     * The player page is an SPA that fetches /source for JSON.
     */
    private fun resolveMegavid(src: String): Pair<ResolvedSource?, List<ResolvedSubtitle>> {
        // The /source endpoint is relative to the player page.
        val sourceUrl = "$src/source"
        val resp = Http.get(
            sourceUrl,
            headers = mapOf("User-Agent" to UA, "Referer" to src),
        )
        if (resp.code != 200) {
            ExtLog.log("yoy", "megavid: /source HTTP ${resp.code}")
            return null to emptyList()
        }
        val json = JSONObject(resp.text)
        if (json.optString("status") != "ok") {
            val msg = json.optString("message", "unknown")
            ExtLog.log("yoy", "megavid: source status=$msg")
            return null to emptyList()
        }
        val m3u8Url = json.optString("source", "")
        if (m3u8Url.isBlank()) {
            ExtLog.log("yoy", "megavid: no source URL")
            return null to emptyList()
        }

        // Probe the m3u8.
        val probe = Http.get(
            m3u8Url,
            headers = mapOf("User-Agent" to UA, "Referer" to MEGAVID_REFERER),
        )
        if (probe.code != 200) {
            ExtLog.log("yoy", "megavid: m3u8 probe HTTP ${probe.code}")
            return null to emptyList()
        }
        val playlist = probe.text
        if (!playlist.contains("#EXTM3U")) {
            ExtLog.log("yoy", "megavid: playlist is not m3u8 (${playlist.length} B)")
            return null to emptyList()
        }

        // Parse subtitle tracks from the JSON.
        val subs = mutableListOf<ResolvedSubtitle>()
        val tracks = json.optJSONArray("tracks")
        if (tracks != null) {
            for (i in 0 until tracks.length()) {
                val t = tracks.getJSONObject(i)
                val file = t.optString("file", "")
                val label = t.optString("label", "")
                if (file.isBlank() || label.isBlank()) continue
                // Map label to language code.
                val code = labelToCode(label)
                if (code == null) continue
                if (code in langNames) {
                    subs += ResolvedSubtitle(
                        lang = langNames[code] ?: code,
                        url = file,
                        trust = if (code == "en") 2 else 1,
                        headers = mapOf("Referer" to MEGAVID_REFERER),
                    )
                }
            }
        }

        val quality = playlist.qualityLabel()
        val source = ResolvedSource(
            name = "$NAME ${quality}".trim(),
            url = m3u8Url,
            referer = MEGAVID_REFERER,
            quality = 0,
            qualityLabel = quality,
            type = "hls",
            headers = mapOf("Referer" to MEGAVID_REFERER),
        )
        return source to subs
    }

    /**
     * Parses .srt URLs from the kisskh player HTML.
     */
    private fun parseKisskhSubs(html: String): List<ResolvedSubtitle> {
        val subs = LinkedHashMap<String, ResolvedSubtitle>()
        val srtRe = Regex("https://kisskh\\.megaplay\\.su/sub/[^\"\\s<]+\\.srt")
        val langOfRe = Regex("\\.([a-z]{2,3})\\.srt$")
        for (m in srtRe.findAll(html)) {
            val url = m.groupValues[0]
            val code = langOfRe.find(url)?.groupValues?.get(1) ?: continue
            if (code !in langNames) continue
            if (subs.containsKey(code)) continue
            subs[code] = ResolvedSubtitle(
                lang = langNames[code] ?: code,
                url = url,
                trust = if (code == "en") 2 else 1,
                headers = mapOf("Referer" to KISSKH_REFERER),
            )
        }
        return subs.values.toList()
    }

    /**
     * Maps a human-readable label ("English", "Arabic") to a 2-letter code.
     */
    private fun labelToCode(label: String): String? {
        val lower = label.lowercase()
        return when {
            lower.contains("english") -> "en"
            lower.contains("arabic") -> "ar"
            lower.contains("indonesia") || lower.contains("indonesian") -> "id"
            lower.contains("malay") -> "ms"
            lower.contains("khmer") -> "km"
            lower.contains("dutch") -> "nl"
            lower.contains("thai") -> "th"
            lower.contains("vietnam") || lower.contains("vietnamese") -> "vi"
            lower.contains("chinese") -> "zh"
            lower.contains("spanish") -> "es"
            lower.contains("french") -> "fr"
            lower.contains("german") -> "de"
            lower.contains("portuguese") -> "pt"
            lower.contains("russian") -> "ru"
            lower.contains("japanese") -> "ja"
            lower.contains("korean") -> "ko"
            lower.contains("turkish") -> "tr"
            else -> null
        }
    }

    /** Best-effort "1080p"-style label from the playlist's EXT-X-STREAM-INF
     *  entries; empty for a single-quality media playlist. */
    private fun String.qualityLabel(): String {
        val m = Regex("RESOLUTION=\\d+x(\\d+)").find(this) ?: return ""
        val h = m.groupValues[1].toIntOrNull() ?: return ""
        return when {
            h >= 2000 -> "${h / 1000}k"
            h >= 1000 -> "${h}p"
            else -> ""
        }
    }
}
