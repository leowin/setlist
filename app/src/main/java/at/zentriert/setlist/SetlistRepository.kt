package at.zentriert.setlist

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AppData(
    val setlists: List<Setlist> = emptyList(),
    /** Song library: every song ever imported or edited, with its latest instructions. */
    val library: List<Song> = emptyList(),
)

/** Stores everything as one small JSON file in app storage. */
class SetlistRepository(context: Context) {
    private val dir = context.filesDir
    private val file = AtomicFile(File(dir, "data.json"))
    private val json = Json { ignoreUnknownKeys = true }
    private val _data = MutableStateFlow(load())
    val data: StateFlow<AppData> = _data.asStateFlow()

    fun get(id: String): Setlist? = _data.value.setlists.firstOrNull { it.id == id }

    fun upsert(setlist: Setlist) = modify { data ->
        val updated = setlist.copy(updatedAt = System.currentTimeMillis())
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
                song.notes.isBlank() || song.notes == existing.notes -> library
                else -> library.upsertBy(existing.copy(notes = song.notes)) { it.id == existing.id }
            }
        }
        data.copy(library = library)
    }

    fun saveLibrarySong(song: Song) = modify { data ->
        data.copy(library = data.library.upsertBy(song) { it.id == song.id })
    }

    fun deleteLibrarySong(id: String) = modify { it.copy(library = it.library.filterNot { s -> s.id == id }) }

    private fun modify(transform: (AppData) -> AppData) = synchronized(this) {
        _data.update(transform)
        save(_data.value)
    }

    private fun load(): AppData {
        if (!file.baseFile.exists()) return AppData()
        return try {
            json.decodeFromString(file.readFully().decodeToString())
        } catch (e: Exception) {
            // Keep the unreadable file instead of overwriting it with the next save.
            Log.e(TAG, "Could not read data", e)
            file.baseFile.copyTo(File(dir, "data.broken-${System.currentTimeMillis()}.json"))
            AppData()
        }
    }

    private fun save(data: AppData) {
        val out = file.startWrite()
        try {
            out.write(json.encodeToString(data).encodeToByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.e(TAG, "Could not save data", e)
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
