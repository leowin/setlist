package at.zentriert.setlist.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.Screen
import at.zentriert.setlist.ui.editor.EditorScreen
import at.zentriert.setlist.ui.notes.MidiImportScreen
import at.zentriert.setlist.ui.notes.NotesScreen

@Composable
fun App(vm: MainViewModel) {
    SetlistTheme {
        Surface {
            // Re-read on every change so edits show up immediately.
            val data by vm.data.collectAsStateWithLifecycle()
            val scores by vm.scores.collectAsStateWithLifecycle()
            val setlists = data.setlists
            when (val screen = vm.screen) {
                Screen.Home -> HomeScreen(vm, setlists)
                is Screen.Import -> ImportScreen(vm, screen.state)
                Screen.Library -> LibraryScreen(vm, data.library, scores)
                is Screen.Edit -> setlists.firstOrNull { it.id == screen.setlistId }
                    ?.let { EditScreen(vm, it, data.library, scores) }
                    ?: LaunchedEffect(Unit) { vm.goHome() }
                is Screen.Perform -> setlists.firstOrNull { it.id == screen.setlistId }
                    ?.let { PerformScreen(vm, it) }
                    ?: LaunchedEffect(Unit) { vm.goHome() }
                is Screen.Notes -> vm.findSong(screen.setlistId, screen.songId)
                    ?.let { NotesScreen(vm, screen.setlistId, it, scores[it.scoreId]) }
                    ?: LaunchedEffect(Unit) { vm.closeNotes(screen.setlistId) }
                is Screen.MidiImport -> MidiImportScreen(vm, screen.state)
                is Screen.Editor -> {
                    val song = vm.findSong(screen.setlistId, screen.songId)
                    val score = scores[song?.scoreId]
                    if (song != null && score != null && score.parts.any { it.instrument == screen.instrument }) {
                        EditorScreen(vm, screen.setlistId, song, score, screen.instrument)
                    } else LaunchedEffect(Unit) { vm.closeNotes(screen.setlistId) }
                }
            }
        }
    }
}
