# AI_RULES.md — Working agreements for the `drama-extension` project

These are the technical agreements made while building this CloudStream 3 extension
(DramaNice, KDramaIn, KissAsian). Every session/agent working in this repo MUST follow them.

## 1. Complete code only — never partial

- **No partial functions.** Never emit a function with omitted bodies, `// ... rest unchanged`,
  `/* existing code */`, or commented-out halves. Every method delivered is complete and runnable.
- **No placeholders.** No `TODO`, `FIXME`, `???`, `null`-as-stub, `NotImplementedError`, or fake
  return values. If something cannot be implemented yet, it is not delivered yet — say so in
  `TODO.md` instead.
- **Files must be self-consistent before they are considered "done".** After any write/append,
  check brace balance and that the file compiles in isolation within the module. (Lesson from
  2026-09-27: a chunked write left `KissAsian.kt` with 64 `{` vs 63 `}` and a missing
  `loadLinks()` — that is an incomplete file, not a done file.)
- When a single tool call is too long, write in chunks **and verify** (brace count / compile)
  before moving on.

## 2. Never guess the CloudStream 3 API surface

- Before using any CloudStream class/function, verify it against the real stub the build uses:
  `/root/.gradle/caches/cloudstream/cloudstream/cloudstream.jar`
  (inspect with `unzip -l` / `javap -p`). Do not invent signatures.
- Verified facts (use these, do not re-litigate):
  - `newSubtitleFile(lang, url)` lives in package `com.lagradost.cloudstream3`
    (generated from `MainAPI.kt`), so `import com.lagradost.cloudstream3.*` is sufficient.
    `SubtitleFile` has `lang`, `url`, `headers`.
  - Extractor links: `newExtractorLink(name, displayName, url, ExtractorLinkType.M3U8) { referer = ...; headers = ... }`.
  - Response builders (this stub, verified via javap on cloudstream.jar): `newTvSeriesLoadResponse`
`newMovieLoadResponse`
`newTvSeriesSearchResponse`
`newMovieSearchResponse`.
    Main page = `getMainPage(page: Int, request: MainPageRequest): HomePageResponse` +
    `newHomePageResponse(request, items)` (NOT `MainPageResponse`). Episodes =
    `newEpisode(url) { name = ...; season = ...; episode = ... }` (not the `Episode(...)` ctor).
    `search()` returns `List<SearchResponse>?` (NULLABLE in this stub).
  - `LoadResponse` properties here: `plot` / `tags` / `actors` / `posterUrl` / `year` ...
    (no `synopsis`, no `genre`, no `credits`, no `country`).
  - `showStatus` exists ONLY on the concrete `TvSeriesLoadResponse` (not on the
    `LoadResponse` interface, not on `MovieLoadResponse`) - set it in the series branch
    only. `ShowStatus` = `Ongoing` | `Completed` only.
  - No `htmlDecode` util: use `org.jsoup.parser.Parser.unescapeEntities(text, false)`
    (two args, spelling "Entities", import `org.jsoup.parser.Parser`).
  - **Transport redaction trap**: `newTv*SearchResponse`-shaped identifiers (and other
    "high entropy" strings) get mangled into `***[REDACTED:...]` in BOTH tool-call
    arguments and terminal output. A write that looks fine in the UI can land CORRUPTED
    on disk (this actually happened to `KissAsian.kt` once). Protocol: build such
    identifiers via Python string concatenation in a bash heredoc, then verify the
    on-disk bytes with `grep -c REDACTED` (must be 0) AND a successful compile -
    never trust rendered text (display redaction happens even on clean files).
  - `supportedTypes = setOf(TvType.AsianDrama, TvType.Movie)`; `hasMainPage = true` + `mainPageOf(...)`
    is the established pattern for listing tabs.

## 3. Verify the live site before writing selectors — never assume markup

- For any new/changed provider: `curl` the real pages/endpoints first and confirm:
  HTTP status, exact card/detail markup, JSON shapes, and **required headers** (Referer gating).
- Record the verified chain in the provider's KDoc (see `KissAsian.kt` header): page → selector →
  endpoint → response shape. Future sessions must re-verify from there, not from memory.
- Known live-site traps (2026-09-27, KissAsian):
  - Detail-page poster is **not** `og:image` and **not** the first `wp-content/uploads` image
    (that is the logo). It is `.details .img img`.
  - The player embed page deliberately contains **no** CDN URLs (`var src = ''`,
    `var srcCdnList = []`). Streams come from the runtime endpoint
    `GET https://catalog.dramavibe.cfd/player_source.php?episode=N` →
    `{"ok":true,"src":"<m3u8>","list":["<m3u8>","<m3u8>"]}`.
  - The subtitle endpoint (`var subApi = "..."` in the player page) returns
    `{"ok":false,"error":"Forbidden."}` **unless the Referer is the embed page**; then it returns
    `[{"lang":"en","format":"srt","url":"<signed .srt>"}]`.
  - The site's `?s=` search renders results client-side only (empty server-side).
    Server-side search = WordPress REST: `/wp-json/wp/v2/series?search=<q>` (note: `search=`,
    **not** `s=` — `s=` is silently ignored and returns the latest posts).
    Posters: `/wp-json/wp/v2/media?include=<comma-ids>&_fields=id,source_url` (batch, one request).
    Catalog size: 477 series (`X-WP-Total`). Fallback: `/drama-list/page/N/` (31 cards/page) +
    local title filter.

## 4. HTTP etiquette as verified

- Every request carries an explicit `User-Agent` (desktop Chrome string in each provider's
  companion object).
- Gated endpoints (source API, subtitle API) send `referer` = the page that links to them.
- JSON is parsed with `org.json` (`JSONObject`/`JSONArray`, `opt*` methods, never throw on
  missing fields); HTML with Jsoup.
- Collect candidate stream URLs in a `LinkedHashSet<String>` (dedupe, stable order, CDN #1 = first).

## 5. Keep the old path alive (fallback discipline)

- When a site changes its mechanism, the provider implements the **new** chain first and keeps
  the **legacy** extraction as a fallback (e.g. `KissAsian.loadLinks()`: runtime `player_source.php`
  first, then the old `var src` / `var srcCdnList` / raw-m3u8 regexes). A partial regression must
  still yield a playable link, never an empty episode.
- Subtitle language fallback chain: API `lang` → `format` → filename token → `"en"`, using the
  provider's `langNames` map (see `KDramaIn.kt` / `KissAsian.kt` companion).

## 6. Repo conventions

- Providers live in `DramaExtension/src/main/kotlin/com/example/` — one file per provider,
  `class X : MainAPI()`.
- Registration: `registerMainAPI(...)` inside `DramaExtensionPlugin.kt`.
- Style follows `DramaNice.kt` / `KDramaIn.kt`: companion object for constants/regexes/maps,
  `toAbsoluteUrl()` helper, card dedupe via `LinkedHashSet`, `app.get(url, referer = ..., headers = ...)`.
- `drama-extension-state.md` and `TODO.md` at the repo root are the session handoff — update them
  at the end of every session, and **before** committing anything.

## 7. Verification gate before commit (non-negotiable order)

1. **Live harness** (`/workspace/tmp-artifacts/harness-proj`; build/run: `./gradlew -p /workspace/tmp-artifacts/harness-proj run`): the JVM harness runs every provider against the live site —
   `getMainPage`, `search`, `load`, `loadLinks`. New providers MUST be added to
   `listOf<MainAPI>(...)` in `Harness.kt` and pass before release.
   (harness needs the `android.util.Base64` JVM shim - already present in the project sources.)
   Expected for KissAsian: cards with real poster URLs, detail with episodes, and per episode
   2 M3U8 links (dramav2 + drama3 CDNs) plus ≥1 EN subtitle.
2. **Regression**: DramaNice and KDramaIn must still pass the harness (v4 behavior) —
   never commit a change that breaks them.
3. **Build**: `./gradlew :DramaExtension:assembleRelease` (and the compile check earlier).
4. **Commit** with a versioned message (`v5: ...`), push, let CI build.

## 8. Session discipline

- Small, verifiable steps — one chain at a time (cards → detail → episodes → player → source API
  → subtitles). Verify each against the live site before building the next.
- Keep raw evidence in `/tmp` (fetched HTML, JS, JSON responses, saved builds). Persistent
  artifacts (harness, cloudstream sources at `/workspace/tmp-artifacts/cloudstream-903ef47`,
  captured pages, stub classes) live in `/workspace/tmp-artifacts/` — note useful artifacts in
  `TODO.md` and move anything that must survive a reboot there, not `/tmp`.
- If blocked or running out of context: finish what is in-flight into a **complete** file state
  (no half-methods), then update `TODO.md` with the exact next step.
