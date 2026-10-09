package com.example

import java.io.File

/**
 * v2 diagnostic log (REPLAN.md §6) — the first thing any rebuild block depends on, and
 * deliberately independent of every provider.
 *
 * Why this differs from [ExtLog] (v1):
 * - Default target is `/storage/emulated/0/drama_dbg/dbg.log` in a PUBLIC directory we
 *   create ourselves. v1 wrote into the app's own `Android/data/<pkg>/files/`, which on
 *   Android 10+ the shell uid cannot read (MANAGE_EXTERNAL_STORAGE required) — so even a
 *   perfectly working log was invisible to us over adb, and three sessions diagnosed blind.
 * - Fallbacks cover BOTH CloudStream flavors (`prerelease` first: operator confirmed
 *   4.8.0-PRE / `com.lagradost.cloudstream3.prerelease`) plus the legacy plugins dir.
 * - The chosen path is announced on stdout (logcat) AND written to every other writable
 *   candidate, so at least one channel always tells us where the log lives.
 *
 * Pull from any machine that can adb-reach the phone:
 * ```sh
 * adb pull /storage/emulated/0/drama_dbg/dbg.log /tmp/v2-dbg.log
 * ```
 * Line format: `<epochMs> [tag] message`. Tags: boot/http/parse/resolve:<host>/link/err.
 * Never throws; 256 KB cap (truncate + restart on overflow).
 */
object ExtLog2 {

    private val TARGETS = listOf(
        "/storage/emulated/0/drama_dbg/dbg.log",
        "/storage/emulated/0/Android/data/com.lagradost.cloudstream3.prerelease/files/v2dbg.log",
        "/storage/emulated/0/Android/data/com.lagradost.cloudstream3/files/v2dbg.log",
        "/storage/emulated/0/Cloudstream3/plugins/v2dbg.log",
    )

    private const val MAX_BYTES = 256 * 1024L

    /** First writable target; resolved once. */
    @Volatile
    var chosen: File? = null
        private set

    @Synchronized
    fun ready(): File? {
        if (chosen != null) return chosen
        val picked = mutableListOf<File>()
        for (path in TARGETS) {
            try {
                val f = File(path)
                f.parentFile?.let { p -> if (!p.exists()) runCatching { p.mkdirs() } }
                if (f.length() > MAX_BYTES) runCatching { f.delete() }
                if (f.createNewFile() || f.canWrite()) picked += f
            } catch (_: Throwable) {
                // not writable here — try the next candidate
            }
        }
        chosen = picked.firstOrNull()
        println("[ExtLog2] writing to ${chosen?.absolutePath ?: "NO WRITABLE PATH"}")
        if (picked.size > 1) {
            for (f in picked.drop(1)) log("boot", "also writable: ${f.absolutePath}")
        }
        return chosen
    }

    fun log(tag: String, message: String) {
        val f = ready() ?: return
        try {
            if (f.length() > MAX_BYTES) runCatching { f.writeText("") }
            f.appendText("${System.currentTimeMillis()} [$tag] $message\n")
        } catch (_: Throwable) {
            // diagnostics must never throw — the provider is more important than the log
        }
    }
}
