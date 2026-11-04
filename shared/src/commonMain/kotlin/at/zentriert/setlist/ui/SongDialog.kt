package at.zentriert.setlist.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.zentriert.setlist.Score
import at.zentriert.setlist.Song
import at.zentriert.setlist.TempoEdit
import at.zentriert.setlist.currentTimeMillis
import at.zentriert.setlist.toBpmText

@Composable
fun SongDialog(
    song: Song,
    score: Score?,
    isNew: Boolean,
    canMerge: Boolean,
    onSave: (Song, TempoEdit?) -> Unit,
    onDelete: () -> Unit,
    onMerge: (Song, TempoEdit?) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(song.title) }
    var notes by remember { mutableStateOf(song.notes) }
    var tempo by remember { mutableStateOf(TempoEdit.of(score)) }
    fun edited() = song.copy(title = title.trim(), notes = notes.trimEnd())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Neues Lied" else "Lied bearbeiten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Titel") }, singleLine = true)
                OutlinedTextField(
                    notes, { notes = it },
                    label = { Text("Anweisungen") },
                    modifier = Modifier.heightIn(min = 120.dp),
                )
                TempoEditor(tempo, onChange = { tempo = it })
                if (!isNew) {
                    Row {
                        TextButton(onClick = onDelete) { Text("Löschen") }
                        if (canMerge) TextButton(onClick = { onMerge(edited(), tempo) }) { Text("Mit nächstem verbinden") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(edited(), tempo) }, enabled = title.isNotBlank()) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Tempo in BPM (typed, ±, or tapped) and meter. `null` = the song has no tempo yet. */
@Composable
fun TempoEditor(tempo: TempoEdit?, onChange: (TempoEdit?) -> Unit) {
    if (tempo == null) {
        OutlinedButton(onClick = { onChange(TempoEdit.DEFAULT) }) { Text("Tempo & Taktart festlegen") }
        return
    }
    var bpmText by remember(tempo.bpm) { mutableStateOf(tempo.bpm.toBpmText()) }
    val taps = remember { mutableListOf<Long>() }

    Text("Tempo & Taktart", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(tempo.copy(bpm = (tempo.bpm - 1).coerceAtLeast(Score.MIN_BPM))) }) {
            Icon(Icons.Default.Remove, "Langsamer")
        }
        OutlinedTextField(
            value = bpmText,
            onValueChange = { text ->
                bpmText = text
                text.replace(',', '.').toDoubleOrNull()
                    ?.takeIf { it in Score.MIN_BPM..Score.MAX_BPM }
                    ?.let { onChange(tempo.copy(bpm = it)) }
            },
            label = { Text("BPM") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(96.dp),
        )
        IconButton(onClick = { onChange(tempo.copy(bpm = (tempo.bpm + 1).coerceAtMost(Score.MAX_BPM))) }) {
            Icon(Icons.Default.Add, "Schneller")
        }
        Spacer(Modifier.width(4.dp))
        OutlinedButton(onClick = {
            // Tap tempo: mean of the last four intervals; a pause of 2 s starts over.
            val now = currentTimeMillis()
            if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
            taps += now
            if (taps.size > 5) taps.removeAt(0)
            if (taps.size >= 2) {
                val meanMillis = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
                val bpm = (60_000.0 / meanMillis * 10).toInt() / 10.0
                onChange(tempo.copy(bpm = bpm.coerceIn(Score.MIN_BPM, Score.MAX_BPM)))
            }
        }) { Text("Tap", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Score.METERS.forEach { (num, den) ->
            FilterChip(
                selected = tempo.numerator == num && tempo.denominator == den,
                onClick = { onChange(tempo.copy(numerator = num, denominator = den)) },
                label = { Text("$num/$den") },
            )
        }
    }
    TextButton(onClick = { onChange(null) }, modifier = Modifier.padding(0.dp)) { Text("Kein Tempo") }
}
