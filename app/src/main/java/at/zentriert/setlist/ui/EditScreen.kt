package at.zentriert.setlist.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.Setlist
import at.zentriert.setlist.Song

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(vm: MainViewModel, setlist: Setlist, library: List<Song>) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Song?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }

    BackHandler { vm.goHome() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(setlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { vm.goHome() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                },
                actions = {
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Umbenennen") }
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, setlist.name)
                            .putExtra(Intent.EXTRA_TEXT, setlist.toText())
                        context.startActivity(Intent.createChooser(send, "Setlist teilen"))
                    }) { Icon(Icons.Default.Share, "Als Text teilen") }
                },
            )
        },
        floatingActionButton = {
            if (setlist.songs.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { vm.perform(setlist.id) },
                    icon = { Icon(Icons.Default.PlayArrow, null) },
                    text = { Text("Konzert") },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(setlist.songs, key = { _, song -> song.id }) { i, song ->
                Card(onClick = { editing = song }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${i + 1}",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.widthIn(min = 32.dp),
                        )
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(song.title, style = MaterialTheme.typography.titleMedium)
                            if (song.notes.isNotBlank()) {
                                Text(
                                    song.notes,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Column {
                            IconButton(onClick = { vm.moveSong(setlist.id, i, -1) }, enabled = i > 0) {
                                Icon(Icons.Default.KeyboardArrowUp, "Nach oben")
                            }
                            IconButton(onClick = { vm.moveSong(setlist.id, i, 1) }, enabled = i < setlist.songs.lastIndex) {
                                Icon(Icons.Default.KeyboardArrowDown, "Nach unten")
                            }
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { editing = Song(title = "") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Neues Lied")
                }
            }
            item {
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth(), enabled = library.isNotEmpty()) {
                    Icon(Icons.Default.LibraryMusic, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Aus Lieder-Bibliothek hinzufügen")
                }
            }
        }
    }

    editing?.let { song ->
        val index = setlist.songs.indexOfFirst { it.id == song.id }
        SongDialog(
            song = song,
            isNew = index < 0,
            canMerge = index in 0 until setlist.songs.lastIndex,
            onSave = { vm.saveSong(setlist.id, it); editing = null },
            onDelete = { vm.deleteSong(setlist.id, song.id); editing = null },
            onMerge = { vm.saveSong(setlist.id, it); vm.mergeWithNext(setlist.id, song.id); editing = null },
            onDismiss = { editing = null },
        )
    }

    if (picking) {
        LibraryPicker(
            library = library,
            onAdd = { vm.addFromLibrary(setlist.id, it); picking = false },
            onDismiss = { picking = false },
        )
    }

    if (renaming) {
        var name by remember { mutableStateOf(setlist.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Setlist umbenennen") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(setlist.id, name); renaming = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Abbrechen") } },
        )
    }
}
