package at.zentriert.setlist.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.Screen
import at.zentriert.setlist.ui.band.BandScreen
import at.zentriert.setlist.ui.editor.EditorScreen
import at.zentriert.setlist.ui.notation.NotationScreen
import at.zentriert.setlist.ui.notes.MidiImportScreen
import at.zentriert.setlist.ui.notes.NotesScreen
import at.zentriert.setlist.ui.practice.CalibrationScreen
import at.zentriert.setlist.ui.practice.PracticeDialogs

@Composable
fun App(vm: MainViewModel) {
    SetlistTheme {
        Surface {
            // Re-read on every change so edits show up immediately.
            val data by vm.data.collectAsStateWithLifecycle()
            val scores by vm.scores.collectAsStateWithLifecycle()
            val bandId = vm.currentBandId
            val setlists = data.setlists(bandId)
            val library = data.library(bandId)
            when (val screen = vm.screen) {
                Screen.Home -> HomeScreen(vm, setlists)
                is Screen.Import -> ImportScreen(vm, screen.state)
                Screen.Library -> LibraryScreen(vm, library, scores)
                Screen.Band -> BandScreen(vm)
                is Screen.Calibration -> CalibrationScreen(vm, screen.songId)
                is Screen.Edit -> data.setlists.firstOrNull { it.id == screen.setlistId && !it.deleted }
                    ?.let { EditScreen(vm, it, data.library(it.bandId), scores) }
                    ?: LaunchedEffect(Unit) { vm.goHome() }
                is Screen.Perform -> data.setlists.firstOrNull { it.id == screen.setlistId && !it.deleted }
                    ?.let { PerformScreen(vm, it) }
                    ?: LaunchedEffect(Unit) { vm.goHome() }
                is Screen.Notes -> vm.findSong(screen.setlistId, screen.songId)
                    ?.let { NotesScreen(vm, screen.setlistId, it, scores[it.scoreId]) }
                    ?: LaunchedEffect(Unit) { vm.closeNotes(screen.setlistId) }
                is Screen.MidiImport -> MidiImportScreen(vm, screen.state)
                is Screen.Notation -> {
                    val song = vm.findSong(screen.setlistId, screen.songId)
                    val score = scores[song?.scoreId]
                    if (song != null && score != null) NotationScreen(vm, screen.setlistId, song, score)
                    else LaunchedEffect(Unit) { vm.closeNotes(screen.setlistId) }
                }
                is Screen.Editor -> {
                    val song = vm.findSong(screen.setlistId, screen.songId)
                    val score = scores[song?.scoreId]
                    if (song != null && score != null && score.parts.any { it.instrument == screen.instrument }) {
                        EditorScreen(vm, screen.setlistId, song, score, screen.instrument)
                    } else LaunchedEffect(Unit) { vm.closeNotes(screen.setlistId) }
                }
            }
            LinkDialogs(vm)
            PracticeDialogs(vm)
        }
    }
}
