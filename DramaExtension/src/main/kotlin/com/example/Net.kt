package com.example

import com.lagradost.cloudstream3.app

/**
 * Minimal conditional-GET (ETag / Last-Modified) helper.
 *
 * Lets a caller re-fetch a URL with `If-None-Match` / `If-Modified-Since`
 * headers and skip processing when the server answers 304 Not-Modified.
 * In-memory only (per process), best-effort, fails open: if the response
 * cannot be read the caller just falls back to a plain GET.
 *
 * Verified 2026-10-01: none of the five provider sites currently send
 * ETag or Last-Modified headers, so today this is a no-op; it starts
 * working automatically the moment a site (or its CDN) adds them.
 */
object Net {
    private class Entry(
        val etag: String,
        val lastModified: String,
        val body: String,
    )

    private val store = HashMap<String, Entry>()
    private val lock = Any()
    private const val MAX_ENTRIES = 512

    /**
     * GET [url] with conditional headers from a previous fetch.
     *
     * @return the fresh body, or null when the server answered 304
     *         (Not Modified) — the previous body is still valid, see [stored].
     * @throws Throwable on transport failure (caller decides the fallback).
     */
    suspend fun getFresh(url: String): String? {
        val e = entry(url)
        val headers = HashMap<String, String>()
        if (e != null) {
            if (e.etag.isNotBlank()) headers["If-None-Match"] = e.etag
            if (e.lastModified.isNotBlank()) headers["If-Modified-Since"] = e.lastModified
        }
        val resp = app.get(url, headers = headers)
        if (resp.code == 304) return null
        val body = resp.text
        remember(
            url,
            etag = resp.headers.get("ETag").orEmpty(),
            lastModified = resp.headers.get("Last-Modified").orEmpty(),
            body = body,
        )
        return body
    }

    /** The body stored for a previous fetch of [url] (304 fallback). */
    fun stored(url: String): String? = synchronized(lock) { store[url]?.body }

    private fun entry(url: String): Entry? = synchronized(lock) { store[url] }

    /** Remember the ETag / Last-Modified headers + body for [url]. */
    fun remember(url: String, etag: String, lastModified: String, body: String) {
        synchronized(lock) {
            if (store.size >= MAX_ENTRIES) store.clear()
            store[url] = Entry(etag = etag, lastModified = lastModified, body = body)
        }
    }

    fun clear() {
        synchronized(lock) {
            store.clear()
        }
    }
}
