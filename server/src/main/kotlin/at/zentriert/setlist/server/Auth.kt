package at.zentriert.setlist.server

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Requests prove band membership with `X-Band-Auth: <unix seconds>:<hex HMAC-SHA256(authKey, text)>`
 * where text = "<seconds>\n<METHOD>\n<path>\n<sha256 hex of the body>". The WebSocket Join uses the
 * same scheme with method JOIN and the room id as path. Clocks may differ by up to five minutes.
 */
object Auth {
    const val HEADER = "X-Band-Auth"
    private const val MAX_SKEW_SECONDS = 300L

    fun hmacHex(key: ByteArray, text: String): String =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            .doFinal(text.toByteArray()).joinToString("") { "%02x".format(it) }

    fun signedText(seconds: Long, method: String, path: String, body: ByteArray) =
        "$seconds\n${method.uppercase()}\n$path\n${FileStore.sha256(body)}"

    fun header(key: ByteArray, seconds: Long, method: String, path: String, body: ByteArray) =
        "$seconds:" + hmacHex(key, signedText(seconds, method, path, body))

    fun verify(key: ByteArray?, header: String?, method: String, path: String, body: ByteArray, nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
        if (key == null || header == null) return false
        val colon = header.indexOf(':')
        if (colon < 0) return false
        val seconds = header.substring(0, colon).toLongOrNull() ?: return false
        if (kotlin.math.abs(seconds - nowSeconds) > MAX_SKEW_SECONDS) return false
        val expected = hmacHex(key, signedText(seconds, method, path, body))
        return MessageDigest.isEqual(expected.toByteArray(), header.substring(colon + 1).lowercase().toByteArray())
    }

    fun verifyJoin(key: ByteArray?, room: String, seconds: Long, auth: String, nowSeconds: Long = System.currentTimeMillis() / 1000) =
        verify(key, "$seconds:$auth", "JOIN", room, ByteArray(0), nowSeconds)
}
