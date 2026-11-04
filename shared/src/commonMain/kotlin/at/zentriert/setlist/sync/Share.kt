package at.zentriert.setlist.sync

import at.zentriert.setlist.Score
import at.zentriert.setlist.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a share link carries: one or more songs (with their scores) or a whole setlist. Encrypted with a
 * fresh key that lives only in the link's fragment; server/web/share.html renders the same JSON.
 */
@Serializable
data class SharePayload(
    val v: Int = 1,
    val setlistName: String? = null,
    val songs: List<Song>,
    val scores: Map<String, Score> = emptyMap(),
    /** Display name of whoever shared it. */
    val from: String = "",
) {
    fun seal(): Sealed {
        val key = Crypto.random(32)
        return Sealed(SecretBox(key).seal(json.encodeToString(serializer(), this).encodeToByteArray()), Crypto.base64Url(key))
    }

    class Sealed(val bytes: ByteArray, val keyBase64: String)

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun open(sealed: ByteArray, keyBase64: String): SharePayload? {
            val key = runCatching { Crypto.fromBase64Url(keyBase64) }.getOrNull() ?: return null
            val plain = SecretBox(key).open(sealed) ?: return null
            return runCatching { json.decodeFromString(serializer(), plain.decodeToString()) }.getOrNull()
        }

        /** `https://host/s/<id>#<key>` → (host, id, key); null for anything else. */
        fun parseLink(url: String): Triple<String, String, String>? {
            val hash = url.indexOf('#').takeIf { it > 0 } ?: return null
            val path = url.substring(0, hash)
            val marker = path.lastIndexOf("/s/").takeIf { it > 0 } ?: return null
            val id = path.substring(marker + 3).trim('/')
            if (id.isEmpty()) return null
            return Triple(path.substring(0, marker), id, url.substring(hash + 1))
        }

        fun link(server: String, id: String, key: String) = "${server.trimEnd('/')}/s/$id#$key"
    }
}
