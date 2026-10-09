package com.example

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * UG-3: remote, hot-patchable site configuration.
 *
 * All provider-specific endpoints, mirrors, crypto seeds and keys live here
 * instead of being hardcoded across the five providers. The config is fetched
 * once per session from [CONFIG_URL], cached on disk (24 h TTL + ETag) and in
 * memory, and **fails open** to [Config.defaults] on any error, so the app
 * never breaks because the config fetch failed.
 *
 * Flip a mirror/seed/key in the remote config.json and the next .cs3 picks it
 * up with zero code change (verify with the harness: a debug log of the
 * chosen mainUrl, or the mirror-flip test).
 *
 * Fetch target supports `http(s)://` (via the shared [Http] client) and
 * `file://` (used by the harness / local overrides). A `siteconfig.config.url`
 * system property overrides [CONFIG_URL]; `siteconfig.cache.path` overrides the
 * on-disk cache location.
 */
object SiteConfig {
    /**
     * Remote config.json. Public repo, versioned, hot-patchable with zero code
     * change: flip a mirror order here and any install that reloads picks it up.
     * Fails open to [defaults] on network failure / timeout / parse error.
     */
    const val CONFIG_URL =
        "https://raw.githubusercontent.com/rafnold/drama-extension/main/drama-config/config.json"

    private const val TTL_SECONDS = 24L * 60 * 60
    private const val FETCH_TIMEOUT_MS = 5_000L

    /**
     * A fully-resolved config. [defaults] holds the hardcoded fallback values
     * (the app always works with these); the remote config.json overrides any
     * field. [vidsyncBase] is derived from [vidsyncApi].
     */
    data class Config(
        val mirrors: Map<String, List<String>>,
        val vidsyncApi: String,
        val dramavideoPlayerJs: String,
        val dramavideoPlayerHost: String,
        val vidoraPlayerKey: String,
        val zokoXorSeeds: List<String>,
        val vidbasicAesSeeds: List<Pair<String, String>>,
        val dead: Map<String, Long>,
        /** FlareSolverr base URL for Cloudflare-blocked sites; null = off. */
        val flareSolverrUrl: String? = null,
    ) {
        val vidsyncBase: String get() = originOf(vidsyncApi)

        /** Serialize to the on-disk / remote JSON envelope shape. */
        fun toJson(): JSONObject {
            val o = JSONObject()
            o.put("version", 1)
            val mirrorsJson = JSONObject()
            for ((k, v) in mirrors) {
                val a = JSONArray()
                for (s in v) a.put(s)
                mirrorsJson.put(k, a)
            }
            o.put("mirrors", mirrorsJson)
            o.put("vidsync", JSONObject().put("api", vidsyncApi))
            o.put("dramavideo", JSONObject()
                .put("playerJs", dramavideoPlayerJs)
                .put("playerHost", dramavideoPlayerHost))
            o.put("vidora", JSONObject().put("playerKey", vidoraPlayerKey))
            val zoko = JSONArray()
            for (s in zokoXorSeeds) zoko.put(s)
            o.put("zoko", JSONObject().put("xorSeeds", zoko))
            val aes = JSONArray()
            for ((k, iv) in vidbasicAesSeeds) aes.put(JSONObject().put("k", k).put("iv", iv))
            o.put("vidbasic", JSONObject().put("aesSeeds", aes))
            val deadJson = JSONObject()
            for ((k, v) in dead) deadJson.put(k, v)
            o.put("dead", deadJson)
            flareSolverrUrl?.let { o.put("flareSolverr", JSONObject().put("url", it)) }
            return o
        }

        /**
         * The primary mirror for [key]: the first non-dead entry in the
         * configured ordered list. Dead hosts (present in [dead] with a
         * death-time in the future) are skipped, so mirror selection stays
         * correct even after a host is retired in the config.
         *
         * Always returned WITHOUT a trailing slash, so callers can join
         * paths with an explicit "/" no matter what the remote config
         * contains (v11 shipped slash-less mirrors; providers that
         * concatenated "${'$'}{mainUrl}path/" then produced URLs like
         * https://host.lvpath/ and every tab returned 0 cards).
         */
        fun mirror(key: String): String {
            val list = mirrors[key].orEmpty()
            val now = System.currentTimeMillis() / 1000
            for (raw in list) {
                val death = dead[hostOf(raw)]
                if (death != null && now < death) continue
                return raw.removeSuffix("/")
            }
            return list.firstOrNull().orEmpty().removeSuffix("/")
        }

        companion object {
            /** Hardcoded fallback config. The app works with these when the
             * remote config is unreachable; the remote config.json overrides
             * any field.
             *
             * Any of these secrets can additionally be overridden per
             * machine via environment variables, read by the accessors
             * below (highest precedence: env var > remote config > the
             * literal here). On-device (Android) no such env vars exist,
             * so device behavior is unchanged:
             *   VIDORA_PLAYER_KEY   Vidora x-player-key (moviesapi.to)
             *   ZOKO_XOR_SEEDS      comma-separated zoko XOR seeds
             *   VIDBASIC_AES_SEEDS  comma-separated "key:iv" pairs
             *   VIDSYNC_API         vidsync.pro extraction-session URL
             * (The TMDB token is env-var-only, `$TMDB_TOKEN`, with no
             * literal fallback anywhere in the codebase.) */
            fun defaults(): Config = Config(
                mirrors = mapOf(
                    "dramanice" to listOf("https://dramanice.boo"),
                    "kisskh" to listOf("https://kisskh.or.at"),
                    "dramahood" to listOf("https://dramahood.mom"),
                    "kissasian" to listOf("https://wwv21.kissasian.com.lv"),
                    "kdramain" to listOf("https://k-drama.in"),
                    "primeshows" to listOf("https://primeshows.org"),
                    "goojara" to listOf("https://ww1.goojara.to"),
                    "yesmovies" to listOf("https://ww8.123moviesfree.net"),
                ),
                vidsyncApi = "https://vidsync.pro/api/extraction/session",
                dramavideoPlayerJs = "https://dramavideo.se/player.js",
                dramavideoPlayerHost = "https://player.dramavideo.se",
                vidoraPlayerKey = "3a67e8866ae1d2bb9e81fe7f73315a56eb3bdf5e3e755c7554c8be6910aa6b13",
                zokoXorSeeds = listOf("otaku-embed-v1"),
                vidbasicAesSeeds = listOf(
                    "94588293375053432799222445521289" to "5259228356829423",
                ),
                dead = emptyMap(),
                // FlareSolverr endpoint, used only for Cloudflare-challenged
                // hosts; override in drama-config/config.json or via
                // $FLARESOLVERR_URL, and set it to "" to disable the mechanism
                // entirely.
                //
                // HTTPS is REQUIRED, not a nicety: the host app is built with
                // targetSdk 36 and does not set `usesCleartextTraffic`, so
                // Android blocks every plain-HTTP request it makes. With the
                // previous `http://192.168.1.20:8191` default the app could
                // never reach the solver at all - no `cf_clearance`, so every
                // k-drama.in request 403'd and `loadLinks` returned nothing
                // (device symptom: `SocketTimeoutException` then
                // `showToast = No Links Found`, with FlareSolverr's own session
                // list completely untouched, proving no request ever arrived).
                //
                // Served over Tailscale rather than a router port-forward: no
                // DDNS or public exposure is needed, and the cert is valid.
                // Verified reachable from the phone 2026-10-04:
                // `curl https://t470p.wildebeest-ayu.ts.net/health` ->
                // `{"status":"ok"}`.
                //
                // Consequence: the solver is reachable only while Tailscale is
                // up on the device, so playback works on the home network. Off
                // the tailnet the app cannot solve - and a `cf_clearance` solved
                // at home would not validate from another egress IP regardless,
                // because Cloudflare binds it to the solving IP.
                flareSolverrUrl = "https://t470p.wildebeest-ayu.ts.net",
            )

            /**
             * Parse a config JSON object, taking each field from [obj] when
             * present and falling back to the default otherwise (missing
             * fields keep their default). Unknown fields are ignored. This
             * partial-merge is what makes the config fail-open: a partial or
             * malformed remote doc still yields a usable config.
             */
            fun fromJson(obj: JSONObject): Config {
                val d = defaults()
                val mirrors = LinkedHashMap<String, List<String>>()
                val m = obj.optJSONObject("mirrors")
                for (key in d.mirrors.keys) {
                    val arr = m?.optJSONArray(key)
                    val list = ArrayList<String>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val s = arr.optString(i).orEmpty()
                            if (s.isNotBlank()) list.add(s)
                        }
                    }
                    mirrors[key] = if (list.isNotEmpty()) list else d.mirrors[key]!!.toList()
                }
                val vidsync = obj.optJSONObject("vidsync")
                val dramavideo = obj.optJSONObject("dramavideo")
                val vidora = obj.optJSONObject("vidora")
                val zoko = obj.optJSONObject("zoko")
                val vidbasic = obj.optJSONObject("vidbasic")

                val aes = ArrayList<Pair<String, String>>()
                if (vidbasic != null) {
                    val aesArr = vidbasic.optJSONArray("aesSeeds")
                    if (aesArr != null) {
                        for (i in 0 until aesArr.length()) {
                            val a = aesArr.optJSONObject(i)
                            if (a != null) {
                                val k = a.optString("k").orEmpty()
                                val iv = a.optString("iv").orEmpty()
                                if (k.isNotBlank() && iv.isNotBlank()) aes.add(k to iv)
                            }
                        }
                    }
                }

                val dead = LinkedHashMap<String, Long>()
                val dmap = obj.optJSONObject("dead")
                if (dmap != null) {
                    val keyIt = dmap.keys().iterator()
                    while (keyIt.hasNext()) {
                        val k = keyIt.next()
                        dead[k] = dmap.optLong(k, 0)
                    }
                }

                fun one(src: JSONObject?, field: String, def: String): String =
                    src?.optString(field).orEmpty().ifBlank { def }

                return Config(
                    mirrors = mirrors,
                    vidsyncApi = one(vidsync, "api", d.vidsyncApi),
                    dramavideoPlayerJs = one(dramavideo, "playerJs", d.dramavideoPlayerJs),
                    dramavideoPlayerHost = one(dramavideo, "playerHost", d.dramavideoPlayerHost),
                    vidoraPlayerKey = one(vidora, "playerKey", d.vidoraPlayerKey),
                    zokoXorSeeds = run {
                        val list = ArrayList<String>()
                        if (zoko != null) {
                            val zarr = zoko.optJSONArray("xorSeeds")
                            if (zarr != null) {
                                for (i in 0 until zarr.length()) {
                                    val s = zarr.optString(i).orEmpty()
                                    if (s.isNotBlank()) list.add(s)
                                }
                            }
                        }
                        if (list.isNotEmpty()) list else d.zokoXorSeeds.toList()
                    },
                    vidbasicAesSeeds = if (aes.isNotEmpty()) aes else d.vidbasicAesSeeds.toList(),
                    dead = dead,
                    flareSolverrUrl = obj.optJSONObject("flareSolverr")
                        ?.optString("url")
                        ?.trim()
                        ?.ifBlank { d.flareSolverrUrl }
                        ?: d.flareSolverrUrl,
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Loading: in-memory cache (per session) + on-disk cache (24h + ETag).
    // ------------------------------------------------------------------

    @Volatile
    private var cached: Config? = null
    private val lock = Any()

    private fun cfg(): Config =
        cached ?: synchronized(lock) { cached ?: loadBlocking().also { cached = it } }

    /** The active config (used by the harness / tests). */
    fun config(): Config = cfg()

    /** Refresh the config now (used by the harness / a refresh trigger). */
    fun reload() = synchronized(lock) { cached = loadBlocking() }

    /** Clear the in-memory cache (used by tests). */
    fun reset() = synchronized(lock) { cached = null }

    // ---- accessors used by the providers (all non-suspend) ----

    fun mirror(key: String): String = cfg().mirror(key)

    /**
     * Environment-variable override lookup. An env var, when set to a
     * non-blank value, takes precedence over the (remote) config so a
     * machine can pin a secret without a config push. Returns null when
     * the variable is absent / blank.
     */
    private fun env(name: String): String? =
        System.getenv(name)?.takeIf { it.isNotBlank() }

    /** Comma-separated env var -> list of non-blank entries. */
    private fun envList(name: String): List<String> =
        env(name)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() } ?: emptyList()

    /** Comma-separated env var of "key:iv" pairs. */
    private fun envPairList(name: String): List<Pair<String, String>> =
        env(name)?.split(',')?.mapNotNull {
            val parts = it.split(':', limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank())
                parts[0].trim() to parts[1].trim()
            else null
        } ?: emptyList()

    /** [vidsyncApi] with the `VIDSYNC_API` env override. */
    fun vidsyncApi(): String = env("VIDSYNC_API") ?: cfg().vidsyncApi
    fun vidsyncBase(): String = originOf(vidsyncApi())
    fun dramavideoPlayerJs(): String = cfg().dramavideoPlayerJs
    fun dramavideoPlayerHost(): String = cfg().dramavideoPlayerHost
    /** [Config.vidoraPlayerKey] with the `VIDORA_PLAYER_KEY` env override. */
    fun vidoraPlayerKey(): String = env("VIDORA_PLAYER_KEY") ?: cfg().vidoraPlayerKey
    /** [Config.zokoXorSeeds] with the `ZOKO_XOR_SEEDS` env override. */
    fun zokoXorSeeds(): List<String> =
        envList("ZOKO_XOR_SEEDS").ifEmpty { cfg().zokoXorSeeds }
    /** [Config.vidbasicAesSeeds] with the `VIDBASIC_AES_SEEDS` env override. */
    fun vidbasicAesSeeds(): List<Pair<String, String>> =
        envPairList("VIDBASIC_AES_SEEDS").ifEmpty { cfg().vidbasicAesSeeds }

    /**
     * Base URL of a FlareSolverr instance used to clear Cloudflare
     * challenges, or null when none is configured.
     *
     * Precedence: `FLARESOLVERR_URL` env var > remote config.json >
     * hardcoded default. Sites behind a Cloudflare managed challenge do not
     * yield to CloudStream's own hidden WebView (verified 2026-10-03: the
     * solver timed out at both 60 s and 180 s on device), but a real headful
     * Chrome clears them, which is what FlareSolverr runs.
     *
     * Point this at null/"" to disable the mechanism entirely.
     */
    fun flareSolverrUrl(): String? {
        val url = env("FLARESOLVERR_URL") ?: cfg().flareSolverrUrl
        // Worth recording: a silently blank endpoint looks exactly like a
        // Cloudflare problem, because every fetch then 403s and loadLinks
        // returns nothing with no error anywhere.
        ExtLog.log("cfg", "flareSolverrUrl=${url ?: "<null>"} (env=${env("FLARESOLVERR_URL")})")
        return url
    }

    fun deadHosts(): Set<String> = cfg().dead.keys

    private fun configUrl(): String =
        System.getProperty("siteconfig.config.url") ?: CONFIG_URL

    private fun diskPath(): String =
        System.getProperty("siteconfig.cache.path")
            ?: (System.getProperty("java.io.tmpdir") ?: "/tmp") + "/drama-extension-siteconfig.json"

    private fun loadBlocking(): Config {
        val url = configUrl()
        val (cachedCfg, etag) = readDisk()
        return try {
            if (cachedCfg != null) {
                // Cache-first: revalidate conditionally. A 304 (unchanged)
                // or an unreachable server both keep the cached config; only
                // a 200 (changed) triggers a body fetch.
                when (val status = revalidate(url, etag)) {
                    "304" -> cachedCfg
                    null -> cachedCfg            // offline / file://: use cache
                    else -> {
                        val fresh = parseConfig(fetchText(url)) ?: cachedCfg
                        writeDisk(fresh, status)
                        fresh
                    }
                }
            } else {
                val body = fetchText(url)
                if (body == null) Config.defaults()
                else {
                    val cfg = parseConfig(body) ?: Config.defaults()
                    writeDisk(cfg, fetchEtag(url))
                    cfg
                }
            }
        } catch (_: Throwable) {
            cachedCfg ?: Config.defaults()  // fail open: cache, else defaults
        }
    }

    /** Parse a config body, failing open to defaults on a corrupt doc. */
    private fun parseConfig(body: String?): Config? =
        if (body == null) null else try {
            Config.fromJson(JSONObject(body))
        } catch (_: Throwable) { null }

    /**
     * Conditional revalidation. Returns "304" when the remote ETag matches
     * [etag], the new ETag on a 200, or null when the server is unreachable
     * (so the caller keeps the cache). `file://` has no ETag semantics and
     * always returns null (the on-disk copy is authoritative).
     */
    private fun revalidate(url: String, etag: String?): String? = try {
        when {
            url.startsWith("file://") -> null
            url.startsWith("http://") || url.startsWith("https://") -> {
                val headers = if (etag != null) mapOf("If-None-Match" to etag) else emptyMap()
                val resp = Http.get(url, headers = headers, timeoutMs = FETCH_TIMEOUT_MS)
                when (resp.code) {
                    304 -> "304"
                    200 -> resp.headers.get("ETag").orEmpty()
                    else -> null
                }
            }
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    /** Read the on-disk cache; returns (config, etag) when the entry is fresh
     * (< 24 h), otherwise (null, null). A corrupt cache file is ignored. */
    private fun readDisk(): Pair<Config?, String?> = try {
        val f = File(diskPath())
        if (!f.exists()) return Pair(null, null)
        val env = try { JSONObject(f.readText()) } catch (_: Throwable) { null }
        if (env == null) return Pair(null, null)
        val ts = env.optLong("ts", 0)
        val data = env.optJSONObject("data")
        if (data != null && System.currentTimeMillis() / 1000 - ts < TTL_SECONDS) {
            Pair(Config.fromJson(data), env.optString("etag").ifBlank { null })
        } else Pair(null, null)
    } catch (_: Throwable) {
        Pair(null, null)
    }

    /** Fetch [url] body: `file://` (local) or `http(s)://` (via [Http]). */
    private fun fetchText(url: String): String? = try {
        when {
            url.startsWith("file://") -> File(url.removePrefix("file://")).readText()
            url.startsWith("http://") || url.startsWith("https://") ->
                Http.get(url, timeoutMs = FETCH_TIMEOUT_MS).text
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    /** Current remote ETag (empty string when unknown / unavailable). */
    private fun fetchEtag(url: String): String = try {
        when {
            url.startsWith("file://") -> ""
            url.startsWith("http://") || url.startsWith("https://") ->
                Http.get(url, timeoutMs = FETCH_TIMEOUT_MS).headers.get("ETag").orEmpty()
            else -> ""
        }
    } catch (_: Throwable) {
        ""
    }

    private fun writeDisk(cfg: Config, etag: String?) {
        try {
            val env = JSONObject()
            env.put("ts", System.currentTimeMillis() / 1000)
            env.put("etag", etag.orEmpty())
            env.put("data", cfg.toJson())
            File(diskPath()).writeText(env.toString())
        } catch (_: Throwable) {
            // disk cache is best-effort; failing is not fatal (fail-open).
        }
    }

    private fun hostOf(url: String): String = try {
        (java.net.URI(url).host ?: "").lowercase()
    } catch (_: Throwable) {
        ""
    }

    private fun originOf(url: String): String = try {
        val u = java.net.URI(url)
        val host = u.host ?: ""
        val port = if (u.port > 0 && u.port != 80 && u.port != 443) ":${u.port}" else ""
        "${u.scheme ?: "https"}://$host$port"
    } catch (_: Throwable) {
        "https://"
    }
}
