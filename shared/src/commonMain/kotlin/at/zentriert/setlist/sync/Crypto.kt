package at.zentriert.setlist.sync

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The few primitives each platform provides natively (javax.crypto on Android/JVM, CommonCrypto on
 * iOS). Everything else – key derivation, authenticated encryption, request signatures – is built on
 * them in common code, and the browser preview page does the same with WebCrypto.
 */
expect object CryptoPrimitives {
    fun sha256(data: ByteArray): ByteArray
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray
    /** AES-256 in CTR mode with a 16-byte big-endian counter block; the same call encrypts and decrypts. */
    fun aesCtr(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray
    fun randomBytes(count: Int): ByteArray
}

object Crypto {
    fun sha256(data: ByteArray) = CryptoPrimitives.sha256(data)
    fun hmac(key: ByteArray, data: ByteArray) = CryptoPrimitives.hmacSha256(key, data)
    fun random(count: Int) = CryptoPrimitives.randomBytes(count)

    /** HKDF-SHA256 (RFC 5869) with an empty salt; [info] separates the derived keys. */
    fun hkdf(ikm: ByteArray, info: String, length: Int): ByteArray {
        val prk = hmac(ByteArray(32), ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var filled = 0
        var counter = 1
        while (filled < length) {
            previous = hmac(prk, previous + info.encodeToByteArray() + byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - filled)
            previous.copyInto(out, filled, 0, n)
            filled += n
            counter++
        }
        return out
    }

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) { val v = b.toInt() and 0xff; append(HEX[v ushr 4]); append(HEX[v and 0xf]) }
    }

    fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    @OptIn(ExperimentalEncodingApi::class)
    fun base64Url(bytes: ByteArray): String = Base64.UrlSafe.encode(bytes).trimEnd('=')

    @OptIn(ExperimentalEncodingApi::class)
    fun fromBase64Url(s: String): ByteArray {
        val padded = s + "=".repeat((4 - s.length % 4) % 4)
        return Base64.UrlSafe.decode(padded)
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private const val HEX = "0123456789abcdef"
}

/**
 * Authenticated encryption from one 32-byte key: AES-256-CTR with a random 16-byte nonce, then
 * HMAC-SHA256 over nonce + ciphertext (encrypt-then-MAC). Output: nonce ‖ ciphertext ‖ tag(32).
 */
class SecretBox(key: ByteArray) {
    private val encKey = Crypto.hkdf(key, "enc", 32)
    private val macKey = Crypto.hkdf(key, "mac", 32)

    fun seal(plain: ByteArray): ByteArray {
        val nonce = Crypto.random(NONCE)
        val cipher = CryptoPrimitives.aesCtr(encKey, nonce, plain)
        val tag = Crypto.hmac(macKey, nonce + cipher)
        return nonce + cipher + tag
    }

    /** Returns null when the data was tampered with or sealed with another key. */
    fun open(sealed: ByteArray): ByteArray? {
        if (sealed.size < NONCE + TAG) return null
        val nonce = sealed.copyOfRange(0, NONCE)
        val cipher = sealed.copyOfRange(NONCE, sealed.size - TAG)
        val tag = sealed.copyOfRange(sealed.size - TAG, sealed.size)
        if (!Crypto.constantTimeEquals(tag, Crypto.hmac(macKey, nonce + cipher))) return null
        return CryptoPrimitives.aesCtr(encKey, nonce, cipher)
    }

    companion object {
        const val NONCE = 16
        const val TAG = 32
    }
}

/**
 * Everything derived from a band's secret. The secret never leaves the phones; the server only ever
 * sees [roomId] and [authKey], neither of which leads back to it.
 */
class BandKeys(val bandKey: ByteArray) {
    val roomId: String = Crypto.hex(Crypto.hmac(bandKey, "room".encodeToByteArray())).substring(0, 32)
    val authKey: ByteArray = Crypto.hkdf(bandKey, "auth", 32)
    val box = SecretBox(Crypto.hkdf(bandKey, "data", 32))

    /** Value of the X-Band-Auth header: `<unix seconds>:<hex hmac>`. */
    fun authHeader(method: String, path: String, body: ByteArray, nowSeconds: Long): String =
        "$nowSeconds:" + Crypto.hex(Crypto.hmac(authKey, signedText(nowSeconds, method, path, body).encodeToByteArray()))

    companion object {
        fun generate() = BandKeys(Crypto.random(32))
        fun fromBase64(key: String) = BandKeys(Crypto.fromBase64Url(key))

        fun signedText(seconds: Long, method: String, path: String, body: ByteArray) =
            "$seconds\n${method.uppercase()}\n$path\n${Crypto.hex(Crypto.sha256(body))}"
    }
}
