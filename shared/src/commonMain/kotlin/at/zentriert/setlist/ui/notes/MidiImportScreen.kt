package at.zentriert.setlist.ui.notes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.unit.dp
import at.zentriert.setlist.Instrument
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.MidiImportState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun MidiImportScreen(vm: MainViewModel, state: MidiImportState) {
    BackHandler { vm.cancelMidiImport(state) }
    val file = state.file
    val tempo = file.tempoMap.firstOrNull()
    val meter = file.meterMap.firstOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MIDI importieren") },
                navigationIcon = { IconButton(onClick = { vm.cancelMidiImport(state) }) { Icon(Icons.Default.Close, "Abbrechen") } },
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = { vm.finishMidiImport(state) },
                    enabled = state.chosenCount > 0,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("${state.chosenCount} Spuren übernehmen") }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(state.fileName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Wähle pro Spur, wer sie spielt. Mehrere Spuren mit demselben Instrument werden zusammengelegt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (tempo != null || meter != null) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.useTempo, onCheckedChange = { state.useTempo = it })
                    Text("Tempo & Taktart übernehmen (${tempo?.bpm?.let { "$it BPM" } ?: "–"}, ${meter?.label ?: "–"})")
                }
            }
            items(file.tracks, key = { it.index }) { track ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            track.name.ifBlank { "Spur ${track.index + 1}" },
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "Kanal ${track.channels.joinToString { "${it + 1}" }} · Programm ${track.program + 1} · ${track.notes.size} Noten",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = state.assignments[track.index] == null,
                                onClick = { state.assignments[track.index] = null },
                                label = { Text("Weglassen") },
                            )
                            Instrument.entries.forEach { instrument ->
                                FilterChip(
                                    selected = state.assignments[track.index] == instrument,
                                    onClick = { state.assignments[track.index] = instrument },
                                    label = { Text(instrument.label) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
