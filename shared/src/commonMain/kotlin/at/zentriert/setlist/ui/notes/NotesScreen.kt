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
import androidx.compose.material.icons.filled.GraphicEq
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
import at.zentriert.setlist.AudioTrack
import at.zentriert.setlist.toBpmText
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
    var pendingAudio by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }
    var editingTrack by remember { mutableStateOf<AudioTrack?>(null) }
    val pickAudio = rememberFilePicker { bytes, name -> pendingAudio = bytes to name }

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
            if (score != null && (score.parts.isNotEmpty() || score.tracks.isNotEmpty())) {
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
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Audiospuren (MP3)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Aufnahmen laufen zum Metronom und zu den Noten mit und werden live auf das aktuelle Tempo gestreckt (Tonhöhe bleibt). " +
                                "Die Datei wird verschlüsselt mit der Band abgeglichen (bis 28 MB), damit alle sie haben.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        score?.tracks?.forEach { track -> TrackRow(vm, score, track, onEdit = { editingTrack = track }, onCue = { vm.openCue(setlistId, song.id, track.id) }) }
                        OutlinedButton(onClick = pickAudio, enabled = (score?.tracks?.size ?: 0) < 4) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Audiodatei hinzufügen") }
                        vm.trackError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
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

    pendingAudio?.let { (bytes, name) ->
        TrackDialog(
            title = name, defaultBpm = score?.bpm ?: 120.0, track = null,
            onSave = { inst, bpm, off -> vm.addTrack(song, score, bytes, name, inst, bpm, off, setlistId); pendingAudio = null },
            onDelete = null, onDismiss = { pendingAudio = null },
        )
    }
    editingTrack?.let { track ->
        if (score != null) TrackDialog(
            title = track.name, defaultBpm = score.bpm, track = track,
            onSave = { inst, bpm, off -> vm.updateTrack(score, track.copy(instrument = inst, originalBpm = bpm, offsetMs = off)); editingTrack = null },
            onDelete = { vm.removeTrack(score, track); editingTrack = null }, onDismiss = { editingTrack = null },
        )
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

@Composable
private fun TrackRow(vm: MainViewModel, score: Score, track: AudioTrack, onEdit: () -> Unit, onCue: () -> Unit) {
    val mix = vm.personalMix(score).trackMixOf(track.id)
    val solo = vm.personalMix(score).soloTrack == track.id
    val audible = vm.effectiveTrackGain(score, track) > 0f
    val missing = vm.trackFileMissing(track)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onEdit)) {
                Text("🎧 ${track.name}", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (track.instrument?.let { "${it.symbol} ${it.label}" } ?: "Alle") + " · ♩ ${track.originalBpm.toInt()}" +
                        (if (track.offsetMs != 0) " · Start ${track.offsetMs} ms" else "") +
                        when { missing -> " · Datei noch nicht da (kommt mit dem Abgleich)"; track.id in vm.decodingTracks -> " · lädt …"; else -> "" },
                    style = MaterialTheme.typography.bodySmall, color = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onCue, enabled = !missing) { Icon(Icons.Default.GraphicEq, "Startpunkt (1-1) setzen") }
            FilterChip(selected = solo, onClick = { vm.toggleTrackSolo(score, track) }, label = { Text("Solo") })
            IconButton(onClick = { vm.toggleTrackMute(score, track) }) {
                Icon(if (audible) Icons.Default.VolumeUp else Icons.Default.VolumeOff, if (mix.muted) "Stumm" else "Hörbar",
                    tint = if (audible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Slider(value = mix.gain, onValueChange = { vm.setTrackGain(score, track, it) }, valueRange = 0f..1f)
    }
}

/** Instrument (or "Alle"), the recording's tempo and where beat 1 starts in it. */
@Composable
fun TrackDialog(title: String, defaultBpm: Double, track: AudioTrack?, onSave: (Instrument?, Double, Int) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var instrument by remember { mutableStateOf(track?.instrument) }
    var bpm by remember { mutableStateOf((track?.originalBpm ?: defaultBpm).toBpmText()) }
    var offset by remember { mutableStateOf((track?.offsetMs ?: 0).toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enthält", style = MaterialTheme.typography.labelLarge)
                InstrumentDropdown(selected = instrument, noneLabel = "Alle (ganze Band)", onSelect = { instrument = it })
                OutlinedTextField(bpm, { bpm = it }, label = { Text("Tempo der Aufnahme (BPM)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(offset, { offset = it }, label = { Text("Beginn von Takt 1 in der Aufnahme (ms)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Spielt das Lied schneller oder langsamer als die Aufnahme, wird sie passend gestreckt. Negativer Beginn = die Aufnahme startet nach Takt 1.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(instrument, bpm.replace(',', '.').toDoubleOrNull() ?: defaultBpm, offset.toIntOrNull() ?: 0) }) { Text("Übernehmen") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Entfernen") }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}

/** Big, easy-to-hit switch between the mixer (parts, volumes) and the sheet music of a song. */
@Composable
fun ViewToggle(notes: Boolean, onMixer: () -> Unit, onNotes: () -> Unit) {
    Row(Modifier.padding(end = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(selected = !notes, onClick = onMixer, label = { Text("Mixer") }, leadingIcon = { Icon(Icons.Default.Tune, null) })
        FilterChip(selected = notes, onClick = onNotes, label = { Text("Noten") }, leadingIcon = { Icon(Icons.Default.LibraryMusic, null) })
    }
}
