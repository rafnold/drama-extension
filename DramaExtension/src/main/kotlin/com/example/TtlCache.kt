package com.example

/**
 * Minimal in-memory TTL cache (insertion-ordered, oldest evicted first).
 * Synchronized; safe to share across the [Concurrency] pool threads.
 */
class TtlCache<V>(
    private val ttlMs: Long,
    private val maxEntries: Int,
) {
    private val store = LinkedHashMap<String, Pair<V, Long>>()
    private val lock = Any()

    fun get(key: String): V? = synchronized(lock) {
        val e = store[key] ?: return null
        if (System.currentTimeMillis() - e.second > ttlMs) {
            store.remove(key)
            return null
        }
        e.first
    }

    fun put(key: String, value: V) {
        synchronized(lock) {
            store.remove(key)
            store[key] = value to System.currentTimeMillis()
            while (store.size > maxEntries) {
                val oldest = store.entries.minByOrNull { it.value.second }?.key ?: break
                store.remove(oldest)
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            store.clear()
        }
    }
}
