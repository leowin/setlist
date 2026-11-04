package at.zentriert.setlist.server

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the server keeps, as plain files (on Cloud Run the directory is a mounted bucket):
 *
 * ```
 * rooms/<roomId>/auth            32-byte key the band registered; proves membership, decrypts nothing
 * rooms/<roomId>/manifest.json   {"version": n, "blob": "<base64 of the band's encrypted manifest>"}
 * rooms/<roomId>/blobs/<sha256>  encrypted records, named by the hash of the ciphertext
 * rooms/<roomId>/touched         last access (epoch millis) for the expiry sweep
 * shares/<id>.json               {"expiresAt": millis, "blob": "<base64>"} – encrypted share-link payloads
 * ```
 * The server never holds a key that could open any of it.
 */
class FileStore(private val dir: File) {
    private val rooms = File(dir, "rooms").also { it.mkdirs() }
    private val shares = File(dir, "shares").also { it.mkdirs() }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val json = Json
    private val random = SecureRandom()

    @Serializable private data class ManifestFile(val version: Long, val blob: String)
    @Serializable private data class ShareFile(val expiresAt: Long, val blob: String)

    class Manifest(val version: Long, val blob: ByteArray)

    private fun lock(roomId: String) = locks.getOrPut(roomId) { Mutex() }
    private fun roomDir(roomId: String) = File(rooms, roomId)

    fun roomExists(roomId: String) = File(roomDir(roomId), "auth").exists()

    /** Creates the room; false when it already exists (a second creator must prove the same key instead). */
    suspend fun createRoom(roomId: String, authKey: ByteArray): Boolean = lock(roomId).withLock {
        val auth = File(roomDir(roomId), "auth")
        if (auth.exists()) return false
        roomDir(roomId).mkdirs()
        File(roomDir(roomId), "blobs").mkdirs()
        auth.writeBytes(authKey)
        touch(roomId)
        true
    }

    fun authKey(roomId: String): ByteArray? = File(roomDir(roomId), "auth").takeIf { it.exists() }?.readBytes()

    fun touch(roomId: String) {
        runCatching { File(roomDir(roomId), "touched").writeText(System.currentTimeMillis().toString()) }
    }

    fun manifest(roomId: String): Manifest? {
        val f = File(roomDir(roomId), "manifest.json").takeIf { it.exists() } ?: return null
        val m = runCatching { json.decodeFromString<ManifestFile>(f.readText()) }.getOrNull() ?: return null
        return Manifest(m.version, Base64.getDecoder().decode(m.blob))
    }

    /** Replaces the manifest when [expectedVersion] matches (0 = none yet); returns the new version or null. */
    suspend fun putManifest(roomId: String, expectedVersion: Long, blob: ByteArray): Long? = lock(roomId).withLock {
        val current = manifest(roomId)?.version ?: 0L
        if (current != expectedVersion) return null
        val next = current + 1
        File(roomDir(roomId), "manifest.json").writeText(json.encodeToString(ManifestFile(next, Base64.getEncoder().encodeToString(blob))))
        touch(roomId)
        next
    }

    fun blob(roomId: String, sha: String): ByteArray? =
        File(File(roomDir(roomId), "blobs"), sha).takeIf { sha.isSha() && it.exists() }?.readBytes()

    /** Stores a blob under the hash of its content; false when the name doesn't match the bytes. */
    fun putBlob(roomId: String, sha: String, bytes: ByteArray): Boolean {
        if (!sha.isSha() || sha256(bytes) != sha) return false
        val f = File(File(roomDir(roomId), "blobs"), sha)
        if (!f.exists()) f.writeBytes(bytes)
        return true
    }

    suspend fun deleteRoom(roomId: String) = lock(roomId).withLock { roomDir(roomId).deleteRecursively() }

    // --- share links ---

    fun createShare(blob: ByteArray, ttlMillis: Long): String {
        val id = ByteArray(12).also { random.nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        File(shares, "$id.json").writeText(json.encodeToString(ShareFile(System.currentTimeMillis() + ttlMillis, Base64.getEncoder().encodeToString(blob))))
        return id
    }

    fun share(id: String): ByteArray? {
        if (!id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        val f = File(shares, "$id.json").takeIf { it.exists() } ?: return null
        val s = runCatching { json.decodeFromString<ShareFile>(f.readText()) }.getOrNull() ?: return null
        if (s.expiresAt < System.currentTimeMillis()) { f.delete(); return null }
        return Base64.getDecoder().decode(s.blob)
    }

    /** Removes rooms untouched for [roomTtlMillis] and expired shares. Returns how many went. */
    fun sweep(roomTtlMillis: Long, now: Long = System.currentTimeMillis()): Int {
        var removed = 0
        rooms.listFiles()?.forEach { room ->
            val touched = File(room, "touched").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: room.lastModified()
            if (now - touched > roomTtlMillis) { room.deleteRecursively(); removed++ }
        }
        shares.listFiles()?.forEach { f ->
            val s = runCatching { json.decodeFromString<ShareFile>(f.readText()) }.getOrNull()
            if (s == null || s.expiresAt < now) { f.delete(); removed++ }
        }
        return removed
    }

    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun String.isSha() = length == 64 && all { it in '0'..'9' || it in 'a'..'f' }
    }
}
