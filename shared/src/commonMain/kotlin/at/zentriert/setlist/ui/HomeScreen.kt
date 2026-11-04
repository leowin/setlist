package at.zentriert.setlist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import at.zentriert.setlist.ui.band.BandSelector
import at.zentriert.setlist.ui.band.CopyToBandDialog
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.Setlist

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, setlists: List<Setlist>) {
    var toDelete by remember { mutableStateOf<Setlist?>(null) }
    var toCopy by remember { mutableStateOf<Setlist?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { BandSelector(vm.bands, vm.currentBandId, onSelect = vm::selectBand) },
                actions = {
                    TextButton(onClick = vm::openBand) {
                        Icon(Icons.Default.Groups, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (vm.bandRole != null) "Band ●" else "Band")
                    }
                    TextButton(onClick = vm::openLibrary) {
                        Icon(Icons.Default.LibraryMusic, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Lieder")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.startImport("", "") },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Neue Setlist") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (setlists.isEmpty()) item { ImportHint() }
            items(setlists.sortedByDescending { it.updatedAt }, key = { it.id }) { setlist ->
                Card(onClick = { vm.edit(setlist.id) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(setlist.name, style = MaterialTheme.typography.titleLarge)
                            Text(
                                "${setlist.songs.size} Lieder",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        SetlistMenu(
                            onCopy = { toCopy = setlist },
                            onShareLink = if (vm.canShareLinks) ({ vm.shareSetlistLink(setlist.id) }) else null,
                            onDelete = { toDelete = setlist },
                        )
                        FilledIconButton(onClick = { vm.perform(setlist.id) }, enabled = setlist.songs.isNotEmpty()) {
                            Icon(Icons.Default.PlayArrow, "Konzert starten")
                        }
                    }
                }
            }
        }
    }

    toCopy?.let { setlist ->
        CopyToBandDialog(
            title = "„${setlist.name}“ kopieren nach …",
            bands = vm.bands, currentBandId = vm.currentBandId,
            onPick = { vm.copySetlistToBand(setlist.id, it); toCopy = null },
            onDismiss = { toCopy = null },
        )
    }
    toDelete?.let { setlist ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Setlist löschen?") },
            text = { Text("„${setlist.name}“ wird endgültig gelöscht.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteSetlist(setlist.id); toDelete = null }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Abbrechen") } },
        )
    }
}

/** ⋮ on a setlist card: copy into another band, share as link, delete. */
@Composable
fun SetlistMenu(onCopy: () -> Unit, onShareLink: (() -> Unit)?, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "Mehr") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("In andere Band kopieren …") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { open = false; onCopy() })
            if (onShareLink != null) DropdownMenuItem(text = { Text("Als Link teilen …") }, leadingIcon = { Icon(Icons.Default.Link, null) }, onClick = { open = false; onShareLink() })
            DropdownMenuItem(text = { Text("Löschen") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { open = false; onDelete() })
        }
    }
}

@Composable
private fun ImportHint() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("So kommt deine Setlist in die App", style = MaterialTheme.typography.titleMedium)
            Text("1. Text der Setlist kopieren (z. B. aus Google Notizen)")
            Text("2. „Neue Setlist“ → „Aus Zwischenablage einfügen“")
            Text("3. In Lieder zerschneiden und übernehmen")
            Text(
                "Tipp: Aus Google Notizen geht es auch direkt über ⋮ → „Senden“ → Setlist.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
