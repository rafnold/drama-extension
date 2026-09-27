# TODO.md — Session handoff (updated 2026-09-27)

Read `AI_RULES.md` first — it contains the binding working agreements (complete code only,
live-verify before coding, fallback discipline, verification gate before commit).

## Current status: **v5 RELEASED + live-verified** (2026-09-27) — nothing pending

- **Live (v5, commit `de00be1` on `main`, CI run SUCCESS 2026-09-27):**
  - `DramaNice` — All tab = paginated image listing, poster fix (since v4).
  - `KDramaIn` — VidSync-API source curation, subtitles via `newSubtitleFile` (since v4).
  - `KissAsian` — 8 main tabs (Popular + 7 countries), WP REST search, runtime
    `player_source.php` M3U8 chain, subApi subtitles (new in v5).
- **Release gate (all steps verified 2026-09-27, in the mandatory order):**
  1. Live harness (`/tmp/harness-proj`): 8/8 tabs real country-specific cards + posters
     (kr 58, cn 45, jp 62, th 59, hk 28, tw 27, ph 38, popular 47; page 2 works);
     `search("bias")` -> 2 REST results with real posters; load + loadLinks + subtitles
     green on KR/CN/TH/HK/TW samples (2 M3U8 links per episode, dramav2 + drama3 CDNs,
     + >=1 EN sub); movies (e.g. Cherry Magic 2020) fall back to single-episode movie load.
  2. Regression green: DramaNice (16 eps, 1 link, 6 subs), KDrama.in (all tabs;
     search 14 results).
  3. Build: `./gradlew make makePluginsJson` -> `DramaExtension/build/DramaExtension.cs3`
     (60725 bytes) + `build/plugins.json` (version 5).
  4. Push -> CI SUCCESS on `de00be1`; `builds` branch force-pushed to `67695bd`;
     live `builds/plugins.json` reports `"version": 5` (description lists all three
     providers); live `.cs3` `fileHash` == local build sha256 3b1a2566a31454bb32feb8ece929c4b74377d3ce65484a725c4396d0c9380f62
     (size 60725 matches too).
- **Remaining (user-side only):** hit "Update" in the CloudStream app
  (Settings -> Extensions -> refresh) to pick up v5; confirm KissAsian tabs stream
  + subtitles on device. No code work pending.
- **Do NOT re-derive**: site facts, stub-API quirks, and redaction handling below are
  verified and encoded in `KissAsian.kt` + `AI_RULES.md`; re-verify only if stale.

### What `KissAsian.kt` contains (final state, ~433 lines, all verified)
- Header KDoc with the full verified video chain.
- Class setup: `name`, `mainUrl = "https://wwv21.kissasian.com.lv/"`, `hasMainPage`,
  `supportedTypes = {AsianDrama, Movie}`, `lang = "en"`,
  `mainPage = mainPageOf("popular"->Popular, "kr"->K-Dramas, "cn", "jp", "th", "hk", "tw", "ph")`.
- Companion: `UA`, `REST`, regexes (`srcRe`, `srcCdnListRe`, `subApiRe`, `m3u8Re`, `epRe`,
  `epParamRe` = `[?&]episode=(\d+)`, `originRe` = `https?://[^/]+`), `countryPaths`
  (south-korea, china, japan, thailand, hong-kong, taiwan, philippines), `langNames` map,
  `String?.langCode()` helper.
- `toAbsoluteUrl()`, `Document.toCards()` (cards = `a[href*=/series/]`, title from inner `h3`,
  poster `img[data-original]`/`src`, dedupe, skips `-episode-` URLs).
- `getMainPage()` — `popular` -> `/most-popular-drama/`, others -> `/country/<path>/`,
  pagination `/page/N/` (all verified 200).
- `search()` + `searchRest()` — WP REST `series?search=<q>&per_page=20&_fields=link,title,featured_media`
  + batch poster resolve via `media?include=<ids>&_fields=id,source_url`; fallback scans
  `/drama-list/page/1..6/` and filters titles locally.
- `load()` — `h1` (name+year), poster `.details .img img` (og:image fallback only),
  `.info p` label/value map, episodes from `ul.list-episode-item-2.all-episode a`
  (+ `ul.all-episode a`, `a[href*=-episode-]`), sorted by episode number;
  `newTvSeriesLoadResponse` (with `showStatus` Ongoing/Completed) with `newMovieLoadResponse` fallback.
- `loadLinks()` — embed page `li[data-video]` (iframe fallback) ->
  1) runtime `player_source.php?episode=N` (JSON `list` -> M3U8 set, `src` fallback),
  2) legacy in-page fallbacks (`var srcCdnList`, `var src`, raw-m3u8 regexes);
  subtitles from the player page's `subApi` (Referer = embed URL required),
  lang chain: `lang` -> `format` -> filename token -> `"en"`; one M3U8 extractor link
  per CDN (CDN #1 first), each with embed referer + UA headers.

### Verified live-site facts (re-verify before trusting if stale; all curl-confirmed 2026-09-27)
- Episode page: `li.Standard Server.selected[data-video]` ->
  `https://catalog.dramavibe.cfd/player_embed.php?episode=N` (absolute URL).
- Embed page: `var src = ''`, `var srcCdnList = []` (deliberately empty — "light embed deterrence");
  contains `var subApi = "https://storage.dramavibe.cfd/api/public/video/<uuid>/subtitles?client=cdn2"`.
- Streams: `GET https://catalog.dramavibe.cfd/player_source.php?episode=N` (works with or without
  referer) -> `{"ok":true,"src":"https://cdn.dramav2.xyz/<uuid>/video.m3u8",
  "list":["https://cdn.dramav2.xyz/<uuid>/video.m3u8","https://cdn.drama3.click/<uuid>/video.m3u8"]}`.
  Playlist is a valid HLS VOD. M3U8 + segments fetched fine with `referer = <embed url>`.
- Subtitles: subApi requires **Referer = embed page URL** (else `{"ok":false,"error":"Forbidden."}`);
  returns `[{"lang":"en","format":"srt","url":"https://cdn.drama3.click/uploads/subs/<uuid>.en.srt?st=...&e=..."}]`.
- Search: `?s=` is client-side rendered only (dead server-side). WP REST `search=` works
  (title search); `s=` param is ignored by REST (returns latest). Catalog = 477 series.
- Movies (e.g. `gameboys-the-movie-2021`) are single-episode series — the episodes path covers them.

## Useful artifacts in /tmp (may be gone after reboot — re-fetch if needed)
- `/tmp/harness-proj` — JVM live-verification harness as a gradle project
  (`./gradlew -p /tmp/harness-proj run`); `/tmp/harness/` — original harness sources
  (Harness.kt runs `listOf<MainAPI>(DramaNice(), KDramaIn(), KissAsian())`).
- `/tmp/ka-*.html/.js/.json` — captured KissAsian pages (drama-list, search page,
  episode pages, `player_source.php`/subApi JSON) and theme JS.
- `/tmp/cloudstream-903ef47`, `/tmp/cs-src`, `/tmp/csjar_check` — cloudstream sources /
  unpacked stub classes (`SubtitleFile`, `MainAPIKt$newSubtitleFile`).
- Build stub: `/root/.gradle/caches/cloudstream/cloudstream/cloudstream.jar` (the exact stub
  the extension compiles against).
- State doc: `drama-extension-state.md` (repo root) — full history incl. the v5 release.

## Next candidate work (optional — nothing pending)
- New providers from the sibling `drama-scraper/` project (same pattern: live-verify the
  chain -> harness -> gate).
- KissAsian: verify `/country/<path>/page/N/` beyond page 2; consider a movie archive tab
  (unverified).
- KDrama.in: vidsync.pro extraction API is intermittent (timeouts/rate-limits); harness
  tolerates 0-source runs — consider caching the last-good curation if flakes hit users.
