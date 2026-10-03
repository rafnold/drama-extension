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
- **Not a header/UA problem** — proven. Chrome 155's EXACT captured request
  headers (`sec-ch-ua*` full set incl. `full-version-list`, `sec-ch-ua-platform:
  "Linux"`, `Upgrade-Insecure-Requests`) replayed through curl still return 403.
  A mobile-Chrome UA and a bare `curl/8.0` UA likewise. So the signal
  Cloudflare keys on is NOT in the header set.
- **Not IP-based — proven, and the site is UP.** From this same NL egress IP
  (`/cdn-cgi/trace` → `ip=178.84.195.10`), a REAL headed
  `google-chrome-unstable` (155.0.8040.2, `navigator.webdriver=false`,
  `navigator.plugins.length=5`) over CDP loads `dramas.php?type=korean` on the
  FIRST request with no challenge: 20/20 cards, `a[href*=detail.php]` each with
  an `h3` name + an `image.tmdb.org` poster + an `i.fa-star` score; detail
  `detail.php?id=2734&type=tv` → h1 "Law & Order: Special Victims Unit",
  og:image present, 23 `watch.php?id=…&season=1&episode=N&type=tv` links. The
  provider's selectors are therefore still correct and unchanged.
  Cookie set by the working browser: **`g_state` only** — NO `cf_clearance`,
  NO `__cf_bm`.
- **Conclusion: the block is on the HTTP client, not the IP and not the
  headers.** The difference between the passing headed Chrome and the failing
  curl/harness is transport/TLS-and-HTTP-2 fingerprint (JA3/JA4 + h2 SETTINGS
  + header ORDER — Cloudflare's bot score reads all three; curl and the
  NiceHttp/OkHttp client both look non-browser, and header ORDER cannot be
  faked by curl's `-H` list). A stale `cf_clearance`/`__cf_bm` cookie does NOT
  help: replaying the user's cookie via curl → 403, and injected into real
  Chromium via CDP `Network.setCookie` (verified present in `document.cookie`)
  → still "Just a moment...". Note browser-use's bundled Chromium is
  `HeadlessChrome` with `navigator.webdriver=true` — it also fails, so
  "use a real browser" must mean *headed, non-automated*, not browser-use.
- No mirror exists: `kdrama.in` is an unrelated squatted domain; `kdrama.tv` is
  a for-sale A-grade domain; `kdrama.la`, `kdrama1.in`, `kdramain.net`,
  `kdramain.com`, `k-drama.com`, `k-drama.tv`, `kdrama.to` all NXDOMAIN/000. No
  origin subdomains (`origin|cdn|static|img|mail|staging|dev.beta.k-drama.in`
  do not resolve); the cert is a plain CF-managed `*.k-drama.in`.

**Lever when a mirror appears (no code needed):** `drama-config/config.json`
`"kdramain": ["<new mirror>", "https://k-drama.in"]` — `Config.mirror()` picks
the first non-dead entry, so prepend; add `k-drama.in` to `"dead"` only after a
mirror is verified working.

**Known limitation to document in the v15 release note (not fixable from the
extension):** k-drama.in is Cloudflare-challenged for programmatic clients, so
the harness will keep reporting KDrama.in FAIL from this host. Judge the gate on
the other 5 providers, or run KDrama.in acceptance through the headed-Chrome
CDP capture (see artifact below) instead of the JVM harness.

**Reproduction artifacts (this host):** `~/.hermes/cache/scratch/cprof` (headed
Chrome profile, `--remote-debugging-port=9223`), `cdp_drive.py` (navigate +
poll + dump `outerHTML`), `cdp_headers.py` (capture the live request headers),
`kd_real.html` (20 cards), `kd_detail.html` (detail + 23 episode links),
`kd_headers.txt`. The venv with `websockets` lives at
`~/.hermes/installs/*/environments/*/venv/bin/python` (system python3 has no
`websockets`).

### Playwright MCP is the sanctioned way to reach this site (2026-10-03)

Playwright MCP is registered in `~/.hermes/config.yaml` as `mcp_servers.playwright`,
pointing at `/usr/bin/google-chrome-unstable` with `--browser chrome
--no-sandbox --user-agent <plain Chrome 155 UA> --init-script
~/.hermes/mcp-assets/playwright/stealth.js --output-dir
~/.hermes/cache/scratch/pw-out --timeout-navigation 90000`. `hermes mcp test
playwright` → connected, 25 tools.

**The init script is load-bearing.** Playwright sets `navigator.webdriver=true`
by default; with that, k-drama.in returns "Just a moment..." / 0 cards. The
script masks `webdriver`, sets `languages`/`plugins`, and adds `window.chrome`.
Do not drop it from the args. Same reason browser-use's bundled `HeadlessChrome`
always fails while a plainly-launched real Chrome succeeds.

Verified live through `mcp_playwright_browser_*` (full chain):
- catalog `dramas.php?type=korean` → **20/20 complete cards**, every one with an
  `h3` name, an `image.tmdb.org` poster and an `i.fa-star` score. First hit
  returned HTTP 403 + "Just a moment...", then the challenge **cleared itself in
  ~8 s** with no click — so allow one wait after a 403 rather than declaring
  failure.
- detail `detail.php?id=2734&type=tv` → h1 "Law & Order: Special Victims Unit",
  `og:image` present, **23** `watch.php?id=2734&season=1&episode=N&type=tv`
  links. Every selector in `KDramaIn.kt` is still correct.
- watch page → 2 iframes, the real one being
  `https://vidsync.pro/embed/tv/2734/1/1?accent=…&watermark=…` (exactly the
  vidsync embed `loadLinks` already targets).
- **vidsync.pro itself is down right now: HTTP 521 "Web server is down"**
  (3/3 curl attempts; `/api/extraction/session` also unreachable, SocketTimeout).
  So KDrama.in playback is blocked one hop further out than the Cloudflare
  issue — that is a separate, upstream outage and matches the 4 vidsync-pro
  curation failures already noted in the v13 release note. Nothing in the
  extension needs changing for it.

### A fresh `cf_clearance` DOES unlock the site for a plain HTTP client (2026-10-03)

Correction to the earlier conclusion in this section. The token first tested was
a stale one; a **freshly solved** `cf_clearance` works. Verified with a token
captured from the Playwright session (`expires` 2027-10-03, i.e. a 364-day
lifetime):

| client (same egress IP) | result |
|---|---|
| curl, no cookie | 403 "Just a moment..." |
| curl + `cf_clearance`, plain Chrome 155 UA | **200, 95,971 B, 20 cards** |
| curl + cookie + full Chrome header set | 200 |
| curl + cookie, HTTP/1.1 or HTTP/2 | 200 (both) |
| python `urllib` + cookie (different TLS/JA3 stack) | 200, 20 cards |

All 7 provider routes pass with curl + cookie: `type=kdrama|cdrama|movies|
ranking` (20 cards each), `detail.php?id=2734` (25 watch links),
`?type=all&q=law` search, `watch.php?id=2734&season=1&episode=1`.

**The token is bound to the exact UA string, not the TLS stack** — this is the
whole ballgame:

| UA sent | result |
|---|---|
| exact solving UA (Linux Chrome/155) | **200** |
| Chrome/154, Chrome/131 | 403 |
| Windows Chrome/155, Mac Chrome/155 | 403 |
| Android Chrome/155 (Mobile) | 403 |
| Dalvik/2.1.0 (OkHttp-style) | 403 |
| no UA | 403 |

So `curl`'s JA3/TLS fingerprint is irrelevant — Cloudflare only checks that the
UA matches the one that solved the challenge. That also means an OkHttp client
on device is *not* inherently disqualified; it just has to send the identical UA.

**Constraint on solving:** a token can only be obtained by a browser whose UA
matches the client's UA exactly, and Cloudflare refuses to solve for stale or
mismatched versions. Attempts to solve while *claiming* a spoofed UA all failed
(Chrome/126 Windows: 100 s, never cleared; Chrome/155 Windows: 100 s, never
cleared — `cf_chl_rc_ni` set, no clearance issued). Only the browser's own
native identity solves. So in practice a usable token for the current
`KDramaIn.kt` UA (`Windows NT 10.0 … Chrome/126.0.0.0`, KDramaIn.kt:43-45)
**cannot be obtained at all** — Chrome 126 is far too old.

**Untested and decisive for on-device use:** whether `cf_clearance` is also bound
to the *solving IP*. Everything above shares one egress IP. A token solved on
the build host will very likely be rejected on a phone's mobile network. Needs
one check from the user's device before any token-shipping design is built.
-> **RESOLVED, no token needed.** See below: the host app's own
`CloudflareKiller` solves the challenge on-device. No token is shipped.

### The fix: `CloudflareKiller` (WebView), not a token (v15, 2026-10-03)

`com.lagradost.cloudstream3.network.CloudflareKiller` ships with the CloudStream
library (verified present in `cloudstream.jar` alongside `WebViewResolver`). It
is an OkHttp `Interceptor` built for exactly this: on a 403/503 + `Server:
cloudflare` response it loads the URL in a **hidden Android WebView**, lets
Cloudflare's JS run natively, pulls `cf_clearance` out of `CookieManager`, and
replays it on later OkHttp requests via `getCookieHeaders()`, which attaches
**the WebView's own UA** alongside the cookies. Same IP, same browser, native
identity -> satisfies the binding with no UA spoofing. Production precedent:
FaselHD and other extensions use `app.get(url, interceptor = cfKiller)` plus
`usesWebView = true`.

Also note `NiceResponse` has no `.ok` — it is `isSuccessful` / `.code` (the
build caught this). And NiceHttp's `Requests.get` does take an `okhttp3.
Interceptor` (verified via `javap` on NiceHttp-0.4.11.jar), so `Http.get` gained
an optional `interceptor` param.

### k-drama.in watch page: the 7 servers (user-reported: only server 6 works)

Extracted from the page's own `switchServer(n)` (a real JS function, not
markup):

| # | label | URL built |
|---|-------|-----------|
| 1 | Multi Lang | `vidsync.pro/embed/{movie\|tv}/<id>…` |
| 2 | Multi | `k-drama.in/13.php/<id>/<s>/<e>` |
| 3 | — | `k-drama.in/2.php/<id>/<s>/<e>` |
| 4 | Vidzee | `player.vidzee.wtf/embed/movie/<id>` ← **hardcodes `movie`, drops s/e** |
| 5 | Zxcstream | `player.zxcstream.xyz/player/movie/<id>` ← **same bug** |
| 6 | **YOY** | `k-drama.in/yoy4.php?id=<id>&s=<s>&e=<e>` |
| 7 | Hindi | `k-drama.in/player.php?tmdb=<id>&season=<s>&episode=<e>` |

Servers 1–3, 6–7 branch on `type === 'movie'` and pass season/episode; 4 and 5
do not, so for a TV episode they request a *movie* by the same TMDB id. Verified:
server 4 as shipped resolves to "The Eleventh Aggression"; with
`/embed/tv/290699/1/1` it resolves to "Shadow Punisher S1E1". The user reports
server 4 "unavailable" and server 5 "a complete other movie" — both are this
same site-side bug (per user instruction the cause is not being pursued further;
the site gets it wrong). Independently, vidzee's API returns `{"languages":[]}`
and its six source calls all fail for this title, so it has no sources anyway.

### Server 6 chain, verified end to end (id=290699 s1e1, "Shadow Punisher" S1E1)

```
k-drama.in/yoy4.php?id=290699&s=1&e=1     (CF-gated, CloudflareKiller)
  -> https://kisskh.megaplay.su/kisskh/225695   (single iframe proxy)
     -> m3u8  kisskh.megaplay.su/vid/…/Ep1.v489_index.m3u8
     -> .srt  kisskh.megaplay.su/sub/…/<hash>.{en,ar,id,km,ms,nl}.srt
```

**Two different referer gates, both required** (this is the whole subtlety):
- the **proxy page** answers `403 "Embed Only"` with no Referer, `200 "KissKH
  Player"` with *any* `k-drama.in` page referer;
- the **m3u8 and the .srt** answer `403 {"error":"forbidden"}` for a k-drama.in
  referer *or* none, and `200` only for a `kisskh.megaplay.su` **self-referer**.
  Media segments then fetch with or without it — but the *playlist* must use the
  self-referer, so the emitted `ExtractorLink` must carry it.

Measured: playlist `200` / 134,510 B; media segment `200` / 400,435,488 B
(a real video); English `.srt` `200` / 51,960 B with correct SRT timing. 6
subtitle languages offered.

Implemented as `Yoy4Resolver.kt` and wired into `loadLinks` as a **fallback
only** when the primary vidsync embed yields nothing (vidsync was returning 521
on 2026-10-03), per AI_RULES §5 — the happy path is unchanged. It probes the
playlist once so a dead link reports as "no sources" rather than failing at
playback time.

### Local build restored on this host (2026-10-03)

The `workspace/` tree the user copied to `~/Desktop/workspace/` unblocked
local verification. `cp.txt` had absolute `/workspace/...` paths and was
rewritten to `~/Desktop/workspace/...`. Then:

1. `cd ~/Desktop/workspace/tmp-artifacts/plugin-fresh && ./gradlew publishToMavenLocal`
   (the `com.lagradost.cloudstream3:gradle:local-SNAPSHOT` the root build needs)
   -> BUILD SUCCESSFUL, writes to `~/.m2`.
2. `echo "sdk.dir=$HOME/Desktop/workspace/android-sdk" > local.properties`
   (**gitignored** — do not commit it; it is a machine-local path).
3. `./gradlew :DramaExtension:compileReleaseKotlin` and `./gradlew make makePluginsJson`.

`CloudflareKiller` and `WebViewResolver` are present in the harness's
`cloudstream.jar`, so provider copies can be synced into
`tmp-artifacts/harness-proj/src/main/kotlin/` verbatim and the gate runs as
usual. v15 build: cs3 135,648 B; dex contains `yoy4.php` +
`kisskh.megaplay.su` + `CloudflareKiller`; `grep REDACTED` = 0; no `eyJ` TMDB
token in the dex.

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
