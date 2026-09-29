# TODO.md — Session handoff (updated 2026-09-29)

Read `AI_RULES.md` first — it contains the binding working agreements (complete code only,
live-verify before coding, fallback discipline, verification gate before commit).

## Current status: **v6 RELEASED + live-verified** (2026-09-29) — nothing pending

All three pending items are implemented and live-verified (harness run 2026-09-29, exit 0,
no failures across all 4 providers):

1. **KissAsian** — pagination beyond page 2 verified live (tw p3 → clean 404 → 0 cards;
   kr p3 → 36 real cards); new **Latest** tab from `/recently-added-movie/` (27 cards,
   different markup, own parser); `toCards()` now requires the card's own
   `<h3 class="title">` so home-page widget leakage can never happen (home page has 0 `<h3>`).
2. **KDrama.in** — last-good curation cache: parsed curation per episode is cached in memory
   (12 h TTL, 256-entry cap); when vidsync.pro flakes, `loadLinks` falls back to the cached
   sources instead of 0 links. Reflection-tested in the harness (HIT/MISS/EXPIRED).
3. **Dramahood** (dramahood.mom) — new provider, fully live-reverse-engineered before coding:
   4 catalog tabs (drama, kshow + two "latest releases" archives), `?s=` search,
   series/episode parsing, and all three player chains decrypted to m3u8 (dramavideo.se
   AES hex-key, embedload.cfd→zokoanime.video XOR+base64, vidbasic.top AES fixed key;
   empty players handled). See `Dramahood.kt` KDoc + README "Dramahood chain".
   Known CDN quirk: zoko's m3u8 CDN (`hls.aniwatch.al`) serves an incomplete Cloudflare
   Origin CA chain — strict TLS clients fail the handshake; link + referer are correct.


- **Live (v6, commit `ca67bd9` on `main`, CI build pushed to `builds` 2026-09-29, branch `306319d`):**
  - `DramaNice` — All tab = paginated image listing, poster fix (since v4).
  - `KDramaIn` — VidSync-API source curation, subtitles via `newSubtitleFile` (v4);
    last-good curation cache against vidsync.pro flakes (v6).
  - `KissAsian` — 9 main tabs (Popular + 7 countries + Latest), WP REST search,
    runtime `player_source.php` M3U8 chain, subApi subtitles (v5); h3-strict card
    parser guards against home-page widget leakage (v6).
  - `Dramahood` — new (v6): 4 tabs, `?s=` search, 3 decrypted player chains → m3u8.
- **Release gate v6 (all steps verified 2026-09-29, in the mandatory order):**
  1. Live harness (`/workspace/tmp-artifacts/harness-proj`): 4/4 providers green (details below).
  2. Build: `./gradlew make makePluginsJson` → `DramaExtension/build/DramaExtension.cs3`
     (85384 bytes, sha256 `8704ca30f71872597042059c26ab675337be1d9d11d2dadbafb6e32e3a034433`)
     + `build/plugins.json` (version 6).
  3. Push `ca67bd9` → CI build → `builds` force-pushed to `306319d`; live
     `builds/plugins.json` reports `"version": 6` (description lists all four
     providers); live `.cs3` sha256 == local build (8704ca30…, 85384 bytes).
- **Remaining (user-side only):** hit "Update" in the CloudStream app to pick up v6;
  confirm Dramahood tabs stream on device (zoko-chain episodes need a lenient-TLS
  player — the CDN cert chain is broken on their side).

### v6 harness evidence (run 2026-09-29, exit 0)
- KissAsian: 9 tabs green (kr 36, cn 34, jp 36, th 36, hk 2, tw 36, ph 12, popular 36,
  **Latest 27** with posters); `search("bias")` → 2 REST results;
  movie page → 1 episode → 2 M3U8 + 1 sub; series (The Scandal 2026, My Bias My Boss)
  → 2 M3U8 + 1 sub each; tw p3 → 0 (404 over-run guard); kr p3 → 36 cards.
- KDrama.in: all 6 tabs + movie tab green (movie chain 11 links + 3 subs);
  cache reflection test: HIT (9 sources from cache), MISS, stale entry → EXPIRED;
  3 rapid repeats all ok.
- DramaNice: all tabs green (16 eps, 2 links).
- Dramahood: 4 tabs green (drama 10, kshow 10, latest-drama 6 deduped, latest-kshow 8);
  p2 overlap=0, p99 → 0; `search("scandal")` → 10; load The Scandal → 8 eps ascending,
  tags `[Historical, Romance, Drama, Melodrama, Korean]`, status Ongoing, year 2026;
  chains: vidbasic PASS (2 M3U8, `stream.vidbasic.top`, both verified), dramavideo
  PASS (1 M3U8, `hls.dramavideo.se`, verified), embedload PASS (1 M3U8, link correct;
  m3u8 fetch = SSL handshake failure on `hls.aniwatch.al` — incomplete Cloudflare
  Origin CA chain, accepted by design); empty player episode → ok=false, 0 links.

## Previous: v5 — RELEASED + live-verified (2026-09-27)
- **Live (v5, commit `de00be1` on `main`, CI run SUCCESS 2026-09-27):**
  - `DramaNice` — All tab = paginated image listing, poster fix (since v4).
  - `KDramaIn` — VidSync-API source curation, subtitles via `newSubtitleFile` (since v4).
  - `KissAsian` — 8 main tabs (Popular + 7 countries), WP REST search, runtime
    `player_source.php` M3U8 chain, subApi subtitles (new in v5).
- **Release gate (all steps verified 2026-09-27, in the mandatory order):**
  1. Live harness (`/workspace/tmp-artifacts/harness-proj`): 8/8 tabs real country-specific cards + posters
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
  returns `[{"lang":"en","format":"srt","url":"https://storage.dramavibe.cfd/uploads/subs/<uuid>.en.srt?st=...&e=..."}]`.
  Sub CDN host rotates (v5: `cdn.drama3.click`; 2026-09-27 re-run: `storage.dramavibe.cfd`) —
  the URL comes from the API response, so no code change needed; re-verify only if subs break.
- Search: `?s=` is client-side rendered only (dead server-side). WP REST `search=` works
  (title search); `s=` param is ignored by REST (returns latest). Catalog = 477 series.
- Movies (e.g. `gameboys-the-movie-2021`) are single-episode series — the episodes path covers them.

## Useful artifacts (persistent — moved out of `/tmp` on 2026-09-28)
Survives reboots: `/workspace/tmp-artifacts/`. Run the harness with
`./gradlew -p /workspace/tmp-artifacts/harness-proj run` from the repo root.
- `/workspace/tmp-artifacts/harness-proj` — JVM live-verification harness as a gradle
  project; `/workspace/tmp-artifacts/harness/` — original harness sources (Harness.kt runs
  `listOf<MainAPI>(DramaNice(), KDramaIn(), KissAsian(), Dramahood())` plus per-provider
  extra-check sections) + `cp.txt` classpath. The harness compiles from its own verbatim
  copies of the provider sources — sync them after repo edits.
- `/workspace/tmp-artifacts/ka-*.html/.js/.json` — captured KissAsian pages (drama-list,
  search page, episode pages, `player_source.php`/subApi JSON) and theme JS.
- `/workspace/tmp-artifacts/cloudstream-903ef47`, `/workspace/tmp-artifacts/cs-src`,
  `/workspace/tmp-artifacts/csjar_check` — cloudstream sources / unpacked stub classes
  (`SubtitleFile`, `MainAPIKt$newSubtitleFile`).
- `/workspace/tmp-artifacts/hj/` — extra JVM jars on the harness classpath (json,
  jspecify, kotlinx-serialization, cryptography); `harness-json.jar` +
  `harness-jspecify.jar` + `jsouptest/jsoup-1.22.1.jar` alongside.
- `/workspace/tmp-artifacts/plugin-fresh` — cloudstream gradle plugin source @ `32895ae`
  (the published commit; `./gradlew publishToMavenLocal` to rebuild mavenLocal).
- Build stub: `/root/.gradle/caches/cloudstream/cloudstream/cloudstream.jar` (the exact stub
  the extension compiles against).
- State doc: `drama-extension-state.md` (repo root) — full history incl. the v5 release.

## Next candidate work (optional — nothing pending)
- More providers from the sibling `drama-scraper/` project (same pattern: live-verify the
  chain -> harness -> gate). Remaining SITES: EverythingMoe, GoPlay, Einthusan, KissKH
  (.ovh / .dk), AsianCrush, OnDemandChina, Dramafren, MyAsianTV, Asiaflix, Rive, Vidbox,
  KissAsian.video.
- Dramahood: the zoko chain's m3u8 CDN (`hls.aniwatch.al`) has an incomplete cert chain —
  re-check whether it's been fixed before tightening the harness expectation.
