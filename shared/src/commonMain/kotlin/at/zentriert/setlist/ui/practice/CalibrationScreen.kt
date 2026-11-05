package at.zentriert.setlist.ui.practice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.zentriert.setlist.Instrument
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.Score
import at.zentriert.setlist.ui.InstrumentDropdown
import at.zentriert.setlist.ui.metronome.Dropdown
import at.zentriert.setlist.edit.DrumNames
import at.zentriert.setlist.rememberMicrophonePermission
import at.zentriert.setlist.ui.Amber

/** Reference notes to learn an instrument's sound from: open strings (and the 12th fret) or one octave on the keys. */
fun referenceNotes(instrument: Instrument): List<Int> = when (instrument) {
    Instrument.BASS -> listOf(28, 33, 38, 43, 40, 45, 50, 55)            // E1 A1 D2 G2 + 12th fret
    Instrument.GUITAR -> listOf(40, 45, 50, 55, 59, 64, 52, 57, 62, 67)  // E2 A2 D3 G3 B3 E4 + 12th fret of the low strings
    Instrument.KEYS -> listOf(48, 50, 52, 53, 55, 57, 59, 60)            // C3 … C4
    Instrument.VOCALS -> listOf(48, 52, 55, 60, 64, 67)                   // comfortable mid range
    Instrument.DRUMS -> emptyList()
}

/** Teach the phone the drums (five hits each), test the recognition live, set the microphone delay. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun CalibrationScreen(vm: MainViewModel, songId: String?) {
    val settings = vm.settings
    val pads = (vm.findSong(null, songId.orEmpty())?.let { vm.score(it) } ?: Score()).drumPads
    val requestMic = rememberMicrophonePermission { if (it) vm.startListening() else vm.micError = "Ohne Mikrofon-Berechtigung kann die App nicht zuhören." }
    val back = { vm.calibratingPad = null; if (songId != null) vm.openNotes(null, songId) else vm.goHome() }

    DisposableEffect(Unit) { if (!vm.listening) requestMic(); onDispose { } }
    BackHandler { back() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Instrument justieren") },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                vm.micError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!vm.listening) TextButton(onClick = requestMic) { Text("Mikrofon einschalten") }
                Text("Mikrofon-Verzögerung und Toleranz stellst du unter ⚙ → Zuhören ein.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Instrument", style = MaterialTheme.typography.titleMedium)
                    InstrumentDropdown(selected = vm.calibrationInstrument, onSelect = { it?.let(vm::chooseCalibrationInstrument) })
                }
                Text(
                    if (vm.calibrationInstrument == Instrument.DRUMS)
                        "Je Trommel fünf Schläge aufnehmen; danach erkennt die App, welche Trommel du triffst, und bewertet auch das. " +
                            "Unten zeigt der Live-Test, was sie gerade hört."
                    else
                        "Referenztöne einzeln anspielen und kurz klingen lassen: die App lernt daraus den Klang des Instruments " +
                            "(Obertonverteilung) und erkennt Töne und Akkorde besser. Ohne Lernen gilt ein Standardklang. " +
                            "Der Live-Test zeigt den erkannten Ton mit Abweichung in Cent" +
                            (if (vm.calibrationInstrument == Instrument.GUITAR || vm.calibrationInstrument == Instrument.KEYS) " bzw. alle Töne eines Akkords." else "."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (vm.calibrationInstrument != Instrument.DRUMS) {
                val inst = vm.calibrationInstrument
                val learned = vm.calibration.learnedNotes[inst.name].orEmpty()
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (learned.isEmpty()) "Standardklang" else "Gelernt aus ${learned.size} Tönen: ${learned.sorted().joinToString(" ") { DrumNames.noteName(it) }}",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        )
                        if (learned.isNotEmpty()) TextButton(onClick = { vm.clearProfile(inst) }) { Text("Zurücksetzen") }
                    }
                }
                items(referenceNotes(inst)) { midi ->
                    val active = vm.learningNote == midi
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(DrumNames.noteName(midi), fontWeight = FontWeight.Bold)
                                Text(if (active) "Jetzt spielen …" else if (midi in learned) "gelernt" else "noch nicht gelernt",
                                    style = MaterialTheme.typography.bodySmall, color = if (active) Amber else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(onClick = { if (active) vm.cancelLearning() else vm.learnNote(midi) }, enabled = vm.listening) {
                                Text(if (active) "Abbrechen" else "Anspielen")
                            }
                        }
                    }
                }
            }
            if (vm.calibrationInstrument == Instrument.DRUMS) items(pads.distinct()) { pad ->
                val count = vm.calibration.count(pad)
                val recording = vm.calibratingPad == pad
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(DrumNames.name(pad), fontWeight = FontWeight.Bold)
                            Text(if (recording) "Aufnahme läuft: schlagen! ($count/5)" else if (count > 0) "$count Schläge" else "noch nicht gelernt",
                                style = MaterialTheme.typography.bodySmall, color = if (recording) Amber else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (count > 0) TextButton(onClick = { vm.clearPad(pad) }) { Text("Löschen") }
                        OutlinedButton(onClick = { if (recording) vm.calibratingPad = null else vm.recordPad(pad) }, enabled = vm.listening) {
                            Text(if (recording) "Stopp" else "Aufnehmen")
                        }
                    }
                }
            }
            item {
                val rec = vm.lastRecognized
                Box(Modifier.fillMaxWidth().height(96.dp).background(if (rec != null && rec.first >= 0) Amber.copy(alpha = 0.25f) else Color(0xFF1C1C1C), MaterialTheme.shapes.large), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Live-Test", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val drums = vm.calibrationInstrument == Instrument.DRUMS
                        Text(
                            when {
                                !vm.listening -> "Mikrofon aus"
                                rec == null -> if (drums) "schlag etwas …" else "spiel etwas …"
                                rec.first < 0 -> "unbekannt"
                                drums -> "${DrumNames.name(rec.first)}  ${(rec.second * 100).toInt()} %"
                                vm.liveNotes.size > 1 -> vm.liveNotes.sortedBy { it.cls }.joinToString("  ") { DrumNames.noteName(it.cls) }
                                else -> DrumNames.noteName(rec.first) + "  " + (if (vm.lastCents > 0) "+" else "") + "${vm.lastCents.toInt()} ¢"
                            },
                            fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
