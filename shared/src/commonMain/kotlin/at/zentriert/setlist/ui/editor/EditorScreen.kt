package at.zentriert.setlist.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.zentriert.setlist.Instrument
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.Score
import at.zentriert.setlist.Song
import at.zentriert.setlist.audio.ScorePlayback
import at.zentriert.setlist.edit.LoopBars
import at.zentriert.setlist.edit.NoteValue
import at.zentriert.setlist.ui.Amber
import at.zentriert.setlist.ui.InstrumentDropdown
import at.zentriert.setlist.ui.transport.TransportBar
import at.zentriert.setlist.ui.transport.TransportOptions
import at.zentriert.setlist.ui.transport.editorStartTick

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun EditorScreen(vm: MainViewModel, setlistId: String?, song: Song, score: Score, instrument: Instrument) {
    var mode by remember { mutableStateOf(EditMode.DRAW) }
    var beatDp by remember { mutableStateOf(48f) }
    var assignPads by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showLoop by remember { mutableStateOf(false) }
    val settings = vm.settings

    BackHandler { vm.openNotes(setlistId, song.id) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${song.title} · ${instrument.label}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { vm.openNotes(setlistId, song.id) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
                actions = {
                    IconButton(onClick = { vm.undo(score) }, enabled = vm.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Rückgängig") }
                    IconButton(onClick = { vm.redo(score) }, enabled = vm.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Wiederholen") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Tune, "Einstellungen") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Tools (instrument first: existing parts by name, missing ones as "+ …")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InstrumentDropdown(
                    selected = instrument,
                    onSelect = { inst -> if (inst != null && inst != instrument) vm.openEditor(setlistId, song, inst) },
                    labelFor = { inst -> (if (score.parts.any { it.instrument == inst }) "" else "+ ") + "${inst.symbol}  ${inst.label}" },
                )
                EditMode.entries.forEach { m -> FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) }) }
                Spacer(Modifier.width(8.dp))
                NoteValue.CHOICES.filter { it >= 2 }.forEach { div ->
                    FilterChip(
                        selected = settings.noteDivision == div,
                        onClick = { vm.updateSettings { it.copy(noteDivision = div) } },
                        label = { Text("1/$div") },
                    )
                }
                FilterChip(selected = settings.noteTriplet, onClick = { vm.updateSettings { it.copy(noteTriplet = !it.noteTriplet) } }, label = { Text("Triole") })
                IconButton(onClick = { beatDp = (beatDp / 1.4f).coerceAtLeast(16f) }) { Icon(Icons.Default.ZoomOut, "Kleiner") }
                IconButton(onClick = { beatDp = (beatDp * 1.4f).coerceAtMost(200f) }) { Icon(Icons.Default.ZoomIn, "Größer") }
                if (vm.editSelection.isNotEmpty()) {
                    TextButton(onClick = { vm.deleteSelection(score, instrument) }) { Icon(Icons.Default.Delete, null); Text(" ${vm.editSelection.size}") }
                }
                TextButton(onClick = { vm.quantizeSelection(score, instrument) }) { Text(if (vm.editSelection.isEmpty()) "Alles quantisieren" else "Quantisieren") }
                if (instrument == Instrument.DRUMS) {
                    FilterChip(selected = assignPads, onClick = { assignPads = !assignPads }, label = { Text("Pads belegen") })
                }
            }

            RollEditor(vm, score, instrument, mode, beatDp, Modifier.weight(1f).fillMaxWidth())

            TransportBar(
                vm, song, score,
                TransportOptions(setlistId = setlistId, recordInstrument = instrument, onLoop = { showLoop = true }, showStepInput = true, startTick = { editorStartTick(vm, score) }),
            )

            InputPanel(vm, score, instrument, assignPads, Modifier.height(if (instrument == Instrument.DRUMS) 120.dp else 150.dp).background(Color(0xFF1A1A1A)).navigationBarsPadding())
        }
    }

    if (showSettings) EditorSettingsDialog(vm, onDismiss = { showSettings = false })
    if (showLoop) LoopDialog(vm, score, onDismiss = { showLoop = false })
}

@Composable
private fun LoopDialog(vm: MainViewModel, score: Score, onDismiss: () -> Unit) {
    val current = vm.loop
    var from by remember { mutableStateOf((current?.from ?: 1).toString()) }
    var to by remember { mutableStateOf((current?.to ?: 4).toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Loop (Takte)") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(from, { from = it }, label = { Text("Von") }, singleLine = true, modifier = Modifier.width(96.dp))
                OutlinedTextField(to, { to = it }, label = { Text("Bis") }, singleLine = true, modifier = Modifier.width(96.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = from.toIntOrNull(); val b = to.toIntOrNull()
                if (a != null && b != null && a >= 1 && b >= a) { vm.loop = LoopBars(a, b); vm.cursorTick = LoopBars(a, b).ticks(score).first }
                onDismiss()
            }) { Text("Setzen") }
        },
        dismissButton = { Row { TextButton(onClick = { vm.loop = null; onDismiss() }) { Text("Kein Loop") }; TextButton(onClick = onDismiss) { Text("Abbrechen") } } },
    )
}

@Composable
private fun EditorSettingsDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val s = vm.settings
    var calibrationResult by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = { if (vm.calibrating) vm.finishCalibration(); onDismiss() },
        title = { Text(if (vm.calibrating) "Latenz kalibrieren" else "Editor") },
        text = {
            if (vm.calibrating) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Tippe mindestens 8-mal genau auf den Klick. Die ersten zwei Taps zählen nicht.")
                    Box(
                        Modifier.fillMaxWidth().height(140.dp).background(Amber, MaterialTheme.shapes.large).clickable { vm.calibrationTap() },
                        contentAlignment = Alignment.Center,
                    ) { Text("TAP  (${vm.calibrationTaps})", color = Color.Black, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Quantisieren beim Aufnehmen", Modifier.weight(1f))
                    Switch(checked = s.quantizeOn, onCheckedChange = { v -> vm.updateSettings { it.copy(quantizeOn = v) } })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(4, 8, 16, 32).forEach { div ->
                        FilterChip(selected = s.quantizeDivision == div, onClick = { vm.updateSettings { it.copy(quantizeDivision = div) } }, label = { Text("1/$div") })
                    }
                }
                Text("Stärke ${(s.quantizeStrength * 100).toInt()} %")
                Slider(value = s.quantizeStrength, onValueChange = { v -> vm.updateSettings { it.copy(quantizeStrength = v) } })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Aufnahme ersetzt Vorhandenes", Modifier.weight(1f))
                    Switch(checked = s.recordReplace, onCheckedChange = { v -> vm.updateSettings { it.copy(recordReplace = v) } })
                }
                Text(
                    "Latenz: " + (if (s.inputLatencyMs >= 0) "${s.inputLatencyMs} ms (kalibriert)" else "automatisch ≈ ${(vm.inputLatencySeconds() * 1000).toInt()} ms") +
                        (calibrationResult?.let { " → neu $it ms" } ?: ""),
                )
                OutlinedButton(onClick = { vm.startCalibration() }) { Text("Im Takt tippen …") }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (vm.calibrating) calibrationResult = vm.finishCalibration() else onDismiss() }) {
                Text(if (vm.calibrating) "Fertig" else "Schließen")
            }
        },
    )
}
