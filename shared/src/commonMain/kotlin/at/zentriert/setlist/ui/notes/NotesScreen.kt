package at.zentriert.setlist.ui.notes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.zentriert.setlist.ImportTarget
import at.zentriert.setlist.Instrument
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.MixMode
import at.zentriert.setlist.Score
import at.zentriert.setlist.Song
import at.zentriert.setlist.audio.ScorePlayback
import at.zentriert.setlist.rememberFilePicker
import at.zentriert.setlist.ui.InstrumentDropdown
import at.zentriert.setlist.ui.metronome.Dropdown
import at.zentriert.setlist.ui.transport.TransportBar
import at.zentriert.setlist.ui.transport.TransportOptions

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun NotesScreen(vm: MainViewModel, setlistId: String?, song: Song, score: Score?) {
    val pickFile = rememberFilePicker { bytes, name -> vm.startMidiImport(bytes, name, ImportTarget(setlistId, song.id)) }

    BackHandler { vm.closeNotes(setlistId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { vm.closeNotes(setlistId) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                },
                actions = {
                    if (score != null && score.parts.any { it.notes.isNotEmpty() }) {
                        ViewToggle(notes = false, onNotes = { vm.openNotation(setlistId, song.id) }, onMixer = {})
                    }
                    IconButton(onClick = pickFile) { Icon(Icons.Default.FileOpen, "MIDI importieren") }
                },
            )
        },
        bottomBar = {
            if (score != null && score.parts.isNotEmpty()) {
                TransportBar(vm, song, score, TransportOptions(setlistId = setlistId, metronomeToggle = true, navigationPadding = true))
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    if (score != null) "♩ ${score.summary}" else "Noch kein Tempo",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (!vm.soundFontReady) {
                    Text("Klänge werden geladen …", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                vm.importError?.let { error ->
                    Snackbar(action = { TextButton(onClick = { vm.importError = null }) { Text("OK") } }) { Text(error) }
                }
            }
            if (score == null || score.parts.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Noch keine Noten", style = MaterialTheme.typography.titleMedium)
                            Text("Importiere eine MIDI-Datei (.mid) – pro Spur wählst du dann, welches Instrument sie spielt – oder spiele die Noten selbst ein.")
                            OutlinedButton(onClick = pickFile) {
                                Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("MIDI-Datei wählen")
                            }
                        }
                    }
                }
            } else {
                item { MixPresets(vm, score) }
                itemsIndexed(score.parts) { _, part ->
                    PartCard(vm, score, part.instrument, part.name, part.notes.size, onEdit = { vm.openEditor(setlistId, song, part.instrument) })
                }
            }
            item {
                val missing = Instrument.entries.filter { inst -> score?.parts?.none { it.instrument == inst } ?: true }
                if (missing.isNotEmpty()) {
                    InstrumentDropdown(
                        selected = null, options = missing, placeholder = "+ Stimme hinzufügen",
                        onSelect = { inst -> inst?.let { vm.openEditor(setlistId, song, it) } },
                        labelFor = { "+ ${it.symbol}  ${it.label}" },
                    )
                }
            }
        }
    }
}

@Composable
private fun MixPresets(vm: MainViewModel, score: Score) {
    val settings = vm.settings
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ich spiele", style = MaterialTheme.typography.labelLarge)
            InstrumentDropdown(selected = vm.myInstrument, noneLabel = "– keins –", onSelect = { vm.setMyInstrument(score, it) })
            vm.currentBand?.let { Text("für ${it.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Dropdown(
            label = "Ich höre",
            options = MixMode.entries.filter { it == MixMode.ALL || it == MixMode.NONE || vm.myInstrument != null }.map { it to it.label },
            selected = settings.mixMode,
            onSelect = { vm.setMixMode(score, it) },
        )
    }
}

@Composable
private fun PartCard(vm: MainViewModel, score: Score, instrument: Instrument, name: String, noteCount: Int, onEdit: () -> Unit) {
    val mix = vm.mixOf(score, instrument)
    val gain = mix.gain
    val muted = mix.muted
    val solo = vm.soloOf(score) == instrument
    val audible = vm.effectiveGain(score, instrument) > 0f
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${instrument.symbol} ${instrument.label}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (name != instrument.label) "$name · $noteCount Noten" else "$noteCount Noten",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Noten bearbeiten") }
                FilterChip(selected = solo, onClick = { vm.toggleSolo(score, instrument) }, label = { Text("Solo") })
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = { vm.toggleMute(score, instrument) }) {
                    Icon(
                        if (audible) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        if (muted) "Stumm" else "Hörbar",
                        tint = if (audible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Slider(value = gain, onValueChange = { vm.setPartGain(score, instrument, it) }, valueRange = 0f..1f)
        }
    }
}

/** Big, easy-to-hit switch between the mixer (parts, volumes) and the sheet music of a song. */
@Composable
fun ViewToggle(notes: Boolean, onMixer: () -> Unit, onNotes: () -> Unit) {
    Row(Modifier.padding(end = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(selected = !notes, onClick = onMixer, label = { Text("Mixer") }, leadingIcon = { Icon(Icons.Default.Tune, null) })
        FilterChip(selected = notes, onClick = onNotes, label = { Text("Noten") }, leadingIcon = { Icon(Icons.Default.LibraryMusic, null) })
    }
}
