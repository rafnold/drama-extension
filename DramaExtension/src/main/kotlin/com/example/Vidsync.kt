package com.example

import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared Vidsync (vidsync.pro) extraction-API logic.
 *
 * The extraction session endpoint returns JSON lines; each
 * "provider-result" line carries one backend provider (castle, vidsrc,
 * kisskh, ...) with its own sources + subtitles. Used by KDramaIn and by
 * any future provider that embeds vidsync players.
 */

/** One playable stream advertised by a vidsync backend provider. */
data class VidsyncSource(
    val provider: String,
    val trust: Int,
    val displayName: String,
    val quality: String,
    val qualityRank: Int,
    val type: String,
    val url: String,
    /** Dub label; null = original audio (or multi-audio with original). */
    val audioLabel: String?,
)

/** One cached curation for a (type, id, season, episode) request. */
class VidsyncCuration(
    val sources: List<VidsyncSource>,
    /** (trust, lang, url) subtitle candidates, most-trusted first after sort. */
    val subtitles: List<Triple<Int, String, String>>,
    val fetchedAt: Long = System.currentTimeMillis(),
)

object Vidsync {
    val BASE: String get() = SiteConfig.vidsyncBase()
    val API: String get() = SiteConfig.vidsyncApi()

    /** Reliability ranking of vidsync backend providers. 0 = junky
     *  sources (gambling-site streams, wrong-language dubs); only used
     *  as a last resort when no reliable provider has anything. */
    val PROVIDER_TRUST = mapOf(
        "castle" to 3,
        "vidsrc" to 2,
        "vidsrc-rescrape" to 2,
        "kisskh" to 1,
    )

    /**
     * Parses the vidsync JSON-lines response. provider-result lines carry a
     * nested "sources" array whose objects contain nested objects ("headers",
     * "audioTracks", ...), so each line is parsed with a real JSON parser.
     * Sources that resolve to a different season/episode are skipped
     * (vidsync sometimes maps an episode to a wrong file).
     */
    fun parseCuration(text: String, season: Int?, episode: Int?): VidsyncCuration {
        val outCandidates = mutableListOf<VidsyncSource>()
        val outSubs = mutableListOf<Triple<Int, String, String>>()
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
                if (url.startsWith("/")) url = "$BASE$url"
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
                outCandidates += VidsyncSource(
                    provider = provider,
                    trust = trust,
                    displayName = displayName,
                    quality = quality,
                    qualityRank = ResolverCrypto.qualityRank(quality),
                    type = s.optString("type"),
                    url = url,
                    audioLabel = audioLabel,
                )
                // Per-source subtitles (e.g. kisskh)
                collectSubs(s.optJSONArray("subtitles") ?: JSONArray(), trust, outSubs)
            }
            // Provider-level subtitles (e.g. castle, vidsrc)
            collectSubs(obj.optJSONArray("subtitles") ?: JSONArray(), trust, outSubs)
        }
        return VidsyncCuration(outCandidates, outSubs)
    }

    /**
     * Picks the extractor links from the parsed candidates: reliable
     * providers first (everything as a last resort), dedupe per
     * (provider, audio, quality) keeping the first occurrence, then sort
     * (original audio first, then trust, then quality). vidsrc-rescrape
     * URLs are relay-wrapped and more robust than the direct vidapi.cloud
     * ones, so the direct twin is dropped when both exist.
     */
    fun selectLinks(candidates: List<VidsyncSource>): List<VidsyncSource> {
        val reliable = candidates.filter { it.trust > 0 }
        val pickedAll = if (reliable.isNotEmpty()) reliable else candidates
        val picked = pickedAll.filterNot { c ->
            c.provider == "vidsrc" && pickedAll.any {
                it.provider == "vidsrc-rescrape" &&
                    it.qualityRank == c.qualityRank && it.audioLabel == c.audioLabel
            }
        }
        val deduped = linkedMapOf<String, VidsyncSource>()
        for (c in picked) {
            val key = "${c.provider}|${c.audioLabel ?: "orig"}|${c.quality}"
            if (!deduped.containsKey(key)) deduped[key] = c
        }
        return deduped.values
            .sortedWith(compareByDescending<VidsyncSource> { it.audioLabel == null } // original audio first
                .thenByDescending { it.trust }
                .thenByDescending { it.qualityRank }
                .thenBy { it.quality.lowercase() })
    }

    private fun collectSubs(
        subs: JSONArray,
        trust: Int,
        out: MutableList<Triple<Int, String, String>>,
    ) {
        for (i in 0 until subs.length()) {
            val s = subs.optJSONObject(i) ?: continue
            var subUrl = s.optString("file").ifBlank { s.optString("url") }
            if (subUrl.startsWith("/")) subUrl = "$BASE$subUrl"
            if (!subUrl.startsWith("http")) continue
            out += Triple(trust, s.langCodeFromAny() ?: "", subUrl)
        }
    }

    /** Resolves a subtitle entry's language: code field -> file name -> label. */
    private fun JSONObject.langCodeFromAny(): String? {
        val fromField = optString("language")
        if (fromField.length in 2..3 && fromField.all { it.isLetter() }) return fromField
        val fileName = optString("file").ifBlank { optString("url") }
            .substringAfterLast('/')
        for (suf in listOf(".vtt", ".srt", ".ass", ".ssa")) {
            if (fileName.endsWith(suf)) return ResolverCrypto.langCode(fileName.removeSuffix(suf))
        }
        return ResolverCrypto.langCode(fileName) ?: ResolverCrypto.langCode(optString("label"))
    }
}
