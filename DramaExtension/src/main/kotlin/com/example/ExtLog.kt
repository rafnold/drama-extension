package com.example

import java.io.File

/**
 * Append-only diagnostic log on shared storage.
 *
 * ## Why this exists
 *
 * `println` from an extension does **not** reliably reach `adb logcat` for this
 * app: on 2026-10-04 a `println` on the very first line of `KDramaIn.loadLinks`
 * produced **no** logcat line at all, on a run where the app's own
 * `Loaded everything` (also `System.out`) *did* appear. So absence of our
 * prints proved nothing.
 *
 * Writes to a file instead, so it is readable over adb with no app-side
 * cooperation. **Candidate paths, tried in order**, because the first may not be
 * writable from the app's own sandbox and a silently-unwritable log is worse
 * than no log at all. The chosen path is announced on logcat
 * (`[ExtLog] writing to …` / `[ExtLog] NO WRITABLE PATH`), so the channel itself
 * is self-describing.
 *
 * Note `/storage/emulated/0/Cloudstream3/` is **not** usable: it is owned by the
 * shell uid (u0_a355) with mode `drwxrws---`, while the app runs as u0_a470, so
 * it cannot create files there. The app-private
 * `Android/data/<pkg>/files/` directory is world-accessible over adb on this
 * device (mode `drwxrwsrwx`) and is guaranteed writable from inside the app.
 *
 * Best-effort by design: every failure is swallowed, since a diagnostic aid
 * must never be the thing that breaks a provider.
 *
 * ```sh
 * adb shell cat /storage/emulated/0/Android/data/com.lagradost.cloudstream3.prerelease/files/dbg.log
 * ```
 */
object ExtLog {
    /** Tried in order; the first writable one is used. */
    private val CANDIDATES = listOf(
        "/storage/emulated/0/Android/data/com.lagradost.cloudstream3.prerelease/files/dbg.log",
        "/storage/emulated/0/Download/dbg.log",
        "/storage/emulated/0/Cloudstream3/plugins/dbg.log",
    )

    private const val MAX_BYTES = 256 * 1024

    /** Resolved once, on first use. */
    private val target: File? by lazy {
        for (path in CANDIDATES) {
            try {
                val f = File(path)
                f.parentFile?.let { if (!it.exists()) it.mkdirs() }
                if (f.exists() && f.length() > MAX_BYTES) f.delete()
                if (f.createNewFile() || f.canWrite()) {
                    println("[ExtLog] writing to $path")
                    return@lazy f
                }
            } catch (_: Throwable) {
                // try the next candidate
            }
        }
        println("[ExtLog] NO WRITABLE PATH (tried $CANDIDATES)")
        null
    }

    fun log(tag: String, message: String) {
        val f = target ?: return
        try {
            f.appendText("${System.currentTimeMillis()} [$tag] $message\n")
        } catch (_: Throwable) {
            // diagnostics must never throw
        }
    }
}