package at.zentriert.setlist

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

@Serializable
data class AppData(
    val setlists: List<Setlist> = emptyList(),
    /** Song library: every song ever imported or edited, with its latest instructions. */
    val library: List<Song> = emptyList(),
)

/** Stores everything as one small JSON file in app storage. */
class SetlistRepository(private val dir: Path) {
    private val file = Path(dir, "data.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val scoresDir = Path(dir, "scores")
    private val _data = MutableStateFlow(load())
    val data: StateFlow<AppData> = _data.asStateFlow()
    private val _scores = MutableStateFlow(loadScores())
    val scores: StateFlow<Map<String, Score>> = _scores.asStateFlow()

    fun score(id: String?): Score? = id?.let { _scores.value[it] }

    fun saveScore(score: Score) {
        _scores.update { it + (score.id to score) }
        writeJson(Path(scoresDir, "${score.id}.json"), score)
    }

    fun get(id: String): Setlist? = _data.value.setlists.firstOrNull { it.id == id }

    fun upsert(setlist: Setlist) = modify { data ->
        val updated = setlist.copy(updatedAt = currentTimeMillis())
        data.copy(setlists = data.setlists.upsertBy(updated) { it.id == setlist.id })
    }

    fun delete(id: String) = modify { it.copy(setlists = it.setlists.filterNot { s -> s.id == id }) }

    /** Library lookup by title, ignoring case, numbering and surrounding spaces. */
    fun findInLibrary(title: String): Song? {
        val key = libraryKey(title)
        return _data.value.library.firstOrNull { libraryKey(it.title) == key }
    }

    /**
     * Adds the songs to the library. A song already there (same title) gets the new
     * instructions, unless those are empty.
     */
    fun remember(songs: List<Song>) = modify { data ->
        var library = data.library
        for (song in songs.filter { it.title.isNotBlank() }) {
            val key = libraryKey(song.title)
            val existing = library.firstOrNull { libraryKey(it.title) == key }
            library = when {
                existing == null -> library + song.copy(id = newId())
                else -> {
                    val merged = existing.copy(
                        notes = if (song.notes.isBlank()) existing.notes else song.notes,
                        scoreId = song.scoreId ?: existing.scoreId,
                    )
                    if (merged == existing) library else library.upsertBy(merged) { it.id == existing.id }
                }
            }
        }
        data.copy(library = library)
    }

    fun saveLibrarySong(song: Song) = modify { data ->
        data.copy(library = data.library.upsertBy(song) { it.id == song.id })
    }

    fun deleteLibrarySong(id: String) = modify { it.copy(library = it.library.filterNot { s -> s.id == id }) }

    private fun modify(transform: (AppData) -> AppData) {
        _data.update(transform)
        save(_data.value)
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
