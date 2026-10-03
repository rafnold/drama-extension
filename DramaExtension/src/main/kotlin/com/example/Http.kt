package com.example

import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Blocking bridge to NiceHttp's suspend-only API.
 *
 * The plugin classpath has no kotlinx-coroutines, so resolver threads drive
 * the suspend [com.lagradost.nicehttp.Requests.get] with a plain
 * [Continuation] (kotlin-stdlib only) and wait on a [CountDownLatch] with a
 * hard timeout. In the running app the coroutine machinery is provided by
 * the host APK, so this is safe at runtime.
 *
 * NOTE: `MainActivityKt.app` is imported as the top-level `app` member
 * (the Kt file facade class itself is not referenceable from plugin code),
 * and `java.lang.Object` monitor methods (wait/notifyAll) are not visible
 * in the plugin's `-no-jdk` compile, hence the latch.
 */
object Http {
    private class ResultBox<T> : Continuation<T> {
        override val context: CoroutineContext = EmptyCoroutineContext
        @Volatile
        var result: kotlin.Result<T>? = null
        private set
        val latch = CountDownLatch(1)
        override fun resumeWith(result: kotlin.Result<T>) {
            this.result = result
            latch.countDown()
        }
    }

    /**
     * Drives [block] (a suspend lambda) to completion from a plain thread,
     * waiting at most [timeoutMs]. Throws [TimeoutException] or the
     * underlying exception on failure.
     */
    fun <T> blocking(timeoutMs: Long, block: suspend () -> T): T {
        val box = ResultBox<T>()
        // Invoke the suspend function by casting to its underlying
        // (Continuation<T>) -> Any? signature - the only way to call it from
        // a non-suspend thread without kotlinx-coroutines.
        @Suppress("UNCHECKED_CAST")
        val r: Any? = (block as (Continuation<T>) -> Any?)(box)
        // Returned a value without suspending.
        if (box.result == null && !isSuspendedToken(r)) return r as T
        // Suspended (or resumed in a race): wait on the latch with a budget.
        if (!box.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw TimeoutException("HTTP request exceeded ${timeoutMs}ms")
        }
        return box.result!!.getOrThrow()
    }

    /**
     * The suspended token is the internal enum
     * [kotlin.coroutines.intrinsics.CoroutineSingletons], which plugin code
     * cannot reference by name - match it via its stable class name instead.
     */
    private fun isSuspendedToken(r: Any?): Boolean =
        r != null && r.javaClass.name == "kotlin.coroutines.intrinsics.CoroutineSingletons"

    /**
     * Blocking GET via the app's shared NiceHttp client (same cookies /
     * client config as the rest of the app).
     *
     * [interceptor] is passed straight through to NiceHttp's `Requests.get`
     * (verified against NiceHttp-0.4.11.jar: `get(url, headers, referer,
     * params, data, cacheTime, cacheTimeUnit, timeout, interceptor, …)`).
     * KDrama.in uses this to run CloudflareKiller, which solves the site's
     * managed challenge in a hidden WebView.
     */
    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        params: Map<String, String> = emptyMap(),
        referer: String? = null,
        timeoutMs: Long = 15_000L,
        interceptor: okhttp3.Interceptor? = null,
    ): NiceResponse = blocking(timeoutMs) {
        app.get(
            url,
            headers = headers,
            params = params,
            referer = referer,
            interceptor = interceptor,
        )
    }
}
