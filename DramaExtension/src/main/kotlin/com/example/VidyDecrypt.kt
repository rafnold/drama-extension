package com.example

import java.util.Base64

/**
 * Byte-exact Kotlin port of the wecollege / vidy.st source decrypt.
 *
 * The official algorithm (from the site's bundled JS) is:
 *   - KSA: a 61-entry S-array seeded by splitmix64 over the FNV1a(seed) XOR
 *     splitmix64(tmdbId ^ 2654435769), with a short 8-step mixing loop.
 *   - PRNG: a rotl-based generator producing 32-bit words little-endian;
 *     the keystream XORs the base64-decoded ciphertext.
 *   - The decrypted bytes must start with the magic "mvm1" (109,118,109,49);
 *     the remainder is UTF-8 JSON.
 *
 * All arithmetic is 32-bit unsigned. Kotlin has no unsigned 32-bit int that
 * wraps predictably for `imul`, so values are kept as Long in [0, 2^32) and
 * masked after every operation. `ushr` is used for logical shifts.
 */
object VidyDecrypt {

    private const val K = 2654435769L
    private const val A0 = 2779096485L
    private const val MASK = 0xFFFFFFFFL
    private val MAGIC = byteArrayOf(109, 118, 109, 49)

    private val IV = longArrayOf(
        1116352408L, 1899447441L, 3049323471L, 3921009573L, 961987163L,
        1508970993L, 2453635748L, 2870763221L, 3624381080L, 310598401L,
        607225278L, 1426881987L, 1925078388L, 2162078206L, 2614888103L,
        3248222580L,
    )

    private fun imul(a: Long, b: Long): Long = (a * b) and MASK

    private fun rotl(x: Long, r: Int): Long {
        val s = r and 31
        return if (s == 0) x and MASK else ((x shl s) or (x ushr (32 - s))) and MASK
    }

    /** splitmix64 finalizer, narrowed to 32 bits. */
    private fun mix(e: Long): Long {
        var x = e and MASK
        x = (x xor (x ushr 16)) and MASK
        x = imul(x, 2246822507L)
        x = (x xor (x ushr 13)) and MASK
        x = imul(x, 3266489909L)
        x = (x xor (x ushr 16)) and MASK
        return x and MASK
    }

    /** FNV-1a over the UTF-8 bytes, finalized with splitmix64. */
    private fun fnv1a(s: String): Long {
        var t = 2166136261L
        val bytes = s.encodeToByteArray()
        for (b in bytes) {
            t = imul((t xor (b.toLong() and 0xFF)) and MASK, 16777619L)
        }
        return mix(t)
    }

    private fun base64Decode(s: String): ByteArray {
        // pad so length % 4 == 0. Use (4 - len%4)%4: Kotlin's % keeps the
        // dividend's sign, so (-len)%4 would be negative here and repeat()
        // would yield an empty string, dropping the required '=' padding.
        val pad = "=".repeat((4 - s.length % 4) % 4)
        return Base64.getUrlDecoder().decode(s + pad)
    }

    /**
     * Decrypt [b64Ciphertext] with [seed] and [tmdbId].
     * @throws IllegalArgumentException when the magic bytes do not match
     *   (wrong seed / tampered payload).
     */
    fun decrypt(b64Ciphertext: String, seed: String, tmdbId: Long): String {
        val ct = base64Decode(b64Ciphertext)
        val ks = keystream(seed, tmdbId, ct.size)
        for (i in ct.indices) {
            ct[i] = (ct[i].toInt() xor ks[i].toInt()).toByte()
        }
        for (i in MAGIC.indices) {
            if ((ct[i].toInt() and 0xFF) != MAGIC[i].toInt()) {
                throw IllegalArgumentException("decrypt failed: bad seed or tampered payload")
            }
        }
        return String(ct, 4, ct.size - 4, Charsets.UTF_8)
    }

    private fun keystream(seed: String, tmdbId: Long, length: Int): ByteArray {
        // KSA
        val s0 = mix((fnv1a(seed) xor mix((tmdbId and MASK) xor K))) and MASK
        val S = LongArray(61)
        val defined = BooleanArray(61) // tracks indices ever written (JS `d in o`)
        var s = s0
        for (e in 0 until 8) {
            val t = (s % 61).toInt()
            val sr = rotl((s + K) and MASK, 7 + (7 and e))
            S[t] = (sr xor mix(sr)) and MASK
            defined[t] = true
            s = mix((sr + t) and MASK)
        }
        val acc = mix(A0 xor s) and MASK

        // PRNG
        val ks = ByteArray(length)
        var n = acc
        var counter = 0
        var i = 0
        while (i < length) {
            val d = (n % 61).toInt()
            val idx = S[d]
            val inner = (idx xor imul(K, (counter + 1).toLong())) and MASK
            val r = if (defined[d]) -1L else 0L
            val l = ((n xor inner) or (n and inner and r)) and MASK
            val x = (rotl((l + n) and MASK, 31 and d) xor rotl(n, 31 and imul(d.toLong(), 7).toInt())) and MASK
            n = mix((x + K) and MASK)
            S[d] = n
            defined[d] = true
            ks[i++] = (n and 0xFF).toByte()
            if (i < length) ks[i++] = ((n ushr 8) and 0xFF).toByte()
            if (i < length) ks[i++] = ((n ushr 16) and 0xFF).toByte()
            if (i < length) ks[i++] = ((n ushr 24) and 0xFF).toByte()
            counter++
        }
        return ks
    }
}
