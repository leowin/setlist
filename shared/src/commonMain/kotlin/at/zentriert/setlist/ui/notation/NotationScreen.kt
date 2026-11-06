package at.zentriert.setlist.ui.notation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.Score
import at.zentriert.setlist.Song
import at.zentriert.setlist.ui.notes.ViewToggle
import at.zentriert.setlist.ui.transport.TransportBar
import at.zentriert.setlist.ui.transport.TransportOptions

/** Sheet music of one song: the shared [NotationPane] plus the shared [TransportBar]. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun NotationScreen(vm: MainViewModel, setlistId: String?, song: Song, score: Score) {
    BackHandler { vm.openNotes(setlistId, song.id) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { vm.openNotes(setlistId, song.id) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
                actions = { ViewToggle(notes = true, onMixer = { vm.openNotes(setlistId, song.id) }, onNotes = {}) },
            )
        },
        bottomBar = { TransportBar(vm, song, score, TransportOptions(setlistId = setlistId, navigationPadding = true)) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(Color.Black)) {
            NotationPane(vm, song, score, Modifier.weight(1f).fillMaxWidth())
        }
    }
}
