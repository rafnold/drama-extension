package com.example

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Shared byte/string helpers for the resolver layer.
 *
 * Pure Kotlin + JDK crypto so the exact same code compiles against the
 * plugin (Android 21+) and the plain-JVM test harness: no
 * android.util.Base64 (absent on JVM) and no java.util.Base64 (API 26+ only,
 * minSdk is 21). The base64 decoder mirrors android.util.Base64.DEFAULT
 * semantics: non-alphabet characters are ignored and missing padding is
 * tolerated.
 */
object ResolverCrypto {
    private const val B64 =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Lenient base64 decode (ignores non-alphabet chars, tolerates no padding). */
    fun b64(input: String): String = String(decodeB64(input), Charsets.UTF_8)

    fun decodeB64(input: String): ByteArray {
        val clean = input.filter { it in B64 }
        if (clean.isEmpty()) return ByteArray(0)
        val out = ByteArray(clean.length * 3 / 4)
        var o = 0
        var acc = 0
        var bits = 0
        for (c in clean) {
            acc = (acc shl 6) or B64.indexOf(c)
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[o++] = ((acc shr bits) and 0xFF).toByte()
            }
        }
        return out.copyOf(o)
    }

    /** Base64-decode [payload] and XOR every byte with the repeating [key]. */
    fun xorB64(payload: String, key: String): String? {
        if (payload.isBlank() || key.isEmpty()) return null
        return try {
            val raw = decodeB64(payload)
            if (raw.isEmpty()) return null
            val k = key.toByteArray(Charsets.UTF_8)
            String(ByteArray(raw.size) { i -> (raw[i].toInt() xor k[i % k.size].toInt()).toByte() },
                Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    /** Hex string -> bytes; null on odd length / invalid chars. */
    fun hexToBytes(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** AES-CBC (PKCS5) decrypt; null on any failure. */
    fun aesCbcDecode(key: ByteArray, iv: ByteArray, data: ByteArray): String? {
        if (key.isEmpty() || iv.isEmpty() || data.isEmpty()) return null
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    /** Hex key/iv + base64 payload convenience. */
    fun aesCbcDecode(keyHex: String, ivHex: String, b64Data: String): String? {
        val k = hexToBytes(keyHex) ?: return null
        val iv = hexToBytes(ivHex) ?: return null
        return aesCbcDecode(k, iv, decodeB64(b64Data))
    }

    /** Quality string -> rank (higher = sharper). 0 = auto/unknown. */
    fun qualityRank(quality: String?): Int = when (quality?.lowercase()?.trim()) {
        "4k", "2160p", "uhd" -> 2160
        "1440p", "2k" -> 1440
        "1080p", "fhd" -> 1080
        "720p" -> 720
        "540p" -> 540
        "480p" -> 480
        "360p" -> 360
        "240p" -> 240
        else -> 0
    }

    /** Language name / ISO code -> 2-3 letter code (null when unrecognised). */
    private val LANG_CODES = mapOf(
        "english" to "en", "eng" to "en", "en" to "en",
        "korean" to "ko", "kor" to "ko", "ko" to "ko",
        "japanese" to "ja", "jpn" to "ja", "ja" to "ja",
        "chinese" to "zh", "chi" to "zh", "zho" to "zh", "zh" to "zh",
        "mandarin" to "zh", "cantonese" to "yue", "yue" to "yue",
        "thai" to "th", "tha" to "th", "th" to "th",
        "filipino" to "fil", "fil" to "fil", "tagalog" to "fil",
        "indonesian" to "id", "ind" to "id", "id" to "id",
        "malay" to "ms", "msa" to "ms", "ms" to "ms",
        "french" to "fr", "fra" to "fr", "fre" to "fr", "fr" to "fr",
        "german" to "de", "deu" to "de", "ger" to "de", "de" to "de",
        "spanish" to "es", "spa" to "es", "es" to "es",
        "italian" to "it", "ita" to "it", "it" to "it",
        "russian" to "ru", "rus" to "ru", "ru" to "ru",
        "arabic" to "ar", "ara" to "ar", "ar" to "ar",
        "dutch" to "nl", "nld" to "nl", "dut" to "nl", "nl" to "nl",
        "portuguese" to "pt", "por" to "pt", "pt" to "pt",
        "turkish" to "tr", "tur" to "tr", "tr" to "tr",
        "vietnamese" to "vi", "vie" to "vi", "vi" to "vi",
        "hindi" to "hi", "hin" to "hi", "hi" to "hi",
        "czech" to "cs", "ces" to "cs", "cze" to "cs", "cs" to "cs",
        "polish" to "pl", "pol" to "pl", "pl" to "pl",
        "hungarian" to "hu", "hun" to "hu", "hu" to "hu",
        "finnish" to "fi", "fin" to "fi", "fi" to "fi",
        "swedish" to "sv", "swe" to "sv", "sv" to "sv",
        "norwegian" to "no", "nor" to "no", "no" to "no",
        "danish" to "da", "dan" to "da", "da" to "da",
        "romanian" to "ro", "ron" to "ro", "rum" to "ro", "ro" to "ro",
        "bulgarian" to "bg", "bul" to "bg", "bg" to "bg",
        "albanian" to "sq", "sqi" to "sq", "sq" to "sq",
        "greek" to "el", "gre" to "el", "ell" to "el", "el" to "el",
        "hebrew" to "he", "heb" to "he", "he" to "he",
        "urdu" to "ur", "urd" to "ur", "ur" to "ur",
        "persian" to "fa", "farsi" to "fa", "fas" to "fa", "fa" to "fa",
        "khmer" to "km", "khm" to "km", "km" to "km",
        "myanmar" to "my", "burmese" to "my", "mya" to "my", "my" to "my",
        "lao" to "lo", "lo" to "lo",
        "croatian" to "hr", "hrv" to "hr", "hr" to "hr",
        "serbian" to "sr", "srp" to "sr", "sr" to "sr",
        "ukrainian" to "uk", "ukr" to "uk", "uk" to "uk",
    )

    /**
     * Label -> subtitle language code. Accepts full names ("Korean"),
     * ISO codes ("ko") and loose filenames ("Movie.Korean.srt").
     */
    fun langCode(s: String?): String? {
        val t = s?.lowercase()?.trim() ?: return null
        if (t.length in 1..3 && t.all { it.isLetter() }) LANG_CODES[t]?.let { return it }
        for (tok in t.split(Regex("[^a-z]+"))) {
            if (tok.length in 2..3 && LANG_CODES[tok] != null) return LANG_CODES[tok]
        }
        for (tok in t.split(Regex("[^a-z]+"))) {
            if (tok.length >= 4 && LANG_CODES[tok] != null) return LANG_CODES[tok]
        }
        return null
    }
}
