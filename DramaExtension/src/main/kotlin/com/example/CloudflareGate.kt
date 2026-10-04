package com.example

/**
 * Single shared [FlareSolverrInterceptor] for the whole extension.
 *
 * ## Why this must be shared
 *
 * [FlareSolverrInterceptor] caches the solved `cf_clearance` per host in
 * instance state. Each provider holding its own instance would mean each one
 * pays its own ~11.6 s FlareSolverr solve for the same host, and - worse -
 * a resolver that *bypasses* the interceptor entirely silently gets a 403.
 *
 * That second failure is not hypothetical: `DevcorpResolver` fetches
 * `k-drama.in/2.php/...` and originally used a bare `Http.get(pageUrl)`. On a
 * cold cache that request is challenged, the body has no iframe, and the
 * resolver returned EMPTY - so `loadLinks` produced no links at all even though
 * server 3 verifiably carries the title. Observed on device as a black screen
 * then a silent return to the episode list, with `PlayerAttachedEvent(player=null)`.
 *
 * Every Cloudflare-gated fetch now goes through `CloudflareGate.interceptor()`,
 * so one solve serves the provider and all of its resolvers.
 */
object CloudflareGate {
    @Volatile
    private var shared: FlareSolverrInterceptor? = null

    @Synchronized
    fun interceptor(): FlareSolverrInterceptor =
        shared ?: FlareSolverrInterceptor().also { shared = it }

    /** Test/reset hook (harness). */
    fun reset() {
        shared = null
    }
}