# Drama Streaming Extension (CloudStream 3)

A CloudStream 3 (reCloudStream) extension that scrapes Asian drama streaming
sites and resolves the **final video sources** (m3u8 / mp4), including sites
that hide their streams behind encrypted/JS player chains.

## Providers

| Provider    | Site                  | Notes |
|-------------|-----------------------|-------|
| `DramaNice` | https://dramanice.boo | K/C/J/Thai drama. 4 country tabs (K/C/J/Thai) filter the single-page `/list-all-drama/` catalog by per-item country class (text cards, no posters). Episode pages embed `dramavideo.se`, whose player host serves an **AES-256-CBC encrypted page** (`encData`/`keyHex`/`ivHex`). The extension decrypts it and reads the `JSON.parse([{file, type, label}])` sources (m3u8). Full episode lists are recovered from the WordPress sitemaps. |
| `KDrama.in` | https://k-drama.in    | K/C/J drama + movies, indexed by TMDB id. 8 tabs (All, K/C/J, Movies, Top Rated, Ranking, Watchlist, paginated); cards carry the site's star rating as the app rating badge and a "N EP" episode-count suffix fetched from the TMDB API v4 (7-day in-memory cache; needs `TMDB_TOKEN` in the app's env, degrades gracefully without it). Streams are resolved through the `vidsync.pro` extraction session API (`/api/extraction/session?type=tv\|movie&id=...&season=..&episode=..`), which returns per-provider sources (m3u8 relay + direct mp4/m3u8). |
| `KissAsian` | https://wwv21.kissasian.com.lv/ | K/C/J/TH/HK/TW/PH drama + movies (WordPress site, 477-series catalog). 13 tabs (Popular + 7 country archives + Latest + 4 genre archives fantasy/historical/romance/action, paginated). Search via WordPress REST `series?search=` (the on-site `?s=` search is client-side only). The player embed page is deliberately empty — the extension calls the runtime endpoint `player_source.php?episode=N` on the embed host to get the final M3U8 list (dramav2 + drama3 CDNs), with legacy in-page regexes as fallback. Subtitles come from the player page's Referer-gated `subApi` (signed `.srt` URLs). |
| `Dramahood` | https://dramahood.mom | K/C/J drama + KShows (WordPress site). 4 tabs (Drama, KShow + two "latest releases" archives, paginated). Server-rendered catalog and `?s=` search. Episodes embed one of three player hosts, each with its own decryption chain (AES-256-CBC hex-key, AES-256-CBC fixed key, or XOR+base64 JSON) — all resolved to the final m3u8. No subtitle files are shipped by the site. |
| `KissKH` | https://kisskh.or.at | K-drama + movies (WordPress "dramastream" theme). 9 tabs (All, Dramas, Movies + 6 genre archives fantasy/historical/romance/action/sci-fi/thriller, paginated) + `?s=` search. Series episodes resolve through a two-hop chain: `data-matrix-vault` (double base64 JSON) → `kisskh.megaplay.su` embed → `#player-payload` JSON → M3U8 + `.srt` subtitle tracks (Referer-gated). Movies resolve from the same vault's per-server iframes: `moviesapi.to` (vidora API, needs `x-player-key` + Referer/Origin) or `vidmoly.biz` (m3u8 embedded in the page). |

All providers support home page browsing, search, TV series and movie loading.

## Building

This project follows the standard [TestPlugins](https://github.com/Cloudburst/TestPlugins)
layout, so it can be built with the GitHub workflow included here.

### Option A - GitHub (recommended)

1. Copy this repository to your GitHub account.
2. Make sure a `builds` branch exists (an empty branch is fine):
   ```sh
   git checkout --orphan builds
   git rm -rf . 2>/dev/null
   git commit --allow-empty -m "init builds"
   git push -f origin builds
   git checkout main
   ```
3. Push to `main`. The `.github/workflows/build.yml` workflow runs
   `./gradlew make makePluginsJson` and publishes to the `builds` branch:
   - `*.cs3` - one file per module
   - `plugins.json` - plugin list (SitePlugin array)
   - `repo.json` - repository manifest the app actually fetches

   Repository URL for the app ("Add Repository"):
   `https://raw.githubusercontent.com/<user>/<repo>/builds/repo.json`

   Note: the workflow first builds the CloudStream gradle plugin from source
   (`recloudstream/gradle` @ `32895ae`) and publishes it to `mavenLocal`,
   because the plugin JAR is not currently available on JitPack/GitHub Packages.

### Option B - local build

Requires JDK 17+ and an Android SDK. The CloudStream gradle plugin must exist
in `mavenLocal` first (build it from source, pinned commit `32895ae`):

```sh
git clone https://github.com/recloudstream/gradle.git /tmp/cloudstream-plugin
cd /tmp/cloudstream-plugin
./gradlew publishToMavenLocal

cd /path/to/drama-extension
./gradlew make makePluginsJson
```

The `.cs3` file is produced under `DramaExtension/build/`, the plugin list at
`build/plugins.json`. (Kotlin 2.4.0 is required by the cloudstream3 stubs.)

## Installing the extension

### Via repository (in-app)

In the app: **Settings → Extensions → Add Repository**, then paste the
`repo.json` URL from Option A, e.g.
`https://raw.githubusercontent.com/<user>/<repo>/builds/repo.json`.
The `DramaNice` / `KDrama.in` / `KissAsian` / `Dramahood` / `KissKH` providers appear in the list - install them.

Note: the app expects a `Repository` JSON manifest (`repo.json` with
`pluginLists`); pasting the bare `plugins.json` or a raw `.cs3` URL does not
work. Use a recent reCloudStream CloudStream 3 build.

### Via device / local file (no GitHub needed)

Copy the built `.cs3` to the app's local plugins folder and restart the app -
it shows up under local extensions:

```sh
adb shell mkdir -p /storage/emulated/0/Cloudstream3/plugins
adb push DramaExtension/build/DramaExtension.cs3 /storage/emulated/0/Cloudstream3/plugins/
adb shell chmod -w /storage/emulated/0/Cloudstream3/plugins/DramaExtension.cs3
```

Or run the built-in task (requires exactly one ADB device connected):

```sh
./gradlew :DramaExtension:deployWithAdb
```

On Android 11+ grant the app **All files access**
(Settings → Apps → CloudStream → Special app access → All files access).
Local plugins are rescanned on every app start; removing the file removes the
extension.

## How the video resolution works

### DramaNice chain

1. Episode page embeds `https://dramavideo.se/watch?v=<id>`.
2. That page lists `li.linkserver` entries (`data-provider`, `data-video`).
3. The player host (last base64 `parts` assignment in `player.js`) serves
   `/?id=<code>&sv=<provider>` (requires the watch page as `Referer`) as an
   AES-256-CBC encrypted HTML page.
4. Decrypting it yields `sources = JSON.parse([{file, type, label}])` - the
   final m3u8 (or mp4) URLs.

Notes: the player host is re-resolved from `player.js` on demand (with a
fallback to `player.dramavideo.se`); a server can occasionally decrypt to an
*empty* source list, in which case the other `linkserver` entries are tried;
and a small number of older episodes embed `kisskh.space` instead of
dramavideo and are skipped.

### KDrama.in chain

1. `detail.php?id=<tmdb>&type=tv|movie` lists `watch.php?id=<tmdb>&season=<s>&episode=<e>`
   links (the `id` is the TMDB id).
2. The watch page embeds `https://vidsync.pro/embed/tv|movie/<tmdb>/...`.
3. The JSON-lines API `https://vidsync.pro/api/extraction/session?type=<tv|movie>&id=<tmdb>[&season=<s>][&episode=<e>]`
   returns `provider-result` lines with sources: a `url` (relay proxy,
   self-contained m3u8/segments) and `rawUrl` (direct CDN), plus `quality`.
4. Relay URLs are preferred because they rewrite playlist segments
   server-side, so playback needs no special headers.

### KissAsian chain

1. Episode page (`/series/<slug>-episode-N/`) has
   `li.Standard Server.selected[data-video]` pointing at the embed page
   `https://catalog.dramavibe.cfd/player_embed.php?episode=N`.
2. The embed page is deliberately empty (`var src = ''`, `var srcCdnList = []`),
   so the extension calls the runtime endpoint on the same host:
   `GET https://catalog.dramavibe.cfd/player_source.php?episode=N` →
   `{"ok":true,"src":"<m3u8>","list":["<m3u8>","<m3u8>"]}` (dramav2 + drama3
   CDNs, valid HLS VOD). Legacy in-page regexes (`var src`, `var srcCdnList`,
   raw `.m3u8`) remain as fallback, so a player change degrades rather than
   breaks playback.
3. Subtitles: the embed page's `subApi` (hosted on `storage.dramavibe.cfd`,
   returns time-limited signed `.srt` URLs) requires **Referer = the embed
   page URL**, otherwise it answers `Forbidden`.

### Dramahood chain

Episode pages embed one of three player hosts (plus occasional empty players
for episodes not yet uploaded — handled gracefully):

1. **dramavideo.se** — same player family as DramaNice: `/watch?v=<n>` lists
   `li.linkserver[data-video][data-provider]` entries; the player host (built
   from base64 parts in `player.js`, fallback `player.dramavideo.se`) serves
   an inline script with `encData`/`keyHex`/`ivHex` (AES-256-CBC, hex-encoded
   key/iv) that decrypts to HTML containing `sources = JSON.parse([{file, type,
   label}])` (m3u8) and `tracks` (subtitle files, currently always empty).
2. **embedload.cfd** — `/watch?v=<n>` iframes `zokoanime.video`, whose page
   carries `window.__P` (base64) that XOR-decodes (repeating key
   `otaku-embed-v1`) to JSON `{"src":"<m3u8>","subtitles":[...]}`. Note: the
   m3u8 CDN (`hls.aniwatch.al`) currently serves an incomplete Cloudflare
   Origin CA chain, so strict TLS clients (and this harness) fail the
   handshake — the link itself is correct and plays in lenient players.
3. **vidbasic.top** — `/embed/<shortid>` iframes `/3rdplayer.html?key=<b64>`;
   the 3rdplayer page's `data-name="crypto" data-value="<b64>"` decrypts with
   AES-256-CBC (PKCS7) using the **fixed ASCII key/iv**
   `94588293375053432799222445521289` / `5259228356829423` to the m3u8 URL.

The two "latest releases" tabs list individual episode posts
(`/<slug>-episode-N/`); the card parser normalizes them back to the series
page and de-duplicates, so each series appears once (latest episode first).

### KissKH chain

1. Episode "watch" page carries `li.linkserver`-style `data-matrix-vault` (base64 of a
   JSON array of `{name, vault}`; each `vault` is base64 of an HTML snippet whose
   `<iframe src>` is the server URL).
2. **Series** servers point at `kisskh.megaplay.su`: the embed page holds
   `script#player-payload` with JSON `{source: <m3u8>, tracks: [{file: <.srt>, label}]}`
   — fetched with `Referer = https://kisskh.megaplay.su/`. M3U8 + subs are emitted with
   that referer (required).
3. **Movie** servers point at:
   - `moviesapi.to/movie/<tmdbId>` — the embed's theme URL is unused; the extension calls
     `https://moviesapi.to/api/vidora/v1/movie/<tmdbId>` with `x-player-key` +
     `Referer/Origin = moviesapi.to` → `{result, sources: [{url: <m3u8>, tracks}]}`.
   - `vidmoly.biz/embed-*` — the m3u8 is embedded directly in the page HTML (first
     `https?://…m3u8` match), referer = the embed page.
   - videasy (origin down) and vidlink (wasm-encrypted) entries are skipped.

## Notes

- Sites change domains and player hosts frequently; the player host is
  resolved dynamically with a known fallback, so redeploys usually just work.
- All requests are wrapped defensively - a broken server never crashes the app.
