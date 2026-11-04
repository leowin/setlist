package at.zentriert.setlist.band

import at.zentriert.setlist.currentTimeMillis
import at.zentriert.setlist.sync.BandKeys
import at.zentriert.setlist.sync.Crypto
import kotlinx.serialization.Serializable

/**
 * A band this phone plays in. [key] is the shared secret (base64url, 32 bytes) that every member
 * holds; [id] is the room id derived from it, which is all the server ever learns.
 */
@Serializable
data class Band(
    val id: String,
    val name: String,
    val key: String,
    val serverUrl: String = "",
    val createdAt: Long = currentTimeMillis(),
) {
    fun keys() = BandKeys.fromBase64(key)

    /** Invitation: the secret travels in the fragment, which browsers never send to the server. */
    fun inviteLink(server: String = serverUrl): String =
        "${server.trimEnd('/')}/join#$key.${encodeName(name)}"

    companion object {
        fun create(name: String, serverUrl: String): Band {
            val keys = BandKeys.generate()
            return Band(id = keys.roomId, name = name.trim(), key = Crypto.base64Url(keys.bandKey), serverUrl = serverUrl)
        }

        /** Parses an invitation link or just its fragment (`<key>.<name>`); null when it isn't one. */
        fun fromInvite(text: String, defaultServer: String): Band? {
            val trimmed = text.trim()
            val hash = trimmed.indexOf('#')
            val fragment = if (hash >= 0) trimmed.substring(hash + 1) else trimmed
            val server = if (hash >= 0) trimmed.substring(0, hash).removeSuffix("/join").ifBlank { defaultServer } else defaultServer
            val dot = fragment.indexOf('.')
            val key = if (dot >= 0) fragment.substring(0, dot) else fragment
            val name = if (dot >= 0) decodeName(fragment.substring(dot + 1)) else "Band"
            val bytes = runCatching { Crypto.fromBase64Url(key) }.getOrNull() ?: return null
            if (bytes.size != 32) return null
            return Band(id = BandKeys(bytes).roomId, name = name.ifBlank { "Band" }, key = Crypto.base64Url(bytes), serverUrl = server)
        }

        private fun encodeName(name: String) = Crypto.base64Url(name.encodeToByteArray())
        private fun decodeName(s: String) = runCatching { Crypto.fromBase64Url(s).decodeToString() }.getOrDefault("Band")
    }
}
