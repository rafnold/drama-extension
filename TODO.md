# TODO.md — Session handoff (updated 2026-10-01)

Read `AI_RULES.md` first — it contains the binding working agreements (complete
code only, live-verify before coding, fallback discipline, verification gate
before commit).

**Upgrade plan: `/workspace/UPGRADE.MD`** — the StreamPlay-derived task board
(UG-1..UG-14). Read it before starting any new feature work; update its task
Status columns as work lands; keep this file as the current-session handoff
only. Release history details live in `drama-extension-state.md` (gitignored).

## Current status: **v30 instrumented, NOT yet working on device** (2026-10-04)

6 providers: DramaNice, KDrama.in, KissAsian, Dramahood, KissKH, Primeshows.
HEAD is `b7fc33f` (**v29**), which IS pushed — `builds/plugins.json` version 29
verified live, and the shipped `.cs3` was independently confirmed to contain the
v29 code. Everything after that commit is **uncommitted working-tree changes**
(the "v30" batch below).

**KDrama.in playback does not work on the device yet** ("No Links Found"). The
diagnosis is not finished; v30 ships diagnostic `println`s so the next run
reads the exact failing stage from logcat instead of guessing. Read
`## v30 — KDrama.in playback bring-up` before touching this code again: it
records four wrong theories I tested and disproved, so they are not re-tried.

Recent releases:
- **v28** (2026-10-04, commit `ad2105e`): ZXC (server 5) discovery — reproduces
  the site's quality ladder (360p–1080p) from a plain client; no source emitted.
- **v27** (2026-10-04, `35febed`): reject image-only playlists (server 3 was
  serving a slideshow, not video).
- **v26** (2026-10-04, `c24f83b`): one shared FlareSolverr interceptor —
  DevcorpResolver was bypassing it.
- **v25** (2026-10-04, `b0dbaf8`): DevcorpResolver (server 3 / moviebox).
- **v24** (2026-10-04, `82b623b`): multi-server fan-out in `loadLinks`.
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

## v29 — server-5 (ZXC) English subtitles + the `Origin` fix (2026-10-04)

**Shipped.** `.cs3` 153,182 B, deployed to the device via
`./gradlew :DramaExtension:deployWithAdb` (no push required for testing).
Gate: **same 2 FAILs as the v28 baseline, verified by stashing this work and
re-running at HEAD** — both are the pre-existing k-drama.in Cloudflare block on
this host (`load FAILED: Could not parse title from detail.php?id=229480`), not
a regression. Everything else PASS/SKIP.

### Server-5 subtitles now work (`ZxcSubtitleResolver.kt`, new)

The video manifest for server 5 stays gated, but the **subtitle** endpoint on the
same server is wide open, so server 5 finally contributes something real:

1. `POST https://player.zxcprime.xyz/backend/willierevillame` — identical body
   shape to `ZxcResolver`, except the server field is the literal `"subtitle_"`
   instead of `atlas`/`valstrax`.
2. `GET https://embed.vidstuck.xyz/backend/subtitle?<same hex keys>` — served
   from a **different host** than the token, needs
   `Referer: https://player.zxcprime.xyz/`.
3. -> `{"captions":[{id, file, display}, ...]}`, `file` being a signed,
   time-limited `.srt` on `cacdn.hakunaymatata.com`.

Verified live for id=239389 ("Fangs of Fortune") s1e1: **11 captions**, English
at index 2, and the `.srt` downloads clean (29,900 B, BOM + valid SRT timing).

**English only, by owner decision.** The endpoint offers 11 languages; the owner
watches with English subs, so only the `English` caption is emitted and the
client's menu gets one entry. The trap worth remembering: the caption objects
have **no `lang`/`name` field at all** — only `display`, a human-readable name in
that language (`English`, `Français`, `Русский`, `中文`, `العربية` with
diacritics…). Worse, the Indonesian one is literally **`Indonesia`** (the
country, not the language). So these strings can't be trusted as language names;
an exact match on `"english"` is the only reliable test, and that's why there is
no lookup table.

### `Origin` header on JSON POSTs (`Http.postJson` gained a `headers` param)

`POST /backend/willierevillame` **without** `Origin` answers
`200 {"success":false,"error":"Internal Server Error"}` — a 200 with an error
body, which reads like a server bug rather than a rejected preflight. This was
masking the whole discovery chain. `Http.postJson` now takes `headers`, and all
three callers pass `Origin` (`ZxcResolver`, `ZxcSubtitleResolver`) — the two
FlareSolverr call sites were switched to the named `timeoutMs` argument since
adding a positional param would have silently reinterpreted their argument.

### Why server-5 VIDEO is still unresolved — two corrections to v28

- The `link` from `/backend_/sources/atlas` is **1132** base64url chars
  (848 raw bytes, `Salted__` OpenSSL envelope), and `token` is **64** chars
  (48 bytes).
- `/backend/database/andromeda?url=…&header=…` takes **142** (106 bytes) and
  **900** (675 bytes) respectively — **neither matches**, and no `Salted__`
  header on either. Feeding the real `link`+`token` to andromeda answers
  **HTTP 400**.
- `andromeda` returns a **real 275-segment 1080p HLS VOD** when given the values
  from a live browser session — but those segment URIs are **relative** and 404
  on every path prefix tried against `embed.vidstuck.xyz`, and the
  `cacdn`/`sacdn.hakunaymatata.com` hosts 403 without a signature. So the
  manifest a browser gets is not reachable from a plain client even when
  decrypted.
- The Cloudflare-worker transport *does* replay perfectly from any client (275
  absolute segment URLs, **7/7 sampled HTTP 200** with genuine MPEG-TS `47 40 11
  10` sync bytes) — but those URLs come from the browser's own player runtime,
  i.e. they are a capture artifact, not derivable from `sources/atlas`.

Net: v28's conclusion stands. The block is server-side key custody, and the one
thing that would finish it is a captured known-plaintext pair — which is exactly
what `vid.log` (kept at the repo root, untracked) contains.

### Also verified this session (useful, no code needed)

- FlareSolverr at `http://192.168.1.20:8191` is reachable and healthy (3.5.2),
  but `sessions.create` + `request.get` on the andromeda URL returns the **SPA
  shell, not the playlist** — that endpoint is context-sensitive, so a
  headless fetch is not a substitute for the direct chain above.

## v30 — KDrama.in playback bring-up (2026-10-04, UNCOMMITTED, still broken)

**Goal:** make KDrama.in episodes play, and list **every** working server rather
than one (explicit owner requirement).

**State: not working.** Device shows `No Links Found`. What *is* proven working is
recorded below, and so is what is not. This section is long because the failure
was diagnosed wrongly four times; the negative results are the valuable part.

### What is VERIFIED working (do not re-verify)

| Fact | Evidence |
|---|---|
| All7 server URLs, read from the live page's own `switchServer()` | FlareSolverr session on `watch.php?id=275102` |
| **Server 6 (YOY) plays.** `yoy4.php` → iframe `kisskh.megaplay.su/kisskh/224433` → m3u8 + 6 `.srt` → playlist **200 / 534 segments** → segment **200, 390 MB, magic `47401110`** (MPEG-TS) | full chain, several times |
| Server 6's playlist **requires the self-referer** `https://kisskh.megaplay.su/`. With a k-drama.in referer → `403 {"error":...}` | both variants measured |
| **Server 7 is dead**, not merely Hindi: its page renders the literal error `HAPI returned HTTP 403.` even with valid `cf_clearance` | rendered through FlareSolverr |
| **Server 3's CDN is down**: `aapanel.devcorp.me/assets/….m3u8` → **502** for every UA/referer; also dead in the owner's browser | reproduced 4×, plus user |
| Server 1 (vidsync) → **521**, and hangs **20.5 s** before failing | 2× measured |
| Server 4 (vidzee) → 200 but no source for these titles | matches the older v17 note about `languages:[]` |
| Phone and laptop share **one egress IP `178.84.195.10` (NL)** — a datacenter IP, which is why Cloudflare challenges it | `/cdn-cgi/trace` from both |
| k-drama.in **403s every OkHttp client** from that IP (`challenge-platform`, "Just a moment...") — including from the phone's own shell | `adb shell curl` → 403 |
| **FlareSolverr clears it**: solved session returns real pages, and a `cf_clearance` replayed with curl works (200, 20 cards) | verified from host and phone |
| Cold solve ≈ **11.7 s**; in-session fetch ≈ **0.4–0.8 s** | many samples |
| A **stateless** solve intermittently returns `"Challenge solved!"` **and still serves the challenge page** (`cf: True`). An **in-session** solve returns real content first time | reproduced from the phone; 6/6 clean in-session |

### THE ACTUAL BLOCKER (fixed, and it did change behaviour)

**The app could never reach FlareSolverr, because it is plain HTTP.**

- Host app is `targetSdk=36` with **no `usesCleartextTraffic` flag** → Android
  blocks cleartext. The old default was `http://192.168.1.20:8191`.
- Proof: after a full episode attempt, **FlareSolverr's own session list was
  untouched** — not one request had arrived. The app log showed
  `SocketTimeoutException` then `No Links Found` in under a second.
- `adb shell curl` from the phone reached 192.168.1.20 fine, so this is the
  **app's** network policy, not the network.

Fix: serve the solver over **HTTPS via Tailscale** — no port-forward, no DDNS:

```
# on the T470p
sudo tailscale serve --bg 8191          # note the SPACE; --bg8191 is invalid
# -> https://t470p.wildebeest-ayu.ts.net/
```

`SiteConfig.defaults().flareSolverrUrl` is now
`https://t470p.wildebeest-ayu.ts.net`. Verified from the phone:
`curl https://t470p.wildebeest-ayu.ts.net/health` → `{"status":"ok"}`, and a
request reusing the app's own session returns **150,424 bytes with
`"Challenge not detected!"`**. So the app now genuinely reaches the solver and
holds valid clearance.

**Consequence:** the solver is reachable only while Tailscale is up, so
playback is **home-network only**. Off-tailnet the app cannot solve — and a
`cf_clearance` solved at home would not validate from another egress IP anyway,
since Cloudflare binds it to the solving IP. Not fixable in code.

### The SPA bug (real, fixed, verified)

`yoy4.php` is a **JavaScript SPA**: its served HTML contains **no `<iframe>` at
all**, and the `kisskh.megaplay.su` embed only appears after ~6 s of scripting.
`Yoy4Resolver` grepped the raw HTML, found nothing, returned EMPTY — so the one
server that actually works was silently skipped.

`FlareSolverr.fetchCleared` gained `renderWaitMs`, which emits `postDataScripts`
with an explicit wait; `Yoy4Resolver` now falls back to
`CloudflareGate.interceptor().renderedHtml(..., waitMs = 7000)` when the raw body
has no iframe. Verified: raw → NONE, rendered (0.5 s) → iframe found → m3u8 →
playlist → 390 MB segment.

### Other real changes in v30

- `Concurrency.fanOut` now drains futures **by completion** (1.5 s slices,
  rotating a not-yet-done future to the back) instead of in submission order.
- `DevcorpResolver` + `Yoy4Resolver` now run **concurrently with** the fan-out
  (submitted to the pool before `resolveAll`) instead of sequentially after it.
- `TOTAL_BUDGET_MS` 20 s → **40 s** (a cold solve is 11.7 s).
- Challenge detection is no longer status-code-only: `looksLikeChallenge()`
  checks body markers (`challenge-platform`, `cf-challenge-running`,
  `Just a moment...`), because a 200 can carry an interstitial. One retry via
  the persistent session when FlareSolverr returns one.
- Server 7 removed from the fan-out; **every** resolving server is now emitted
  (the `if (!added)` short-circuits are gone), each labelled
  (`Server 3 (moviebox)`, `Server 6 (YOY)`, …) and deduped by URL.
- `Http.postJson` gained a `headers` param (the `Origin` header is load-bearing on
  `/backend/willierevillame`); the two FlareSolverr call sites were switched to a
  named `timeoutMs` so the new positional param could not swallow their argument.
- Diagnostic `println`s added in `FlareSolverrInterceptor` (`[cf] …`) and
  `Yoy4Resolver` (`[yoy] …`) — see "Next step" below.

### Four WRONG theories — tested and disproved, do not retry

1. **"The app's `usesWebView` permission is missing."** Not it; unrelated to the
   failure and unverifiable from outside.
2. **"`fanOut`'s input-order waiting starved the live servers."** I fixed this
   (it is a genuine robustness bug) and then **simulated both versions with the
   real measured latencies: OLD also survives 6/6** at a 40 s budget. It was not
   the cause of `No Links Found`; only the ordering differs.
3. **"A 200-with-interstitial is the deterministic failure."** Measured 6/6 cold
   solves returning **clean** pages. It is intermittent, not deterministic — which
   is why the body-marker check is worth keeping but was not the fix.
4. **"The `IOException: Canceled` came from a starved budget."** Real in the log
   (`f.cancel(true)` → OkHttp `IOException: Canceled`), but not from starvation.
   Source still unidentified.

Also: `Server 1 (loklok)` in the log is Devcorp's **own** label from the page, not
the site's server numbering — do not confuse the two.

### Next step (exact)

1. Force-stop CloudStream, reopen, open KDrama.in → The Scandal → Episode 1.
2. `adb -s 192.168.1.17:44491 logcat -d | grep -E '\[cf\]|\[yoy\]|No Links'`
3. Those lines name the failing stage:
   - `[cf] challenge on <host> (cached=…) -> solving` — interception reached
   - `[cf] solve FAILED … null=/blank=/stillChallenge=` — solve outcome
   - `[yoy] no iframe in raw HTML` / `[yoy] iframe=…` — SPA render
   - `[yoy] no m3u8 …` / `[yoy] playlist probe HTTP nnn` / `[yoy] OK playlist`
4. **Once it works, strip the `println`s** or keep them — they are cheap and they
   are the only visibility into this layer.

### Still true from before

- Server 5 **video** stays unresolved (`link` is an AES-CBC envelope; the
  andromeda `url`/`header` pair is neither the `link` nor the `token`). Its
  **subtitles** do work and are emitted in v29.
- `v29` is pushed and live; this v30 batch is **not committed**.

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

## v28 - ZXC (server 5) discovery, deliberately no source emitted

The site's own picker advertises six servers (Main Server, Backup I-V). We can
now reproduce its discovery call exactly, from a plain client, no browser:

1. `POST /backend/willierevillame` with a PLAIN json body keyed by fixed hex
   field names -> `{"token":"<64 hex>","ts":<epoch ms>}`
2. `GET /backend_/sources/<atlas|valstrax>?<same hex keys + token + ts>`
   -> `{"success":true,"links":[{"type":"hls","link":"<base64>","resolution":360},
      ... 480, 720, 1080],"dubs":[zh Original Audio, ar, ru]}`

`atlas` returns `type:"hls"`, `valstrax` returns `type:"dash"`. This is the
360p-1080p ladder visible on the website. Note the hex name mapping is FIXED -
only token/ts rotate - so this is reproducible, correcting the v27 note that
called the keys a moving target.

Verified request requirements: the sources GET requires **title** and **airdate**
(omitting either -> `{"success":false,"error":"missing params"}`); year, enddate
and imdb are optional. Neither is in `loadLinks` scope, so they come from
`https://zxcstream.icu/database/details/<kind>/<id>?language=en-US` - a clean,
unencrypted TMDB proxy with no Cloudflare.

Origins: `player.zxcstream.xyz` -> `player.zxcprime.xyz` (which serves
`/player/tv/...`) -> `zxcstream.icu` (which serves `/watch/tv/...`). Each host
answers only one of those two path forms; the other 404s.

### Why NO source is emitted for it

Each `link` base64-decodes to `b"Salted__" + 8-byte salt + 832 bytes
ciphertext` (832 % 16 == 0): a standard OpenSSL AES-CBC envelope. Ruled out:

- 32 chunks across both origins contain no `Salted__`, `EVP`, `BytesToKey`,
  `CryptoJS` or `passphrase`. Every `subtle.decrypt`/`pbkdf2`/`deriveBits` hit
  is stock fflate (dash.js) or Widevine/forge.
- The token is not the key: tried raw as an AES-256 key with iv = salt*2 /
  zeros / salt+8zero, and via EVP_BytesToKey with md5 and sha256, for both
  atlas and valstrax.
- Salt AND ciphertext change on every request for the same title; Shannon
  entropy 7.72-7.75 with no valid PKCS7 padding -> real CBC, ~832-byte
  plaintext.
- The `link` is not a URL. Requested on every plausible origin/path it 404s;
  the 200s are Next.js's SPA catch-all serving the app shell.

The key never reaches the browser, so the manifest is resolved server-side.
**To finish this we need one known-plaintext pair**: a real manifest response
captured from a working browser session. The fragment transport is already
solved (the `workers.dev/a?y=..&h=..` URLs replay from any client with no
Origin and a foreign Referer, returning identical gzipped MPEG-TS), so only
discovery+decryption remain.

Per the project's own rule, a server we can reach but cannot parse is reported
as "no source yet", never as a working source: a guessed URL fails silently and
is indistinguishable from "no source". `ZxcResolver.probe()` therefore returns
the ladder as a diagnostic and `loadLinks` logs it rather than emitting a link.
