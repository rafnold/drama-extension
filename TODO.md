# TODO.md — Session handoff (updated 2026-10-01)

Read `AI_RULES.md` first — it contains the binding working agreements (complete
code only, live-verify before coding, fallback discipline, verification gate
before commit).

**Upgrade plan: `/workspace/UPGRADE.MD`** — the StreamPlay-derived task board
(UG-1..UG-14). Read it before starting any new feature work; update its task
Status columns as work lands; keep this file as the current-session handoff
only. Release history details live in `drama-extension-state.md` (gitignored).

## Current status: **v13 LIVE** (2026-10-02, commit `05add09`, builds ref `builds`)

5 providers: DramaNice, KDrama.in, KissAsian (13 tabs), Dramahood, KissKH
(9 tabs). Live `.cs3` 116,716 bytes sha256 `d559b4f1…` == local build;
`builds/plugins.json` version 13. v13 adds env-var overrides for the
remote-config secrets (see note below); device behavior unchanged.
v12 (commit `2da2ebf`) fixed the v11 empty-cards regression
(KissAsian + Dramahood zero cards).

Recent releases:
- **v14** (2026-10-03, commit `e005d3f`): new **Primeshows** provider (`primeshows.org`). Catalog/detail/episodes via the TMDB proxy (`/api/proxy/tmdb`), watch route `/watch/{movie|tv}/{tmdbId}`, sources via `api.wecollege.net` seed→`/miami/sources` + the ported `VidyDecrypt` (base64url ct → XOR PRNG keystream → `"mvm1"` magic → JSON `sources[]` → m3u8 with `referer=https://www.vidy.st/`). Fixes that unblocked it in the harness: (1) `VidyDecrypt.base64Decode` padding `(-len)%4` → `(4-len%4)%4` (Kotlin `%` keeps the sign, so a ct length %4∈{2,3} decoded empty → flaky magic mismatch, since ct length is non-deterministic); (2) harness `SiteConfig.kt` was missing the primeshows mirror → relative proxy URL; (3) standalone decrypt test used the wrong seed host and parsed the ct as JSON. Harness gate GREEN for Primeshows (catalog 20 cards, movie+TV load, TV loadLinks=3 links, decrypt PASS, live capture `Digger` e2eLinks=3). Remaining failure is **KDrama.in only** — pre-existing, blocked by a Cloudflare "Just a moment..." bot challenge (HTTP 403) on the harness HTTP client, unrelated to Primeshows and failing identically before these changes.
- **v13** (2026-10-02, commit `05add09`): env-var overrides for the
  remote-config secrets. The 6 resolver/secret accessors in `SiteConfig.kt`
  now honor per-machine environment variables (highest precedence:
  **env var > remote config.json > hardcoded defaults**);
  `VIDSYNC_API`, `VIDORA_PLAYER_KEY`, `ZOKO_XOR_SEEDS`,
  `VIDBASIC_AES_SEEDS`. On Android no such env vars exist, so device behavior
  is byte-for-byte unchanged (the accessors fall back to the config exactly
  as before). Purpose: a machine can pin a rotated mirror/seed/key without a
  config push, and ops can point every install at a mirror/vidsync endpoint
  without editing the public config. Verified: harness `EnvOverrideTestKt`
  (9/9 PASS with vars set; falls back to config when absent); extension
  compiles green; full live gate green except the 4 vidsync-pro **curation**
  tests, which failed only because `vidsync.pro` was unreachable from the
  build host (SocketTimeout — network, not code: no `VIDSYNC_API` was set so
  the curation path used the config URL exactly as v12). `grep -c REDACTED`
  = 0; no `eyJ` (TMDB JWT) in `classes.dex`.
- **v12** (2026-10-02, commit `2da2ebf`): v11 regression fix — KissAsian + Dramahood rendered
  only category tabs, zero cards on device. Root cause (found by diffing
  v10→v11, `git diff 1f7c69a 03b4d6d`): v11 moved `mainUrl`s to
  `SiteConfig.mirror()`, which returns the config value verbatim — the
  mirrors have NO trailing slash — while KissAsian/Dramahood still
  concatenated `"${mainUrl}$path/"` →
  `https://wwv21.kissasian.com.lvmost-popular-drama/` (DNS NXDOMAIN →
  exception → empty tab; every tab of both providers). The other 3
  providers join with an explicit `/` and were unaffected. Fix:
  `SiteConfig.mirror()` now always returns WITHOUT a trailing slash (with a
  doc comment recording the incident) and every provider joins paths with an
  explicit `/` — KissAsian getMainPage + search fallback, Dramahood
  getMainPage, plus latent same-class bugs: KDramaIn search
  (`mainUrl + "dramas.php"`) and DramaNice sitemap fetch (`"$mainUrl$file"`,
  would have 404'd search). Gate hardened: new "main pages must produce
  cards" FAIL check runs first for all 5 providers — v11's 55 PASS / 0 FAIL
  gate missed this because `capture()` silently skipped empty main pages.
- **v11** (2026-10-02): UG-3 — remote SiteConfig hot-patching. New `SiteConfig.kt`
  fetches `config.json` once per session (24 h on-disk TTL + ETag, fails open to
  hardcoded defaults). All 5 providers read `mainUrl` from `SiteConfig.mirror(...)`;
  6 resolvers read their seeds/keys/player-hosts/vidsync base from it (only the Vidora
  `x-player-key` path stays literal; TMDB_API kept hardcoded). Config repo =
  `rafnold/drama-extension/drama-config/config.json`. Harness `Gate.kt` gains
  `testSiteConfig` (mirror default/flip/dead-skip, fromJson round-trip + partial
  fail-open, fail-open on dead URL, on-disk 24 h TTL) — 15/15 PASS; full gate EXIT 0
  (55 PASS / 0 FAIL / 9 SKIP).
- **v10** (2026-10-01): UG-1/UG-2/UG-4/UG-5. Rewired all 5 providers onto a
  shared, non-suspend resolver layer (`Resolvers.resolveAll` under a
  `ResolveContext` budget; per-resolver 15 s cap, ~20 s total). Single
  dramavideo AES home in `DramavideoResolver.kt` (UG-1). Tiered cache
  (`Cache` + `TtlCache`), dead-tier short-circuit, `Net` conditional-GET
  ETag helpers, `ResolverCrypto` pure-Kotlin base64, 9 resolvers, `Vidsync`
  curation. UG-4 mix (dead+slow+good) verified in Gate.kt, EXIT 0. TMDB
  episode-count suffix now on-device only when `TMDB_TOKEN` is set. Harness
  `Gate.kt` (UG-1/UG-4/UG-5 live acceptance) all PASS.
- **v9** (2026-10-01): removed KissAsian Wuxia tab — its archive has only 2
  series (WP term count 49 = 2 series + 47 per-episode posts; verified via the
  site's own REST). KissAsian = 13 tabs.
- **v8** (2026-10-01): KissAsian +4 genre tabs, KissKH +6 genre tabs
  (fantasy/historical/romance/action/sci-fi/thriller), k-drama.in +Ranking/
  Watchlist + star-rating badge (Score) + TMDB " (N EP)" name suffix, DramaNice
  +K/C/J/Thai country tabs (single-page A-Z index, per-item country classes).
- **v7** (2026-09-30): KissKH provider (matrix-vault -> megaplay series chain
  with .srt subs; moviesapi/vidmoly movie chains).
- **v6** (2026-09-29): Dramahood provider (3 decrypted player chains),
  KDrama.in curation cache, KissAsian Latest tab.
- **v5** (2026-09-27): KissAsian provider. v4 (2026-09-26): posters + VidSync
  curation/subs. v1-v3 (2026-09-25/26): DramaNice + KDrama.in + runtime fixes.

## KDrama.in 403 / Cloudflare investigation (2026-10-03)

`https://k-drama.in` now answers **403 `cf-mitigated: challenge`** ("Just a
moment...", `challenge-platform` in the CSP) on every path: `/`, `/dramas.php`,
`/?s=`, `/feed/`, `/robots.txt`, `/sitemap.xml`, `/wp-json/`, even
`/favicon.ico` and `/wp-content/uploads/`. Only `/cdn-cgi/trace` (200)
responds, so the zone itself is behind the challenge, not one route.

- Not a code regression: `git diff 2da2ebf e005d3f --stat` (v12→v14) touches
  nothing in `KDramaIn.kt`; its last change is v12 (`2da2ebf`). The mirror value
  has been `https://k-drama.in` in every release since v11 (v11 config.json,
  version 1) and hardcoded pre-v11.
- Not a header/UA problem: full Chrome-131 desktop header set (`sec-ch-ua*`,
  `Sec-Fetch-*`, `Accept-Language`, `--compressed`), a mobile-Chrome UA, and a
  bare `curl/8.0` UA all get 403. A **real headed browser** (Chromium via
  browser-use) also never clears the challenge — 60 s of polling, title stays
  "Just a moment...", 0 links. Same via `r.jina.ai` (proxy hits the challenge
  too).
- No mirror exists: `kdrama.in` is an unrelated squatted domain; `kdrama.tv` is
  a for-sale A-grade domain; `kdrama.la`, `kdrama1.in`, `kdramain.net`,
  `kdramain.com`, `k-drama.com`, `k-drama.tv`, `kdrama.to` all NXDOMAIN/000. No
  origin subdomains (`origin|cdn|static|img|mail|staging|dev.beta.k-drama.in`
  do not resolve); the cert is a plain CF-managed `*.k-drama.in`.
- Untested hypothesis (needs the user's own network): our egress IP is a
  datacenter IP in NL (`/cdn-cgi/trace` → `ip=178.84.195.10`, `loc=NL`,
  `colo=AMS`). If the block is IP-reputation based, the provider will still work
  on a real device. **Ask the user to run this on the device's network** (or a
  home IP) before touching the code:
  `curl -sI https://k-drama.in/dramas.php | head -1`  → `HTTP/2 200` = fine,
  `HTTP/2 403` = the site blocks everyone and the provider needs a new source.

**Lever when a mirror appears (no code needed):** `drama-config/config.json`
`"kdramain": ["<new mirror>", "https://k-drama.in"]` — `Config.mirror()` picks
the first non-dead entry, so prepend; add `k-drama.in` to `"dead"` only after a
mirror is verified working.

## Next steps

1. **User-side verification of v9** (auto-updates in-app at app start, repo
   installs only): new genre tabs on KissAsian/KissKH with real cards;
   k-drama.in Ranking/Watchlist + rating badges; DramaNice K/C/J/Thai tabs
   (text cards).
2. **Watch item — TMDB " (N EP)" suffix on device**: token is read from
   `System.getenv` (env var name: TMDB_TOKEN, **set in the machine
   environment** — user decision 2026-10-02: the token is never hardcoded in
   the codebase; verified 2026-10-02 that no `eyJ` appears in the source, in
   any git commit (`git log --all -S "eyJ"` → nothing), or in the v11 `.cs3`
   classes.dex; `.pi/tmdb.env` no longer exists). A normal Android app
   process likely lacks the env var, so the suffix may not show on device
   (cards otherwise fine; rating badge is site-HTML based and unaffected).
   If it becomes a problem: prefer injecting the env var into the app
   process (user side); do NOT hardcode without a new user decision.
3. **primeshows.org** (user-requested 2026-10-02, researched) — new provider
   candidate: streaming-platform categories (Prime/Netflix/Disney+/Apple TV/
   Max/Hulu/Peacock/Starz/Crunchyroll) + latest & trending. Verified facts,
   routes, and the full embed matrix are in the section below.
4. Optional: candidate providers from `drama-scraper/` (EverythingMoe, GoPlay,
   Einthusan, KissKH .ovh/.dk, AsianCrush, OnDemandChina, Dramafren,
   MyAsianTV, Asiaflix, Rive, Vidbox, KissAsian.video) — same pattern:
   live-verify chain -> harness -> gate.
5. Not possible on the current 5 sites (need new sites): dedicated Xianxia,
   Animation, Live-action split, DramaNice/Dramahood genre tabs.

## primeshows.org (candidate provider, researched 2026-10-02)

Goal (user): streaming providers (Prime, Netflix, Disney+ etc.) as
categories + latest/trending. The site is a Next.js App Router app whose
catalog is pure **TMDB**, served through an open same-origin proxy, and
whose player is a **TMDB-id embed aggregator** — both fit our architecture.

- **Catalog (no API key needed):** `GET
  https://primeshows.org/api/proxy/tmdb?endpoint=<TMDB v3 path minus /3>&<params>`
  (e.g. `endpoint=/trending/tv/week`, `Accept: application/json`). The server
  holds the TMDB key; the client JS also carries 3 embedded TMDB v3 API keys
  as a direct-call fallback. Endpoints the site uses: `/trending/{movie|tv}/
  <window>`, `/movie/popular?language=en-US&page=1&vote_count.gte=1000`,
  `/tv/popular?...`, `/discover/movie`, `/discover/tv`, `/search/multi
  ?language=en-US&query=`, `/watch/providers/{movie|tv}?language=en-US`,
  `/configuration`. (Or skip the proxy and call TMDB directly with our own
  key.)
- **Platform categories:** TMDB `/watch/providers/{movie|tv}` returns
  `{provider_id, logo_path, name, display_priority}` (Netflix, Prime Video,
  Disney+, Apple TV+, Max, Hulu, Peacock, Starz, Crunchyroll, …); group titles
  by `provider_id`. The site's own `/section/provider/<providerId>` route does
  exactly this (`?name=<Name>&watch_region=US`). Trending =
  `/trending/{movie|tv}/week`; latest = the site's "Now Playing" query
  (popular + `vote_count.gte=1000`). Routes: `/section/trending/{movie|tv|anime}`,
  `/section/movie-genre/<genreId>`, search via `/search/multi`.
- **Watch routes:** `/watch/movie/<tmdbId>`, `/watch/tv/<tmdbId>
  ?season=N&episode=N`, `/watch/anime/<anilistId>?episode=N`; detail pages
  `/movie/<id>`, `/tv/<id>`, `/tv/anime-<anilistId>`.
- **Embed matrix (non-anime; movie = `<id>`, TV = `<id>/<season>/<ep>`):**
  source order as on the site:
  | # | id | movie URL | TV URL |
  |---|----|-----------|--------|
  | 1 | vidy (Multi) | `https://www.vidy.st/movie/<id>` | `https://www.vidy.st/tv/<id>/<s>/<w>` (+ `nextEpisode=true&episodeSelector=true&autoplayNextEpisode=true`, resume `t=<sec>`) |
  | 2 | vidfast (Multi) | `https://vidfast.vc/movie/<id>?autoPlay=true` | `https://vidfast.vc/tv/<id>/<s>/<w>?autoPlay=true` |
  | 3 | rozar (Hindi) | `https://rozgarlelo.modiplay.xyz/embed/tmdb/movie?id=<id>` | `https://rozgarlelo.modiplay.xyz/embed/tmdb/tv?id=<id>&s=<s>&e=<w>` |
  | 4 | scapa (Hindi) | `https://screenscape.me/embed?tmdb=<id>&type=movie&lan=eng` | `https://screenscape.me/embed?tmdb=<id>&type=tv&s=<s>&e=<w>&lan=eng` |
  | 5 | vidrock (Original) | `https://vidrock.ru/movie/<id>` | `https://vidrock.ru/tv/<id>/<s>/<w>` |
  | 6 | vidbolt (Multi) | `https://vidbolt.xyz/movie/<id>?autoPlay=true` | `https://vidbolt.xyz/tv/<id>/<s>/<w>?autoPlay=true` |
  | 7 | vidlink (Original) | `https://vidlink.pro/movie/<id>` | `https://vidlink.pro/tv/<id>/<s>/<w>` |
  | 8 | vidzee (Original) | `https://player.vidzee.wtf/embed/movie/<id>` | `https://player.vidzee.wtf/embed/tv/<id>/<s>/<w>` |
  | 9 | reelsdownload (Hindi, usesFallback) | `https://embed.reelsdownload.online/player/<id>?key=k_8b8dc5f4aaffbc68d155aac6` | `https://embed.reelsdownload.online/player/<id>/<s>/<w>?key=k_8b8dc5f4aaffbc68d155aac6` |
  vidfast has a fallback-domain list: vidfast.bz/.in/.io/.me/.net/.pm/.pro/.xyz
  (the player rotates when the primary is down).
- **Anime (AniList id `<id>`, episode `<ep>`):** `anime-mega-sub/dub` →
  `https://tryembed.us.cc/embed/anime/<id>/<ep>/{sub|dub}`; `anime-upcloud-`
  `sub/dub/hindi` → `https://vidnest.fun/anime/<id>/<ep>/{sub|dub|hindi}`.
- **Open items before coding:** (1) live-verify one movie + one TV episode per
  host (m3u8/mp4 extraction, headers) and pick the default source order;
  (2) the site's player chunks (hls.js) + `streamed.pk` preconnect suggest
  some sources may be self-hosted HLS on Upcloud — check the upcloud sources
  specifically; (3) TMDB provider_id list is dynamic (fetch
  `/watch/providers` at runtime rather than hardcoding ids).
- Other site facts: user APIs (auth/history/favorites/watchlist/
  content-views/player-settings) are account features we don't need;
  `https://analytics.vidshows.xyz` is telemetry; build id
  `KhaTR3-xnt2cmp-fiGC4W` (2026-10-02).

## Verified live-site facts (v8/v9 investigation, 2026-10-01)
- KissAsian `/genres/<g>/`: 31 genre pages, same `a.img`+`h3.title` grid,
  paginated `/page/N/`; xianxia has no page (301 -> home); wuxia archive =
  2 series (REST `series?genres=993` agrees; term count 49 inflated by
  per-episode posts); `/recently-added-movie/` = the "Latest" feed.
- KissKH `/genres/<g>/`: 14 genres, `a.tip` grid (same parser), paginated;
  `/series/?genre=` = 500; card `span.epx` always empty.
- k-drama.in `dramas.php?type=`: 8 types; ranking = 20 cards, watchlist =
  0 (community, sparse); card id IS the TMDB id (2734 = Law & Order: SVU);
  card rating div `<i class="fas fa-star">` + one decimal; detail pages load
  episodes client-side; vidsync API has no episode totals.
- DramaNice `/list-all-drama/`: whole catalog (159 items) with per-li
  `country-XX` classes: 19=Korean(35)+8=SouthKorea(14), 17=Chinese(28)+
  48=China(21), 36=Japanese(20)+51=Japan(4), 25=Thailand(32), 70=PH(3),
  61=TW(1), 63=USA(1); the image listing has no country classes.
- TMDB v4: `GET https://api.tmdb.org/3/tv/<id>` Bearer JWT ->
  `number_of_episodes` (verified: 2734 -> 598).

## Useful artifacts (persistent — `/workspace/tmp-artifacts/`)
- `harness-proj` — JVM live-verification harness (gradle project; verbatim
  provider copies in its src — sync after repo edits). Run: `cd
  /workspace/drama-extension && ./gradlew -p /workspace/tmp-artifacts/
  harness-proj run` (5-10 min, must EXIT=0; picks up `$TMDB_TOKEN` from the
  machine env — without it the TMDB-suffix gate test is skipped). Note:
  this machine needed `apt-get install openjdk-21-jdk-headless` on
  2026-10-02 (fresh container, no JDK by default; gradle 8.12 via the repo
  wrapper).
- `harness/` — original harness sources + `cp.txt` classpath. `hj/` — extra
  JVM jars. `plugin-fresh` — cloudstream gradle plugin source @ 
  (rebuild mavenLocal: `./gradlew publishToMavenLocal`).
- `cloudstream-903ef47`, `cs-src`, `csjar_check` — user's app build source /
  unpacked stub classes. `ka-*` / `kk-*` captures (incl. valid m3u8/.srt);
  v8 investigation captures in `/tmp/cats/`.
- Build stub the extension compiles against:
  `/root/.gradle/caches/cloudstream/cloudstream/cloudstream.jar`.
- TMDB token: `$TMDB_TOKEN` in the machine environment (v4 JWT, ~6-month
  expiry) — harness runs only; never in code or the dex (user decision
  2026-10-02; verified absent from source, git history, and the v11 `.cs3`).
- In-app install URL: `https://raw.githubusercontent.com/rafnold/drama-extension/builds/repo.json`
- `$GITHUB_TOKEN` (fine-grained PAT, user `rafnold`) is embedded in the git
  remote origin URL (replaced 2026-09-30; old dead token optionally
  deletable at github.com/settings/tokens).
