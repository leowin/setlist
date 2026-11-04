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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileOpen
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun NotesScreen(vm: MainViewModel, setlistId: String?, song: Song, score: Score?) {
    val pickFile = rememberFilePicker { bytes, name -> vm.startMidiImport(bytes, name, ImportTarget(setlistId, song.id)) }
    var withMetronome by remember { mutableStateOf(true) }

    BackHandler { vm.closeNotes(setlistId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { vm.closeNotes(setlistId) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                },
                actions = {
                    IconButton(onClick = pickFile) { Icon(Icons.Default.FileOpen, "MIDI importieren") }
                },
            )
        },
        bottomBar = {
            if (score != null && score.parts.isNotEmpty()) {
                TransportBar(vm, score, withMetronome, onMetronomeChange = { withMetronome = it })
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
                            Text("Importiere eine MIDI-Datei (.mid) – pro Spur wählst du dann, welches Instrument sie spielt.")
                            OutlinedButton(onClick = pickFile) {
                                Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("MIDI-Datei wählen")
                            }
                        }
                    }
                }
            } else {
                item { MixPresets(vm, score) }
                itemsIndexed(score.parts) { _, part ->
                    PartCard(vm, score, part.instrument, part.name, part.notes.size)
                }
            }
        }
    }
}

@Composable
private fun MixPresets(vm: MainViewModel, score: Score) {
    val settings = vm.settings
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Ich spiele", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Instrument.entries.forEach { instrument ->
                FilterChip(
                    selected = settings.myInstrument == instrument,
                    onClick = { vm.setMyInstrument(score, if (settings.myInstrument == instrument) null else instrument) },
                    label = { Text(instrument.label) },
                )
            }
        }
        Text("Ich höre", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MixMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.mixMode == mode,
                    onClick = { vm.setMixMode(score, mode) },
                    label = { Text(mode.label) },
                    enabled = mode == MixMode.ALL || settings.myInstrument != null,
                )
            }
        }
    }
}

@Composable
private fun PartCard(vm: MainViewModel, score: Score, instrument: Instrument, name: String, noteCount: Int) {
    val gain = vm.partGain[instrument] ?: 1f
    val muted = vm.partMuted[instrument] == true
    val solo = vm.solo == instrument
    val audible = vm.effectiveGain(instrument) > 0f
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(instrument.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (name != instrument.label) "$name · $noteCount Noten" else "$noteCount Noten",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

@Composable
private fun TransportBar(vm: MainViewModel, score: Score, withMetronome: Boolean, onMetronomeChange: (Boolean) -> Unit) {
    val tick = vm.playbackTick
    val position = when {
        !vm.playing -> "Takt 1"
        tick == null -> "Vorzähler …"
        else -> ScorePlayback.barBeat(score, tick).let { (bar, beat) -> "Takt $bar · $beat" }
    }
    Row(
        Modifier.fillMaxWidth().padding(12.dp).height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = { if (vm.playing) vm.stopPlayback() else vm.playScore(score, withMetronome = withMetronome) },
            enabled = vm.soundFontReady,
            modifier = Modifier.height(56.dp),
        ) {
            Icon(if (vm.playing) Icons.Default.Stop else Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(6.dp))
            Text(if (vm.playing) "Stop" else "Abspielen", fontSize = 18.sp)
        }
        Text(position, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text("Klick", style = MaterialTheme.typography.labelMedium)
        Switch(checked = withMetronome, onCheckedChange = onMetronomeChange)
    }
}
