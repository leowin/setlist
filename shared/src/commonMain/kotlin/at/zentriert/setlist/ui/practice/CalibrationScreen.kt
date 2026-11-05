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
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.Score
import at.zentriert.setlist.edit.DrumNames
import at.zentriert.setlist.rememberMicrophonePermission
import at.zentriert.setlist.ui.Amber

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
                title = { Text("Zuhören justieren") },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Mikrofon-Verzögerung", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Zwischen Schlag und Mikrofon liegen Schallweg und Verarbeitung. „Automatisch messen“ spielt neun Klicks über den " +
                                "Lautsprecher und misst, wie spät das Mikrofon sie hört (Handy frei hinlegen, Lautstärke hoch). Der Regler korrigiert fein.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("${settings.micOffsetMs} ms" + (vm.calibration.micOffsetMeasuredMs?.let { " (gemessen $it ms)" } ?: ""), fontWeight = FontWeight.Bold)
                        Slider(value = settings.micOffsetMs.toFloat(), onValueChange = { v -> vm.updateSettings { it.copy(micOffsetMs = v.toInt()) } }, valueRange = -150f..300f)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = vm::startLoopbackMeasure, enabled = !vm.loopbackMeasuring) { Text("Automatisch messen") }
                            if (vm.loopbackMeasuring) { CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp); Text("hört die Klicks … (9 s)") }
                        }
                        vm.micError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (!vm.listening) TextButton(onClick = requestMic) { Text("Mikrofon einschalten") }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Bewertung", style = MaterialTheme.typography.titleMedium)
                        Text("Toleranzfenster ±${settings.practiceWindowMs} ms", style = MaterialTheme.typography.bodyMedium)
                        Slider(value = settings.practiceWindowMs.toFloat(), onValueChange = { v -> vm.updateSettings { it.copy(practiceWindowMs = v.toInt()) } }, valueRange = 40f..250f)
                        Text("Raster ohne Noten", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0 to "Wie Metronom", 1 to "Viertel", 2 to "Achtel", 3 to "Triolen", 4 to "16tel").forEach { (v, label) ->
                                FilterChip(selected = settings.practiceGrid == v, onClick = { vm.updateSettings { it.copy(practiceGrid = v) } }, label = { Text(label) })
                            }
                        }
                    }
                }
            }
            item {
                Text("Trommeln beibringen", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Je Trommel fünf Schläge aufnehmen; danach erkennt die App, welche Trommel du triffst, und bewertet auch das. " +
                        "Unten zeigt der Live-Test, was sie gerade hört.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(pads.distinct()) { pad ->
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
                        Text(
                            when {
                                !vm.listening -> "Mikrofon aus"
                                rec == null -> "schlag etwas …"
                                rec.first < 0 -> "unbekannt"
                                else -> "${DrumNames.name(rec.first)}  ${(rec.second * 100).toInt()} %"
                            },
                            fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
