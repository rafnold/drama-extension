# Drama Streaming Extension (CloudStream 3)

A CloudStream 3 (reCloudStream) extension that scrapes Asian drama streaming
sites and resolves the **final video sources** (m3u8 / mp4), including sites
that hide their streams behind encrypted/JS player chains.

## Providers

| Provider    | Site                  | Notes |
|-------------|-----------------------|-------|
| `DramaNice` | https://dramanice.boo | K/C/J drama. Episode pages embed `dramavideo.se`, whose player host serves an **AES-256-CBC encrypted page** (`encData`/`keyHex`/`ivHex`). The extension decrypts it and reads the `JSON.parse([{file, type, label}])` sources (m3u8). Full episode lists are recovered from the WordPress sitemaps. |
| `KDrama.in` | https://k-drama.in    | K/C/J drama + movies, indexed by TMDB id. Streams are resolved through the `vidsync.pro` extraction session API (`/api/extraction/session?type=tv\|movie&id=...&season=..&episode=..`), which returns per-provider sources (m3u8 relay + direct mp4/m3u8). |

Both providers support home page browsing, search, TV series and movie loading.

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
The `DramaNice` / `KDrama.in` plugins appear in the list - install them.

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

## Notes

- Sites change domains and player hosts frequently; the player host is
  resolved dynamically with a known fallback, so redeploys usually just work.
- All requests are wrapped defensively - a broken server never crashes the app.
