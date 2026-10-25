package at.zentriert.setlist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import at.zentriert.setlist.Song

@Composable
fun SongDialog(
    song: Song,
    isNew: Boolean,
    canMerge: Boolean,
    onSave: (Song) -> Unit,
    onDelete: () -> Unit,
    onMerge: (Song) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(song.title) }
    var notes by remember { mutableStateOf(song.notes) }
    fun edited() = song.copy(title = title.trim(), notes = notes.trimEnd())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Neues Lied" else "Lied bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Titel") }, singleLine = true)
                OutlinedTextField(
                    notes, { notes = it },
                    label = { Text("Anweisungen") },
                    modifier = Modifier.heightIn(min = 160.dp),
                )
                if (!isNew) {
                    Row {
                        TextButton(onClick = onDelete) { Text("Löschen") }
                        if (canMerge) TextButton(onClick = { onMerge(edited()) }) { Text("Mit nächstem verbinden") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(edited()) },
                enabled = title.isNotBlank(),
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
