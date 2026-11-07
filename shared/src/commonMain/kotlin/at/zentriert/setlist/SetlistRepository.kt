package at.zentriert.setlist

import at.zentriert.setlist.band.Band
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString

@Serializable
data class AppData(
    /** Every setlist, including tombstones of deleted ones (needed so the deletion syncs). */
    val setlists: List<Setlist> = emptyList(),
    /** Song library: every song ever imported or edited, with its latest instructions. */
    val library: List<Song> = emptyList(),
    /** The bands this phone plays in. */
    val bands: List<Band> = emptyList(),
    /** Personal mixer settings per song and person (see [PersonalMix]). */
    val mixes: List<PersonalMix> = emptyList(),
) {
    /** Live setlists of one band (null = private). */
    fun setlists(bandId: String?) = setlists.filter { !it.deleted && it.bandId == bandId }
    fun library(bandId: String?) = library.filter { !it.deleted && it.bandId == bandId }
    fun band(id: String?) = bands.firstOrNull { it.id == id }
}

/**
 * Stores everything as one small JSON file in app storage, scores as one file each. Every change
 * stamps the record with a growing revision and the author, so the band sync can merge it.
 */
class SetlistRepository(private val dir: Path) {
    private val file = Path(dir, "data.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val scoresDir = Path(dir, "scores")
    /** Where the band sync keeps what the server last agreed on. */
    val syncDir = Path(dir, "sync")
    /** Practice takes and microphone calibration (private to this phone). */
    val practiceDir = Path(dir, "practice")
    /** Backing-track files (audio/<trackId>.<ext>), local to this phone. */
    val audioDir = Path(dir, "audio")

    fun audioPath(track: AudioTrack): Path = Path(audioDir, "${track.id}.${track.fileName.substringAfterLast('.', "mp3")}")
    fun hasAudioFile(track: AudioTrack) = SystemFileSystem.exists(audioPath(track))
    fun saveAudioFile(track: AudioTrack, bytes: ByteArray) {
        SystemFileSystem.createDirectories(audioDir)
        SystemFileSystem.sink(audioPath(track)).buffered().use { it.write(bytes) }
    }
    fun deleteAudioFile(track: AudioTrack) { runCatching { SystemFileSystem.delete(audioPath(track), mustExist = false) } }
    fun readAudioFile(track: AudioTrack): ByteArray? =
        if (!hasAudioFile(track)) null else runCatching { SystemFileSystem.source(audioPath(track)).buffered().use { it.readByteArray() } }.getOrNull()
    /** Every track of every score, with the score it belongs to. */
    fun allTracks(): List<Pair<Score, AudioTrack>> = _scores.value.values.flatMap { sc -> sc.tracks.map { sc to it } }
    private val _data = MutableStateFlow(load())
    val data: StateFlow<AppData> = _data.asStateFlow()
    private val _scores = MutableStateFlow(loadScores())
    val scores: StateFlow<Map<String, Score>> = _scores.asStateFlow()

    /** Device id written into every change this phone makes. */
    var author: String = ""

    /** Fires after every local change (not after [applyRemote]) – the sync engine listens here. */
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes: SharedFlow<Unit> = _changes

    fun score(id: String?): Score? = id?.let { _scores.value[it] }

    fun saveScore(score: Score) {
        val stamped = score.copy(rev = score.rev + 1, updatedAt = currentTimeMillis(), updatedBy = author)
        _scores.update { it + (stamped.id to stamped) }
        writeJson(Path(scoresDir, "${stamped.id}.json"), stamped)
        _changes.tryEmit(Unit)
    }

    fun get(id: String): Setlist? = _data.value.setlists.firstOrNull { it.id == id && !it.deleted }

    fun upsert(setlist: Setlist) = modify { data ->
        val updated = setlist.copy(rev = setlist.rev + 1, updatedAt = currentTimeMillis(), updatedBy = author)
        data.copy(setlists = data.setlists.upsertBy(updated) { it.id == setlist.id })
    }

    /** Deleting leaves a tombstone so the deletion reaches the other phones of the band. */
    fun delete(id: String) = modify { data ->
        data.copy(setlists = data.setlists.map { if (it.id == id) stamp(it.copy(deleted = true, songs = emptyList())) else it })
    }

    /** Library lookup by title within one band, ignoring case, numbering and surrounding spaces. */
    fun findInLibrary(title: String, bandId: String?): Song? {
        val key = libraryKey(title)
        return _data.value.library(bandId).firstOrNull { libraryKey(it.title) == key }
    }

    /**
     * Adds the songs to the library of [bandId]. A song already there (same title) gets the new
     * instructions, unless those are empty.
     */
    fun remember(songs: List<Song>, bandId: String?) = modify { data ->
        var library = data.library
        for (song in songs.filter { it.title.isNotBlank() }) {
            val key = libraryKey(song.title)
            val existing = library.firstOrNull { !it.deleted && it.bandId == bandId && libraryKey(it.title) == key }
            library = when {
                existing == null -> library + stamp(song.copy(id = newId(), bandId = bandId, rev = 0, deleted = false))
                else -> {
                    val merged = existing.copy(
                        notes = if (song.notes.isBlank()) existing.notes else song.notes,
                        scoreId = song.scoreId ?: existing.scoreId,
                    )
                    if (merged == existing) library else library.upsertBy(stamp(merged)) { it.id == existing.id }
                }
            }
        }
        data.copy(library = library)
    }

    fun saveLibrarySong(song: Song) = modify { data ->
        data.copy(library = data.library.upsertBy(stamp(song)) { it.id == song.id })
    }

    fun deleteLibrarySong(id: String) = modify { data ->
        data.copy(library = data.library.map { if (it.id == id) stamp(it.copy(deleted = true)) else it })
    }

    // --- personal mixes ---

    fun mixFor(scoreId: String, person: String): PersonalMix? =
        _data.value.mixes.firstOrNull { it.id == PersonalMix.idFor(person, scoreId) && !it.deleted }

    fun saveMix(mix: PersonalMix) = modify { data ->
        val stamped = mix.copy(rev = mix.rev + 1, updatedAt = currentTimeMillis(), updatedBy = author)
        data.copy(mixes = data.mixes.upsertBy(stamped) { it.id == mix.id })
    }

    // --- bands ---

    fun addBand(band: Band) = modify { it.copy(bands = it.bands.filterNot { b -> b.id == band.id } + band) }

    /** Leaves a band locally; its songs and setlists stay on this phone as private copies. */
    fun removeBand(bandId: String) = modify { data ->
        data.copy(
            bands = data.bands.filterNot { it.id == bandId },
            setlists = data.setlists.filterNot { it.deleted }.map { if (it.bandId == bandId) it.copy(bandId = null, rev = 0) else it },
            library = data.library.filterNot { it.deleted }.map { if (it.bandId == bandId) it.copy(bandId = null, rev = 0) else it },
            mixes = data.mixes.filterNot { it.deleted }.map { if (it.bandId == bandId) it.copy(bandId = null, rev = 0) else it },
        )
    }

    /** Moves everything private into [bandId] – offered once when the first band is created. */
    fun adoptPrivate(bandId: String) = modify { data ->
        data.copy(
            setlists = data.setlists.map { if (it.bandId == null && !it.deleted) stamp(it.copy(bandId = bandId)) else it },
            library = data.library.map { if (it.bandId == null && !it.deleted) stamp(it.copy(bandId = bandId)) else it },
        )
    }

    /** An independent copy of a library song (new ids, own score) in another band. Returns the copy. */
    fun copySong(songId: String, targetBandId: String?, titleSuffix: String = ""): Song? {
        val song = _data.value.library.firstOrNull { it.id == songId && !it.deleted } ?: return null
        val copy = copyOf(song, targetBandId, titleSuffix)
        modify { it.copy(library = it.library + copy) }
        return copy
    }

    /** A copy of a setlist in another band, with copies of the songs it uses added to that library. */
    fun copySetlist(setlistId: String, targetBandId: String?, nameSuffix: String = ""): Setlist? {
        val setlist = get(setlistId) ?: return null
        val scoreMap = HashMap<String, String>()
        val songs = setlist.songs.map { s -> copyOf(s, targetBandId, "", scoreMap) }
        val copy = stamp(Setlist(name = setlist.name + nameSuffix, songs = songs, bandId = targetBandId))
        modify { it.copy(setlists = it.setlists + copy) }
        remember(songs, targetBandId)
        return copy
    }

    private fun copyOf(song: Song, bandId: String?, titleSuffix: String, scoreMap: MutableMap<String, String> = HashMap()): Song {
        val newScoreId = song.scoreId?.let { old ->
            scoreMap.getOrPut(old) {
                val source = score(old)
                val id = newId()
                if (source != null) saveScore(source.copy(id = id, rev = 0, updatedAt = 0, updatedBy = ""))
                id
            }
        }
        return stamp(Song(title = song.title + titleSuffix, notes = song.notes, scoreId = newScoreId, bandId = bandId))
    }

    // --- sync ---

    /** Writes records that arrived from the band as they are (revision and author included). */
    fun applyRemote(setlists: List<Setlist> = emptyList(), songs: List<Song> = emptyList(), scores: List<Score> = emptyList(), mixes: List<PersonalMix> = emptyList()) {
        if (scores.isNotEmpty()) {
            _scores.update { it + scores.associateBy { s -> s.id } }
            scores.forEach { writeJson(Path(scoresDir, "${it.id}.json"), it) }
        }
        if (setlists.isNotEmpty() || songs.isNotEmpty() || mixes.isNotEmpty()) {
            _data.update { data ->
                var s = data.setlists
                for (x in setlists) s = s.upsertBy(x) { it.id == x.id }
                var l = data.library
                for (x in songs) l = l.upsertBy(x) { it.id == x.id }
                var m = data.mixes
                for (x in mixes) m = m.upsertBy(x) { it.id == x.id }
                data.copy(setlists = s, library = l, mixes = m)
            }
            save(_data.value)
        }
    }

    private fun stamp(song: Song) = song.copy(rev = song.rev + 1, updatedAt = currentTimeMillis(), updatedBy = author)
    private fun stamp(setlist: Setlist) = setlist.copy(rev = setlist.rev + 1, updatedAt = currentTimeMillis(), updatedBy = author)

    private fun modify(transform: (AppData) -> AppData) {
        _data.update(transform)
        save(_data.value)
        _changes.tryEmit(Unit)
    }

    private fun load(): AppData {
        if (!SystemFileSystem.exists(file)) return AppData()
        return try {
            json.decodeFromString(SystemFileSystem.source(file).buffered().use { it.readString() })
        } catch (e: Exception) {
            // Keep the unreadable file instead of overwriting it with the next save.
            println("$TAG: could not read data: $e")
            SystemFileSystem.atomicMove(file, Path(dir, "data.broken-${currentTimeMillis()}.json"))
            AppData()
        }
    }

    private fun save(data: AppData) = writeJson(file, data)

    private fun loadScores(): Map<String, Score> {
        if (!SystemFileSystem.exists(scoresDir)) return emptyMap()
        return SystemFileSystem.list(scoresDir)
            .filter { it.name.endsWith(".json") }
            .mapNotNull { path ->
                try {
                    json.decodeFromString<Score>(SystemFileSystem.source(path).buffered().use { it.readString() })
                } catch (e: Exception) {
                    println("$TAG: could not read ${path.name}: $e")
                    null
                }
            }
            .associateBy { it.id }
    }

    /** Writes to a temp file first, so a crash mid-write never leaves a truncated file. */
    private inline fun <reified T> writeJson(target: Path, value: T) {
        try {
            target.parent?.let { SystemFileSystem.createDirectories(it) }
            val tmp = Path(target.parent ?: dir, "${target.name}.tmp")
            SystemFileSystem.sink(tmp).buffered().use { it.writeString(json.encodeToString(value)) }
            SystemFileSystem.atomicMove(tmp, target)
        } catch (e: Exception) {
            println("$TAG: could not save ${target.name}: $e")
        }
    }

    private companion object {
        const val TAG = "SetlistRepository"

        fun libraryKey(title: String) = SetlistParser.cleanTitle(title).lowercase().replace(Regex("\\s+"), " ")

        fun <T> List<T>.upsertBy(item: T, match: (T) -> Boolean): List<T> {
            val i = indexOfFirst(match)
            return if (i < 0) this + item else toMutableList().also { it[i] = item }
        }
    }
}
