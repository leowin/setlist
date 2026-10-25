package at.zentriert.setlist.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.Song

private fun List<Song>.search(query: String): List<Song> {
    val q = query.trim()
    return filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) || it.notes.contains(q, ignoreCase = true) }
        .sortedBy { it.title.lowercase() }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) =
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Suchen") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: MainViewModel, library: List<Song>) {
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Song?>(null) }

    BackHandler { vm.goHome() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lieder-Bibliothek (${library.size})") },
                navigationIcon = {
                    IconButton(onClick = vm::goHome) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = Song(title = "") }) { Icon(Icons.Default.Add, "Neues Lied") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            SearchField(query, { query = it })
            LazyColumn(
                contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (library.isEmpty()) item {
                    Text(
                        "Noch leer. Jedes Lied, das du importierst oder in einer Setlist bearbeitest, " +
                            "landet samt Anweisungen automatisch hier.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(library.search(query), key = { it.id }) { song ->
                    Card(onClick = { editing = song }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
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
                    }
                }
            }
        }
    }

    editing?.let { song ->
        SongDialog(
            song = song,
            isNew = library.none { it.id == song.id },
            canMerge = false,
            onSave = { vm.saveLibrarySong(it); editing = null },
            onDelete = { vm.deleteLibrarySong(song.id); editing = null },
            onMerge = {},
            onDismiss = { editing = null },
        )
    }
}

/** Multi-select dialog to put library songs onto a setlist, in the order they were ticked. */
@Composable
fun LibraryPicker(library: List<Song>, onAdd: (List<Song>) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<Song>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aus Bibliothek hinzufügen") },
        text = {
            Column {
                SearchField(query, { query = it })
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(library.search(query), key = { it.id }) { song ->
                        val position = picked.indexOf(song)
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { if (position < 0) picked += song else picked.remove(song) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = position >= 0, onCheckedChange = null)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(song.title, style = MaterialTheme.typography.bodyLarge)
                                if (song.notes.isNotBlank()) {
                                    Text(
                                        song.notes,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (position >= 0) {
                                Text("${position + 1}", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(picked.toList()) }, enabled = picked.isNotEmpty()) {
                Text("${picked.size} hinzufügen")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
