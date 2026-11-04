package at.zentriert.setlist

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

sealed interface Screen {
    data object Home : Screen
    class Import(val state: ImportState) : Screen
    data class Edit(val setlistId: String) : Screen
    data class Perform(val setlistId: String) : Screen
    data object Library : Screen
}

class ImportState(text: String, name: String) {
    var name by mutableStateOf(name)
    var text by mutableStateOf(text)
    var editingText by mutableStateOf(text.isBlank())
    val lines: List<String> by derivedStateOf { SetlistParser.lines(this.text) }
    var cuts by mutableStateOf(SetlistParser.suggestCuts(lines))

    fun autoCut() { cuts = SetlistParser.suggestCuts(lines) }
    fun cutEveryLine() { cuts = lines.indices.filter { lines[it].isNotBlank() }.toSet() }
    fun clearCuts() { cuts = emptySet() }
    fun toggleCut(i: Int) { cuts = if (i in cuts) cuts - i else cuts + i }
    fun songs() = SetlistParser.split(lines, cuts)
}

/** Keys a stage controller may send: arrow/page keys of Bluetooth page turners, volume buttons … */
enum class PerformKey { NEXT, PREVIOUS }

class MainViewModel(
    private val repo: SetlistRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    val data = repo.data

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set

    var settings by mutableStateOf(settingsStore.load())
        private set

    var performIndex by mutableIntStateOf(0)
        private set
    var blackout by mutableStateOf(false)
        private set

    fun setlist(id: String) = repo.get(id)

    // --- navigation ---

    fun goHome() { screen = Screen.Home }
    fun openLibrary() { screen = Screen.Library }
    fun edit(id: String) { screen = Screen.Edit(id) }

    fun perform(id: String, startAt: Int = 0) {
        performIndex = startAt
        blackout = false
        screen = Screen.Perform(id)
    }

    fun startImport(text: String, name: String) {
        screen = Screen.Import(ImportState(text, name))
    }

    // --- import ---

    fun finishImport(state: ImportState) {
        val name = state.name.trim().ifEmpty {
            "Setlist " + todayLabel()
        }
        // Songs without instructions get them from the library, if known there.
        val songs = state.songs().map { song ->
            if (song.notes.isNotBlank()) song
            else song.copy(notes = repo.findInLibrary(song.title)?.notes.orEmpty())
        }
        repo.remember(songs)
        val setlist = Setlist(name = name, songs = songs)
        repo.upsert(setlist)
        edit(setlist.id)
    }

    // --- editing ---

    private fun change(id: String, transform: (Setlist) -> Setlist) {
        repo.get(id)?.let { repo.upsert(transform(it)) }
    }

    fun deleteSetlist(id: String) = repo.delete(id)

    fun rename(id: String, name: String) = change(id) { it.copy(name = name.trim().ifEmpty { it.name }) }

    fun saveSong(id: String, song: Song) {
        change(id) { s ->
            val i = s.songs.indexOfFirst { it.id == song.id }
            s.copy(songs = if (i < 0) s.songs + song else s.songs.toMutableList().also { it[i] = song })
        }
        repo.remember(listOf(song))
    }

    /** Adds copies of library songs, so concert-specific edits don't change other setlists. */
    fun addFromLibrary(id: String, songs: List<Song>) = change(id) { s ->
        s.copy(songs = s.songs + songs.map { it.copy(id = newId()) })
    }

    // --- library ---

    fun saveLibrarySong(song: Song) = repo.saveLibrarySong(song)
    fun deleteLibrarySong(songId: String) = repo.deleteLibrarySong(songId)

    fun deleteSong(id: String, songId: String) = change(id) { s ->
        s.copy(songs = s.songs.filterNot { it.id == songId })
    }

    fun moveSong(id: String, from: Int, delta: Int) = change(id) { s ->
        val to = from + delta
        if (from !in s.songs.indices || to !in s.songs.indices) return@change s
        s.copy(songs = s.songs.toMutableList().also { it.add(to, it.removeAt(from)) })
    }

    /** Appends the instructions of the following song to this one and removes the follower. */
    fun mergeWithNext(id: String, songId: String) = change(id) { s ->
        val i = s.songs.indexOfFirst { it.id == songId }
        if (i < 0 || i >= s.songs.lastIndex) return@change s
        val a = s.songs[i]
        val b = s.songs[i + 1]
        val notes = listOf(a.notes, b.title, b.notes).filter { it.isNotBlank() }.joinToString("\n")
        s.copy(songs = s.songs.toMutableList().apply {
            set(i, a.copy(notes = notes))
            removeAt(i + 1)
        })
    }

    // --- performance ---

    fun next(count: Int) { if (performIndex < count - 1) performIndex++ }
    fun previous() { if (performIndex > 0) performIndex-- }
    fun jumpTo(i: Int) { performIndex = i }
    fun screenOff() { blackout = true }
    fun screenOn() { blackout = false }

    fun updateSettings(transform: (PerformSettings) -> PerformSettings) {
        settings = transform(settings)
        settingsStore.save(settings)
    }

    fun changeFontSize(delta: Float) = updateSettings {
        it.copy(fontSize = (it.fontSize + delta).coerceIn(PerformSettings.MIN_FONT, PerformSettings.MAX_FONT))
    }

    val inPerformance: Boolean get() = screen is Screen.Perform

    /** Hardware keys during a concert. While the screen is "off" any key just wakes it. */
    fun handleKey(key: PerformKey) {
        val current = screen as? Screen.Perform ?: return
        when {
            blackout -> screenOn()
            key == PerformKey.NEXT -> next(repo.get(current.setlistId)?.songs?.size ?: 0)
            else -> previous()
        }
    }
}
