# TODO.md — Session handoff (updated 2026-10-01)

Read `AI_RULES.md` first — it contains the binding working agreements (complete
code only, live-verify before coding, fallback discipline, verification gate
before commit).

**Upgrade plan: `/workspace/UPGRADE.MD`** — the StreamPlay-derived task board
(UG-1..UG-14). Read it before starting any new feature work; update its task
Status columns as work lands; keep this file as the current-session handoff
only. Release history details live in `drama-extension-state.md` (gitignored).

## Current status: **v10 LIVE** (2026-10-01, commit `1f7c69a`, builds ref `builds`)

5 providers: DramaNice, KDrama.in, KissAsian (13 tabs), Dramahood, KissKH
(9 tabs). Live `.cs3` 109,277 bytes sha256 `420f1fb5…` == local build;
`builds/plugins.json` version 10.

Recent releases:
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

## Next steps

1. **User-side verification of v9** (auto-updates in-app at app start, repo
   installs only): new genre tabs on KissAsian/KissKH with real cards;
   k-drama.in Ranking/Watchlist + rating badges; DramaNice K/C/J/Thai tabs
   (text cards).
2. **Watch item — TMDB " (N EP)" suffix on device**: token is read from
   `System.getenv` (env var name: TMDB_TOKEN, value in `.pi/tmdb.env` for
   harness runs) — **no hardcoded token by user decision (reaffirmed
   2026-10-01)**. A normal Android app process likely lacks the env var, so
   the suffix may not show on device (cards otherwise fine; rating badge is
   site-HTML based and unaffected). If it becomes a problem: prefer injecting
   the env var into the app process (user side); do NOT hardcode without a new
   user decision.
3. Optional: candidate providers from `drama-scraper/` (EverythingMoe, GoPlay,
   Einthusan, KissKH .ovh/.dk, AsianCrush, OnDemandChina, Dramafren,
   MyAsianTV, Asiaflix, Rive, Vidbox, KissAsian.video) — same pattern:
   live-verify chain -> harness -> gate.
4. Not possible on the current 5 sites (need new sites): dedicated Xianxia,
   Animation, Live-action split, DramaNice/Dramahood genre tabs.

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
  /workspace/drama-extension && TMDB_TOKEN=<from .pi/tmdb.env> ./gradlew -p
  /workspace/tmp-artifacts/harness-proj run` (5-10 min, must EXIT=0).
- `harness/` — original harness sources + `cp.txt` classpath. `hj/` — extra
  JVM jars. `plugin-fresh` — cloudstream gradle plugin source @ `32895ae`
  (rebuild mavenLocal: `./gradlew publishToMavenLocal`).
- `cloudstream-903ef47`, `cs-src`, `csjar_check` — user's app build source /
  unpacked stub classes. `ka-*` / `kk-*` captures (incl. valid m3u8/.srt);
  v8 investigation captures in `/tmp/cats/`.
- Build stub the extension compiles against:
  `/root/.gradle/caches/cloudstream/cloudstream/cloudstream.jar`.
- TMDB token: `.pi/tmdb.env` (v4 JWT, ~6-month expiry) — harness runs only;
  never in code (user decision).
- In-app install URL: `https://raw.githubusercontent.com/rafnold/drama-extension/builds/repo.json`
- `$GITHUB_TOKEN` (fine-grained PAT, user `rafnold`) is embedded in the git
  remote origin URL (replaced 2026-09-30; old dead token optionally
  deletable at github.com/settings/tokens).
