# TODO.md — Session handoff (updated 2026-09-27)

Read `AI_RULES.md` first — it contains the binding working agreements (complete code only,
live-verify before coding, fallback discipline, verification gate before commit).

## Current status: **KissAsian provider (v5) COMPLETE + verified** (2026-09-27)

- **Committed (v4, `11a13a4` on `main`)** — working and released:
  - `DramaNice` — All tab = paginated image listing, poster fix.
  - `KDramaIn` — VidSync-API source curation, subtitles via `newSubtitleFile`.
- **Done this session (v5, ready to commit):**
  - `KissAsian.kt` fully written (~433 lines) and **compiles clean** (verified via
    gradle `compileDebugKotlin` AND the JVM harness).
  - Fixed during the session: correct stub-API names (`newTvSeriesSearchResponse`,
    `getMainPage(page, request: MainPageRequest): HomePageResponse` +
    `newHomePageResponse`, `newEpisode(url) { ... }`, `plot`/`tags`,
    `showStatus` only on the TvSeries branch, `Parser.unescapeEntities(x, false)`),
    and the **`/country/` URL prefix** for country tabs (bare `/south-korea/`
    301-redirects to a generic 31-card widget page — that was the bug that made
    all country tabs look identical/posterless).
  - `DramaExtensionPlugin.kt` line 13: `registerMainAPI(KissAsian())`.
  - `build.gradle.kts`: `version = 5` + updated description.
- **Live-verified end-to-end in the JVM harness** (`/tmp/harness-proj`,
  `./gradlew -p /tmp/harness-proj run` — recipe details in
  `drama-extension-state.md` §1 v5 entry):
  - 8/8 main tabs: real country-specific cards with posters (kr 58, cn 45,
    jp 62, th 59, hk 28, tw 27, ph 38, popular 47; page2 works).
  - `search("bias")` -> 2 REST results with real posters.
  - load + loadLinks + subtitles green on KR/CN/TH/HK/TW samples
    (2 M3U8 links + EN sub each). Movies (e.g. Cherry Magic 2020) fall back to
    single-episode movie load.
  - Regressions green: DramaNice (16 eps, 1 link, 6 subs) and KDrama.in
    (all tabs; search 14 results).
  - `./gradlew make makePluginsJson` -> fresh `DramaExtension/build/
    DramaExtension.cs3` (manifest version 5, KissAsian classes in dex) +
    `build/plugins.json`.
- **Remaining steps (in order):**
  1. Commit (files: `KissAsian.kt`, `DramaExtensionPlugin.kt`,
     `build.gradle.kts`, `AI_RULES.md`, `TODO.md`) with a `v5: ...` message.
  2. Push to `main` -> CI (`build.yml`) rebuilds and force-pushes `builds`
     (`.cs3` + `plugins.json` + `repo.json`). `*.md` changes are paths-ignored
     by CI, so docs can ride along.
  3. Verify live `builds/plugins.json` reports `"version": 5` and the `.cs3`
     sha matches the local build; tell the user to hit "Update" in the app.
- **Do NOT re-derive**: everything below (site facts, API quirks, redaction
  handling) is verified and encoded in `KissAsian.kt` + `AI_RULES.md`.

### What `KissAsian.kt` contains (final state)
### What `KissAsian.kt` already contains (complete, verified logic)
- Header KDoc with the full verified video chain.
- Class setup: `name`, `mainUrl = "https://wwv21.kissasian.com.lv/"`, `hasMainPage`,
  `supportedTypes = {AsianDrama, Movie}`, `lang = "en"`,
  `mainPage = mainPageOf("popular"→Popular, "kr"→K-Dramas, "cn", "jp", "th", "hk", "tw", "ph")`.
- Companion: `UA`, `REST`, regexes (`srcRe`, `srcCdnListRe`, `subApiRe`, `m3u8Re`, `epRe`,
  `epParamRe` = `[?&]episode=(\d+)`, `originRe` = `https?://[^/]+`), `countryPaths`
  (south-korea, china, japan, thailand, hong-kong, taiwan, philippines), `langNames` map,
  `String?.langCode()` helper.
- `toAbsoluteUrl()`, `Document.toCards()` (cards = `a[href*=/series/]`, title from inner `h3`,
  poster `img[data-original]`/`src`, dedupe, skips `-episode-` URLs).
- `getMainPage()` — `popular` → `/most-popular-drama/`, others → `/country/<path>/`,
  pagination `/page/N/` (all verified 200).
- `search()` + `searchRest()` — WP REST `series?search=<q>&per_page=20&_fields=link,title,featured_media`
  + batch poster resolve via `media?include=<ids>&_fields=id,source_url`; fallback scans
  `/drama-list/page/1..6/` and filters titles locally.
- `load()` — `h1` (name+year), poster `.details .img img` (og:image fallback only),
  `.info p` label/value map, episodes from `ul.list-episode-item-2.all-episode a`
  (+ `ul.all-episode a`, `a[href*=-episode-]`), sorted by episode number;
  `newTvSeriesLoadResponse` (status/genre/cast/country/network) with `newMovieLoadResponse` fallback.

### Verified live-site facts (re-verify before trusting if stale; all curl-confirmed 2026-09-27)
- Episode page: `li.Standard Server.selected[data-video]` →
  `https://catalog.dramavibe.cfd/player_embed.php?episode=N` (absolute URL).
- Embed page: `var src = ''`, `var srcCdnList = []` (deliberately empty — "light embed deterrence");
  contains `var subApi = "https://storage.dramavibe.cfd/api/public/video/<uuid>/subtitles?client=cdn2"`.
- Streams: `GET https://catalog.dramavibe.cfd/player_source.php?episode=N` (works with or without
  referer) → `{"ok":true,"src":"https://cdn.dramav2.xyz/<uuid>/video.m3u8",
  "list":["https://cdn.dramav2.xyz/<uuid>/video.m3u8","https://cdn.drama3.click/<uuid>/video.m3u8"]}`.
  Playlist is a valid HLS VOD. M3U8 + segments fetched fine with `referer = <embed url>`.
- Subtitles: subApi requires **Referer = embed page URL** (else `{"ok":false,"error":"Forbidden."}`);
  returns `[{"lang":"en","format":"srt","url":"https://cdn.drama3.click/uploads/subs/<uuid>.en.srt?st=...&e=..."}]`.
- Search: `?s=` is client-side rendered only (dead server-side). WP REST `search=` works
  (title search); `s=` param is ignored by REST (returns latest). Catalog = 477 series.
- Movies (e.g. `gameboys-the-movie-2021`) are single-episode series — the episodes path covers them.

## Exact next step (one small task first, in order)

### Step 1 — Finish `KissAsian.kt`: append `loadLinks()` + the final class `}`
The file currently ends with the closing `    }` of `load()`. Append the complete method below
(then verify: braces 65/65, file compiles). This is the only missing code.

```kotlin
    // ------------------------------------------------------------------
    // Video + subtitles
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA)).document
        val embedUrl = doc.selectFirst("li[data-video]")?.attr("data-video")
            ?: doc.selectFirst("iframe[src*='dramavibe']")?.attr("src")
            ?: doc.selectFirst("iframe")?.attr("src")
            ?: return false
        val fullEmbed = toAbsoluteUrl(embedUrl) ?: return false

        val playerHeaders = mapOf("User-Agent" to UA)
        val playerHtml = try {
            app.get(fullEmbed, referer = url, headers = playerHeaders).text
        } catch (_: Throwable) {
            null
        }

        // 1) Runtime source resolution: player_source.php?episode=N
        val streamUrls = LinkedHashSet<String>()
        val epParam = epParamRe.find(fullEmbed)?.groupValues?.get(1)
        if (!epParam.isNullOrBlank() && playerHtml != null) {
            val origin = originRe.find(fullEmbed)?.value
            if (origin != null) {
                try {
                    val sourceUrl = "$origin/player_source.php?episode=$epParam"
                    val json = app.get(
                        sourceUrl, referer = fullEmbed, headers = playerHeaders
                    ).text
                    val obj = JSONObject(json)
                    val list = obj.optJSONArray("list")
                    if (list != null) {
                        for (i in 0 until list.length()) {
                            val u = list.optString(i)
                            if (u.startsWith("http")) streamUrls += u
                        }
                    }
                    if (streamUrls.isEmpty()) {
                        val src = obj.optString("src")
                        if (src.startsWith("http")) streamUrls += src
                    }
                } catch (_: Throwable) {
                    // fall through to the in-page fallbacks below
                }
            }
        }

        // 2) Legacy fallback: sources embedded directly in the player page.
        if (streamUrls.isEmpty() && playerHtml != null) {
            srcCdnListRe.find(playerHtml)
                ?.groupValues?.get(1)
                ?.let { raw -> m3u8Re.findAll(raw).forEach { streamUrls += it.value } }
            srcRe.find(playerHtml)
                ?.groupValues?.get(1)
                ?.takeIf { it.contains(".m3u8") }
                ?.let { streamUrls += it }
            m3u8Re.findAll(playerHtml).forEach { streamUrls += it.value }
        }
        if (streamUrls.isEmpty()) return false

        // Subtitles: subApi (signed, Referer-gated) from the player page.
        if (playerHtml != null) {
            val subApi = subApiRe.find(playerHtml)?.groupValues?.get(1)
            if (!subApi.isNullOrBlank()) {
                try {
                    val subsJson = app.get(
                        toAbsoluteUrl(subApi) ?: subApi,
                        referer = fullEmbed,
                        headers = playerHeaders
                    ).text
                    val arr = JSONArray(subsJson)
                    val seenSubs = LinkedHashSet<String>()
                    for (i in 0 until arr.length()) {
                        val s = arr.optJSONObject(i) ?: continue
                        val u = s.optString("url")
                        if (!u.startsWith("http") || !seenSubs.add(u)) continue
                        val lang = s.optString("lang").langCode()
                            ?: s.optString("format").langCode()
                            ?: u.substringBefore('?').substringAfterLast('/').langCode()
                            ?: "en"
                        subtitleCallback(newSubtitleFile(lang, url = u))
                    }
                } catch (_: Throwable) {
                    // no subtitles for this episode
                }
            }
        }

        var added = false
        for ((index, streamUrl) in streamUrls.withIndex()) {
            callback(
                newExtractorLink(name, "KissAsian ${index + 1}", streamUrl, ExtractorLinkType.M3U8) {
                    referer = fullEmbed
                    headers = mapOf("User-Agent" to UA)
                }
            )
            added = true
        }
        return added
    }
}
```

Note: `newSubtitleFile`'s second argument — check the stub
(`/root/.gradle/caches/cloudstream/cloudstream/cloudstream.jar`, `MainAPIKt`) for whether it is
`newSubtitleFile(lang, url)` positional or named; `KDramaIn.kt` (committed, compiles in v4) is
the reference for the exact call shape.

### Step 2 — Compile
`./gradlew :DramaExtension:compileDebugKotlin` (fix any signature mismatch vs. the v4 providers'
style — `KDramaIn.kt` is the ground truth for `newExtractorLink`/`newSubtitleFile` usage).

### Step 3 — Live harness (add KissAsian)
- `/tmp/harness/Harness.kt` line 19: change
  `listOf<MainAPI>(DramaNice(), KDramaIn())` → `listOf<MainAPI>(DramaNice(), KDramaIn(), KissAsian())`
  (harness dir: `/tmp/harness`; rebuild/run as before).
- Expect for KissAsian:
  - `getMainPage` popular + one country tab, pages 1–2: cards with real poster URLs
    (series jpgs from `wp-content/uploads`, **not** the logo).
  - `search("bias")` → "My Bias, My Boss (2026)" / "My Bias Is Showing?! (2025)" with posters.
  - `load(.../series/a-bona-fide-killer-2026/)` → 14+ episodes, poster = `l0z2x4_4f-1.jpg`,
    country South Korea, status Ongoing.
  - `loadLinks(.../a-bona-fide-killer-2026-episode-14/)` → **2 M3U8 extractor links**
    (dramav2 + drama3 CDNs) and **≥1 EN subtitle** (signed drama3.click .srt).
- Regression: DramaNice + KDramaIn must still pass (v4 behavior).

### Step 4 — Release gate (only after Step 3 passes)
1. `./gradlew :DramaExtension:assembleRelease`
2. Update `drama-extension-state.md` with the v5 notes.
3. `git add` both files, commit `v5: add KissAsian provider (REST search, player_source.php chains, subApi subtitles)`, push, CI.

## Useful artifacts in /tmp (may be gone after reboot — re-fetch if needed)
- `/tmp/harness` — JVM live-verification harness (`Harness.kt`).
- `/tmp/ka-*.html/.js/.json` — captured KissAsian pages (drama-list, search page, episode pages,
  `player_source.php`/subApi JSON) and theme JS.
- `/tmp/cloudstream-903ef47`, `/tmp/cs-src`, `/tmp/csjar_check` — cloudstream sources / unpacked
  stub classes (`SubtitleFile`, `MainAPIKt$newSubtitleFile`).
- Build: `/root/.gradle/caches/cloudstream/cloudstream/cloudstream.jar` (the exact stub the
  extension compiles against).
- State doc: `drama-extension-state.md` (repo root) — v4 history; refresh for v5 in Step 4.
