# Hollywood source research — FMHY

**Date:** 2026-10-09
**Source:** FMHY wiki "📺 Movies TV Anime Sports" (github.com/fmhy/FMHY/wiki) + FMHY main video page.
**Status:** RESEARCH ONLY. Per AI_RULES §3, every site below must be `curl`-verified live before
any selector/endpoint is coded. None have been verified yet.

## What FMHY lists (English Hollywood = Movies / TV)

FMHY organizes sites into **Multi-Server** (aggregators that pull from many 3rd-party hosts),
**Single-Server** (one site hosts its own streams), **Free w/ Ads**, and **Streaming APIs**.

### Multi-Server aggregators (most CloudStream-friendly — thin providers + resolvers)
- **VidSrc** (`vidsrc.to`, `vidsrc.me`) — API. Resolves a TMDB id → stream URL. Has a public
  resolver repo (Ciarands/vidsrc-to-resolver). **Highest-value target: no site scraping, resolves
  any TMDB title.**
- **Levidia** (`levidia.ch`) — Movies/TV/Anime 1080p. Mirrors: supernova.to, goojara.to.
- **Goojara** (`goojara.to`) — Movies/TV/Anime 1080p. Very popular in the CloudStream community.
- **YesMovies** (`yesmovies.ag`) — Movies/TV 1080p. Mirrors: solarmovie.to, 0123movie.net, putlocker.vip.
- **PrimeWire** (`primewire.tf`) — Movies/TV/Anime; mostly 3rd-party hosts.
- **FMovies** (`fmoviesz.to`) — Movies/TV/Anime 1080p. Mirrors/clones.
- **UpMovies** (`upmovies.net`) — Movies/TV. Mirrors: flixwave.me, vumoo.mx, zoroxtv.net.
- **MovieBeams** (`moviebeamz.com`) — 4K/1080p.
- **HydraHD** (`hydrahd.com`) — Movies/TV/Anime; no mirror needed.
- **Braflix** (`braflix.video`) — 4K/1080p.
- **NunFlix** (`nunflix.com`) — 1080p.

### Single-Server (host their own streams)
- **NEPU** (`nepu.to`), **Nites** (`w1.nites.is`) / LookMovie, **PressPlay** (`pressplay.top`),
  **LookMovie** (`lookmovie2.to`), **StreamLord**, **VidCloud**, **Catflix**, **WatchHQ**, **RidoMovies**.
- Free w/ ads: **Tubi**, **Freevee**, **Crackle**, **Roku**, **ShoutFactoryTV**, **Kanopy/Hoopla**
  (library card).

### Streaming APIs (cleanest integration — JSON in, m3u8 out; no HTML scraping)
- **VidSrc** (`vidsrc.to`), **MoviesAPI** (`moviesapi.club`), **AutoEmbed** (`autoembed.cc`),
  **Embed.su** (`embed.su` 4K), **SmashyStream API** (`embed.smashystream.com`),
  **moviee** (`moviee.tv/api`), **SuperEmbed**, **2embed**, **CineScrape**,
  **WHVX** (`whvx.net`), **VidSrc.dev** (4K).
- **Wyzie Subs** (`sub.wyzie.ru`) — subtitle API (already noted in UPGRADE.MD UG-7).

## Recommended next targets (ranked for a CloudStream extension)

1. **VidSrc** — TMDB-based, API-driven, no scraping, resolves any title. Best fit for the
   existing resolver-layer architecture. Verify: `vidsrc.me/api/movie/<tmdb>` / `tv/<tmdb>/s{S}e{E}`.
2. **Goojara** — proven CloudStream pattern, scrapable markup.
3. **Levidia** — scrapable, multiple mirrors, 1080p.
4. **YesMovies** — scrapable, mirrors.

## Notes
- Most multi-host sites sit behind Cloudflare from the operator's egress IP
  (`178.84.195.10`, NL datacenter) — VidSrc is the exception (API, no CF challenge).
- Free w/ ads sites (Tubi/Freevee) are legal, ad-supported, and reliable but low-value for a
  resolver-focused extension.
- Do NOT copy any third-party extension code (clean-room only, AI_RULES §2).
