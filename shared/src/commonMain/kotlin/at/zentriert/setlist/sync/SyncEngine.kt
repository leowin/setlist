package at.zentriert.setlist.sync

import at.zentriert.setlist.Score
import at.zentriert.setlist.SetlistRepository
import at.zentriert.setlist.Setlist
import at.zentriert.setlist.Song
import at.zentriert.setlist.band.Band
import at.zentriert.setlist.currentTimeMillis
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class SyncStatus(
    val running: Boolean = false,
    val lastSuccess: Long? = null,
    val error: String? = null,
    /** Names of the band members seen in the manifest (authors of the latest changes). */
    val members: List<String> = emptyList(),
)

/**
 * Keeps one band's songs, scores and setlists in step with the server's encrypted copy. Local-first:
 * everything lives in the repository; the server sees only sealed blobs plus an encrypted manifest.
 */
class SyncEngine(
    private val repo: SetlistRepository,
    val band: Band,
    private val serverUrl: String,
    private val stateDir: Path,
    private val myName: () -> String,
    private val onStatus: (SyncStatus) -> Unit = {},
    private val client: HttpClient = HttpClient(CIO),
) {
    @Serializable
    private data class State(val version: Long = 0, val base: List<ManifestEntry> = emptyList())

    /** Something the band stores: a record with its manifest entry and (when changed) its sealed bytes. */
    private class Item(val entry: ManifestEntry, val sealed: ByteArray?)

    private val keys = band.keys()
    private val json = Json { ignoreUnknownKeys = true }
    private val stateFile = Path(stateDir, "${band.id}.json")
    private var state = loadState()
    private val mutex = Mutex()
    var status = SyncStatus()
        private set

    private val base: String get() = serverUrl.trimEnd('/')

    /** Pull, merge, push. Safe to call often; concurrent calls queue up. Returns an error text or null. */
    suspend fun sync(): String? = mutex.withLock {
        report(status.copy(running = true, error = null))
        val error = try {
            ensureRoom()
            var attempts = 0
            while (true) {
                if (syncOnce() || ++attempts >= 3) break
            }
            null
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
        report(status.copy(running = false, error = error, lastSuccess = if (error == null) currentTimeMillis() else status.lastSuccess,
            members = if (error == null) membersFrom(state.base) else status.members))
        error
    }

    /** Removes the band's room (and everything in it) from the server. */
    suspend fun deleteRoom() {
        signed("DELETE", "/rooms/${band.id}").also { if (!it.status.isSuccess()) throw IllegalStateException("Löschen fehlgeschlagen: ${it.status}") }
        state = State()
        saveState()
    }

    /** One round; false when the manifest moved under us and we should go again. */
    private suspend fun syncOnce(): Boolean {
        val remote = fetchManifest()
        // Records whose revision the server already has keep the server's blob id (sealing is randomised).
        val known = state.base.associateBy { it.key } + remote.entries.associateBy { it.key }
        var local = localItems(known)
        var plan = Merge.plan(local.map { it.entry }, remote.entries, state.base)

        // 1. Take what the others changed; keep my conflicting versions as copies.
        if (plan.download.isNotEmpty() || plan.conflicts.isNotEmpty()) {
            val incoming = (plan.download + plan.conflicts.map { it.remote }).map { it to open(it) }
            for (c in plan.conflicts) keepLocalCopy(c.local)
            apply(incoming)
            local = localItems(known)
            plan = Merge.plan(local.map { it.entry }, remote.entries, remote.entries)
        }

        // 2. Upload my changes and the merged manifest.
        val merged = remote.entries.associateBy { it.key }.toMutableMap()
        for (entry in plan.upload) {
            val item = local.first { it.entry.key == entry.key }
            val bytes = item.sealed ?: seal(entry)
            signed("PUT", "/rooms/${band.id}/blobs/${entry.blobId}", bytes).expect(HttpStatusCode.Created, HttpStatusCode.OK)
            merged[entry.key] = entry
        }
        val entries = merged.values.sortedBy { it.key }
        var version = remote.version
        if (plan.upload.isNotEmpty() || (remote.version == 0L && entries.isNotEmpty())) {
            val response = signed("PUT", "/rooms/${band.id}/manifest", keys.box.seal(json.encodeToString(Manifest.serializer(), Manifest(entries)).encodeToByteArray())) {
                header("If-Match", "\"${remote.version}\"")
            }
            if (response.status == HttpStatusCode.PreconditionFailed) return false
            response.expect(HttpStatusCode.OK)
            version = response.headers["ETag"]?.trim('"')?.toLongOrNull() ?: (remote.version + 1)
        }
        state = State(version, entries)
        saveState()
        return true
    }

    private class Remote(val version: Long, val entries: List<ManifestEntry>)

    private suspend fun fetchManifest(): Remote {
        val response = signed("GET", "/rooms/${band.id}/manifest")
        val version = response.headers["ETag"]?.trim('"')?.toLongOrNull() ?: 0L
        return when (response.status) {
            HttpStatusCode.NoContent -> Remote(version, emptyList())
            HttpStatusCode.OK -> {
                val plain = keys.box.open(response.bodyAsBytes()) ?: throw IllegalStateException("Manifest passt nicht zum Band-Schlüssel")
                Remote(version, json.decodeFromString(Manifest.serializer(), plain.decodeToString()).entries)
            }
            else -> throw IllegalStateException("Server: ${response.status}")
        }
    }

    private suspend fun ensureRoom() {
        val response = signedWithKey(keys.authKey, "PUT", "/rooms/${band.id}", keys.authKey)
        if (response.status == HttpStatusCode.Conflict) throw IllegalStateException("Raum gehört einer anderen Band")
        response.expect(HttpStatusCode.Created, HttpStatusCode.OK)
    }

    /** Everything of this band as manifest entries; unchanged records keep the blob id the base knows. */
    private fun localItems(knownByKey: Map<String, ManifestEntry>): List<Item> {
        val data = repo.data.value
        val items = ArrayList<Item>()
        fun add(type: String, id: String, rev: Long, updatedAt: Long, updatedBy: String, deleted: Boolean, encode: () -> String) {
            val known = knownByKey["$type/$id"]
            if (known != null && known.rev == rev && known.updatedAt == updatedAt) { items += Item(known, null); return }
            val sealed = keys.box.seal(encode().encodeToByteArray())
            items += Item(ManifestEntry(type, id, rev, updatedAt, updatedBy, Crypto.hex(Crypto.sha256(sealed)), deleted), sealed)
        }
        val songs = data.library.filter { it.bandId == band.id }
        val setlists = data.setlists.filter { it.bandId == band.id }
        for (s in songs) add(ManifestEntry.SONG, s.id, s.rev, s.updatedAt, s.updatedBy, s.deleted) { json.encodeToString(Song.serializer(), s) }
        for (s in setlists) add(ManifestEntry.SETLIST, s.id, s.rev, s.updatedAt, s.updatedBy, s.deleted) { json.encodeToString(Setlist.serializer(), s) }
        val scoreIds = (songs.filter { !it.deleted }.mapNotNull { it.scoreId } +
            setlists.filter { !it.deleted }.flatMap { it.songs }.mapNotNull { it.scoreId }).toSet()
        for (id in scoreIds) {
            val score = repo.score(id) ?: continue
            add(ManifestEntry.SCORE, id, score.rev, score.updatedAt, score.updatedBy, false) { json.encodeToString(Score.serializer(), score) }
        }
        return items
    }

    private fun seal(entry: ManifestEntry): ByteArray {
        val text = when (entry.type) {
            ManifestEntry.SONG -> json.encodeToString(Song.serializer(), repo.data.value.library.first { it.id == entry.id })
            ManifestEntry.SETLIST -> json.encodeToString(Setlist.serializer(), repo.data.value.setlists.first { it.id == entry.id })
            else -> json.encodeToString(Score.serializer(), repo.score(entry.id)!!)
        }
        return keys.box.seal(text.encodeToByteArray())
    }

    private suspend fun open(entry: ManifestEntry): String {
        val response = signed("GET", "/rooms/${band.id}/blobs/${entry.blobId}")
        response.expect(HttpStatusCode.OK)
        val bytes = response.bodyAsBytes()
        if (Crypto.hex(Crypto.sha256(bytes)) != entry.blobId) throw IllegalStateException("Datenpaket beschädigt")
        return (keys.box.open(bytes) ?: throw IllegalStateException("Datenpaket passt nicht zum Band-Schlüssel")).decodeToString()
    }

    private fun apply(incoming: List<Pair<ManifestEntry, String>>) {
        val songs = ArrayList<Song>(); val setlists = ArrayList<Setlist>(); val scores = ArrayList<Score>()
        for ((entry, text) in incoming) {
            when (entry.type) {
                ManifestEntry.SONG -> songs += json.decodeFromString(Song.serializer(), text).copy(bandId = band.id)
                ManifestEntry.SETLIST -> setlists += json.decodeFromString(Setlist.serializer(), text).copy(bandId = band.id)
                ManifestEntry.SCORE -> scores += json.decodeFromString(Score.serializer(), text)
            }
        }
        repo.applyRemote(setlists, songs, scores)
    }

    /** My version of a record both sides changed survives as "… (Fassung von <me>)". */
    private fun keepLocalCopy(local: ManifestEntry) {
        if (local.deleted) return
        val suffix = " (Fassung von ${myName().ifBlank { "mir" }})"
        when (local.type) {
            ManifestEntry.SONG -> repo.copySong(local.id, band.id, suffix)
            ManifestEntry.SETLIST -> repo.copySetlist(local.id, band.id, suffix)
            ManifestEntry.SCORE -> {
                val data = repo.data.value
                val owners = data.library.filter { it.bandId == band.id && !it.deleted && it.scoreId == local.id }
                if (owners.isEmpty()) return
                owners.forEach { repo.copySong(it.id, band.id, suffix) }
            }
        }
    }

    private fun membersFrom(entries: List<ManifestEntry>) = entries.map { it.updatedBy }.filter { it.isNotBlank() }.distinct()

    private suspend fun signed(method: String, path: String, body: ByteArray = ByteArray(0), extra: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}) =
        signedWithKey(keys.authKey, method, path, body, extra)

    private suspend fun signedWithKey(key: ByteArray, method: String, path: String, body: ByteArray, extra: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        val auth = "${currentTimeMillis() / 1000}:" + Crypto.hex(Crypto.hmac(key, BandKeys.signedText(currentTimeMillis() / 1000, method, path, body).encodeToByteArray()))
        val url = base + path
        return when (method) {
            "GET" -> client.get(url) { header("X-Band-Auth", auth); extra() }
            "PUT" -> client.put(url) { header("X-Band-Auth", auth); setBody(body); extra() }
            "DELETE" -> client.delete(url) { header("X-Band-Auth", auth); extra() }
            else -> error(method)
        }
    }

    private fun HttpResponse.expect(vararg ok: HttpStatusCode): HttpResponse {
        if (status !in ok) throw IllegalStateException(
            when (status) {
                HttpStatusCode.Unauthorized -> "Server kennt diese Band nicht (Schlüssel passt nicht)"
                else -> "Server: $status"
            },
        )
        return this
    }

    private fun report(s: SyncStatus) { status = s; onStatus(s) }

    private fun loadState(): State = try {
        if (SystemFileSystem.exists(stateFile)) json.decodeFromString(State.serializer(), SystemFileSystem.source(stateFile).buffered().use { it.readString() }) else State()
    } catch (e: Exception) { State() }

    private fun saveState() {
        try {
            SystemFileSystem.createDirectories(stateDir)
            val tmp = Path(stateDir, "${band.id}.tmp")
            SystemFileSystem.sink(tmp).buffered().use { it.writeString(json.encodeToString(State.serializer(), state)) }
            SystemFileSystem.atomicMove(tmp, stateFile)
        } catch (e: Exception) { println("SyncEngine: could not save state: $e") }
    }

    /** Signature for the live room's Join message. */
    fun joinAuth(nowSeconds: Long): String =
        Crypto.hex(Crypto.hmac(keys.authKey, BandKeys.signedText(nowSeconds, "JOIN", band.id, ByteArray(0)).encodeToByteArray()))

    companion object {
        /** Forgets the sync state of a band this phone left. */
        fun forget(stateDir: Path, bandId: String) { runCatching { SystemFileSystem.delete(Path(stateDir, "$bandId.json"), mustExist = false) } }
    }
}
