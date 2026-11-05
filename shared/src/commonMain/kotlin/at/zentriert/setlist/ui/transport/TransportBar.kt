package at.zentriert.setlist.ui.transport

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.zentriert.setlist.Instrument
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.Score
import at.zentriert.setlist.Song
import at.zentriert.setlist.TempoEdit
import at.zentriert.setlist.audio.ScorePlayback
import at.zentriert.setlist.band.Role
import at.zentriert.setlist.ui.Amber
import at.zentriert.setlist.ui.TempoEditor
import at.zentriert.setlist.ui.metronome.Dropdown
import at.zentriert.setlist.ui.practice.TimingMeter
import at.zentriert.setlist.rememberMicrophonePermission

val DimColor = Color(0xFF9E9E9E)
private val ButtonGray = Color(0xFF2A2A2A)
private val BarBackground = Color(0xFF151515)

/** What a screen adds to the common transport bar. */
data class TransportOptions(
    /** Setlist the song is played from (tempo changes are saved into its copy); null in the library. */
    val setlistId: String? = null,
    /** Editor: record button for this part and the loop chip. */
    val recordInstrument: Instrument? = null,
    val onLoop: (() -> Unit)? = null,
    val showStepInput: Boolean = false,
    /** Where play starts when the screen has its own idea (editor: loop or cursor bar); null = the cursor. */
    val startTick: (() -> Long)? = null,
    /** Notes screen: toggle for playing the notes with or without the click. */
    val metronomeToggle: Boolean = false,
    /** Keep room for the system navigation bar (screens without their own padding). */
    val navigationPadding: Boolean = false,
)

/**
 * The one transport bar: ⚙ · ⏺ · Play/Pause · ■ · position · tempo ± · subdivision, the same on the concert,
 * notation, notes and editor screens. Play starts from the cursor, Pause keeps the position, ■ returns to the
 * start. Without notes the Play button runs the metronome.
 */
@Composable
fun TransportBar(vm: MainViewModel, song: Song?, score: Score?, options: TransportOptions = TransportOptions()) {
    val tempo = vm.tempoFor(song)
    val hasNotes = score?.parts?.any { it.notes.isNotEmpty() } == true
    var showSettings by remember { mutableStateOf(false) }
    var editTempo by remember { mutableStateOf(false) }
    var withMetronome by remember { mutableStateOf(true) }
    val requestMic = rememberMicrophonePermission { granted -> if (granted) vm.startListening() else vm.micError = "Ohne Mikrofon-Berechtigung kann die App nicht zuhören." }

    Column(Modifier.fillMaxWidth().background(BarBackground).let { if (options.navigationPadding) it.navigationBarsPadding() else it }) {
    if (vm.listening) TimingMeter(vm)
    Row(
        Modifier.fillMaxWidth().height(56.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Tune, "Metronom-Einstellungen (Unterteilung, Vorzähler, Lautstärke)", tint = DimColor) }
        // Listening: the microphone rates what is played along.
        IconButton(onClick = { if (vm.listening) vm.stopListening() else requestMic() }) {
            Icon(Icons.Default.Hearing, if (vm.listening) "Zuhören aus" else "Zuhören: Spiel bewerten", tint = if (vm.listening) Amber else DimColor)
        }

        if (options.recordInstrument != null && score != null) {
            Button(
                onClick = {
                    if (vm.recording) vm.stopRecording(score)
                    else vm.startRecording(score, options.recordInstrument, options.startTick?.invoke() ?: vm.startTick)
                },
                enabled = vm.soundFontReady,
                colors = ButtonDefaults.buttonColors(containerColor = if (vm.recording) Color(0xFFD32F2F) else ButtonGray, contentColor = Color.White),
                modifier = Modifier.fillMaxHeight().padding(vertical = 6.dp),
            ) {
                Icon(if (vm.recording) Icons.Default.Stop else Icons.Default.FiberManualRecord, null, tint = if (vm.recording) Color.White else Color(0xFFFF5252))
                Spacer(Modifier.width(4.dp)); Text(if (vm.recording) "Fertig" else "Aufnahme", fontSize = 16.sp)
            }
        }

        if (hasNotes && score != null) {
            Button(
                onClick = {
                    when {
                        vm.playing -> vm.pausePlayback()
                        options.startTick != null -> vm.playScore(score, options.startTick.invoke(), withMetronome = true, loop = vm.loop)
                        else -> vm.playFromCursor(score, withMetronome = withMetronome || !options.metronomeToggle)
                    }
                },
                enabled = vm.soundFontReady && !vm.recording,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (vm.playing) Amber else ButtonGray,
                    contentColor = if (vm.playing) Color.Black else Color.White,
                ),
                modifier = Modifier.fillMaxHeight().padding(vertical = 6.dp),
            ) {
                Icon(if (vm.playing) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(4.dp))
                Text(if (vm.playing) "Pause" else "Play", fontSize = 16.sp)
            }
            IconButton(
                onClick = { if (vm.recording) vm.stopRecording(score); vm.stopPlayback(); vm.stopMetronome(); vm.startTick = 0; vm.cursorTick = 0 },
                enabled = vm.playing || vm.startTick > 0 || vm.metronomeOn || vm.cursorTick > 0,
            ) { Icon(Icons.Default.Stop, "Stop, zum Anfang", tint = DimColor) }
            Text(
                positionLabel(vm, score, options.startTick?.invoke() ?: vm.startTick),
                color = if (vm.playing) Amber else Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        } else {
            Button(
                onClick = { vm.toggleMetronome(song) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (vm.metronomeOn) Amber else ButtonGray,
                    contentColor = if (vm.metronomeOn) Color.Black else Color.White,
                ),
                modifier = Modifier.fillMaxHeight().padding(vertical = 6.dp),
            ) {
                Icon(if (vm.metronomeOn) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(4.dp))
                Text("Metronom", fontSize = 16.sp)
            }
        }

        IconButton(onClick = { vm.nudgeTempo(song, -1.0) }) { Icon(Icons.Default.Remove, "Langsamer", tint = DimColor) }
        // Tap the tempo to edit BPM and meter for this song.
        Column(
            Modifier.clickable(enabled = song != null) { editTempo = true }.padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("♩ ${tempo.label}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (!vm.hasOwnTempo(song)) {
                Text(if (vm.performBpm != null) "geändert" else "Standard", color = DimColor, fontSize = 11.sp)
            }
        }
        IconButton(onClick = { vm.nudgeTempo(song, 1.0) }) { Icon(Icons.Default.Add, "Schneller", tint = DimColor) }
        if (vm.performBpm != null && song != null) {
            TextButton(onClick = { vm.adoptTempo(options.setlistId, song) }) { Text("Übernehmen", color = Amber) }
        }
        if (vm.bandRole != null) {
            Text(if (vm.bandRole == Role.HOST) "HOST" else "BAND", color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            when (vm.settings.subdivision) { 2 -> "♪ Achtel"; 3 -> "♪ Triolen"; 4 -> "♪ 16tel"; else -> "♩ Viertel" },
            color = DimColor, fontSize = 11.sp,
            modifier = Modifier.clickable { showSettings = true }.padding(horizontal = 6.dp),
        )
        if (options.metronomeToggle && hasNotes) {
            FilterChip(selected = withMetronome, onClick = { withMetronome = !withMetronome }, label = { Text("Klick") }, colors = chipColors())
        }
        if (options.onLoop != null && score != null) {
            FilterChip(
                selected = vm.loop != null, onClick = options.onLoop,
                label = { Text(vm.loop?.let { "Loop ${it.from}–${it.to}" } ?: "Loop") }, colors = chipColors(),
            )
        }
        if (options.showStepInput) {
            FilterChip(selected = vm.stepInput, onClick = { vm.stepInput = !vm.stepInput }, label = { Text("Schritt") }, colors = chipColors())
        }
    }
    }

    if (showSettings) MetronomeSettingsDialog(vm, song, onDismiss = { showSettings = false })
    if (editTempo && song != null) {
        TempoDialog(
            initial = tempo,
            onSave = { vm.setTempo(options.setlistId, song, it); editTempo = false },
            onDismiss = { editTempo = false },
        )
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(selectedContainerColor = Amber, selectedLabelColor = Color.Black, labelColor = Color.White)

/** "Takt 3 · 2" while playing or where play would start; "Vorzähler …" during the count-in. */
fun positionLabel(vm: MainViewModel, score: Score, startTick: Long): String {
    val tick = if (vm.playing) vm.playbackTick ?: return "Vorzähler …" else startTick
    val (bar, beat) = ScorePlayback.barBeat(score, tick)
    return "Takt $bar · $beat"
}

@Composable
private fun TempoDialog(initial: TempoEdit, onSave: (TempoEdit) -> Unit, onDismiss: () -> Unit) {
    var tempo by remember { mutableStateOf<TempoEdit?>(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tempo & Taktart") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { TempoEditor(tempo, onChange = { tempo = it ?: initial }) } },
        confirmButton = { TextButton(onClick = { tempo?.let(onSave) }, enabled = tempo != null) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Horizontal level bar: green up to -12 dB, amber to -3 dB, red above. */
@Composable
private fun LevelMeter(levelDb: Float, active: Boolean) {
    val fraction = if (!active) 0f else ((levelDb + 60f) / 60f).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(14.dp).background(Color(0xFF2A2A2A), MaterialTheme.shapes.small)) {
        Box(
            Modifier.fillMaxWidth(fraction).fillMaxHeight()
                .background(if (levelDb > -3f) Color(0xFFEF5350) else if (levelDb > -12f) Amber else Color(0xFF66BB6A), MaterialTheme.shapes.small),
        )
    }
}

/** ⚙ of the transport bar: one dialog, two pages chosen by the icon row on top – metronome and listening. */
@Composable
private fun MetronomeSettingsDialog(vm: MainViewModel, song: Song?, onDismiss: () -> Unit) {
    val settings = vm.settings
    var tab by remember { mutableStateOf(0) }
    val requestMicFromDialog = rememberMicrophonePermission { granted -> if (granted) vm.startListening() else vm.micError = "Ohne Mikrofon-Berechtigung kann die App nicht zuhören." }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Metronom") }, leadingIcon = { Icon(Icons.Default.Timer, null) }, colors = chipColors())
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Zuhören") }, leadingIcon = { Icon(Icons.Default.Hearing, null) }, colors = chipColors())
            }
        },
        text = {
            if (tab == 0) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Lautstärke")
                Slider(
                    value = settings.clickVolume,
                    onValueChange = { v -> vm.updateSettings { it.copy(clickVolume = v) } },
                    valueRange = 0f..1f,
                )
                Dropdown(
                    label = "Unterteilung",
                    options = listOf(1 to "Nur Schläge", 2 to "Achtel", 3 to "Triolen", 4 to "Sechzehntel"),
                    selected = settings.subdivision,
                    onSelect = { v -> vm.updateMetronomeSettings(song) { it.copy(subdivision = v) } },
                )
                Dropdown(
                    label = "Vorzähler",
                    options = listOf(0 to "Keiner", 1 to "1 Takt", 2 to "2 Takte"),
                    selected = settings.countInBars,
                    onSelect = { v -> vm.updateMetronomeSettings(song) { it.copy(countInBars = v) } },
                )
            } else Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Level meter: is the microphone hearing anything, and how loud?
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pegel", fontWeight = FontWeight.Bold)
                    if (!vm.listening) TextButton(onClick = { requestMicFromDialog() }) { Text("Mikrofon ein") }
                }
                LevelMeter(vm.micLevelDb, vm.listening)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Automatisch", Modifier.weight(1f))
                    Switch(checked = settings.micAutoGain, onCheckedChange = { v -> vm.updateSettings { it.copy(micAutoGain = v) } })
                }
                if (settings.micAutoGain) Text("Verstärkung ×${(vm.micGainApplied * 10).toInt() / 10f}", fontSize = 12.sp, color = DimColor)
                else {
                    Text("Verstärkung ${settings.micGainDb} dB", fontSize = 12.sp, color = DimColor)
                    Slider(value = settings.micGainDb.toFloat(), onValueChange = { v -> vm.updateSettings { it.copy(micGainDb = v.toInt()) } }, valueRange = -24f..36f)
                }
                Text(
                    "Android lässt die Mikrofon-Empfindlichkeit nicht ändern; Verstärkung/Dämpfung gelten für die Anzeige. Die Erkennung passt sich dem Pegel selbst an – " +
                        "wichtig ist nur, dass der Balken bei Schlägen ausschlägt und nicht dauernd im Rot steht (Übersteuerung).",
                    fontSize = 12.sp, color = DimColor,
                )
                Text("Empfindlichkeit der Schlagerkennung: ${settings.listenSensitivity}", fontWeight = FontWeight.Bold)
                Slider(value = settings.listenSensitivity.toFloat(), onValueChange = { v -> vm.updateSettings { it.copy(listenSensitivity = v.toInt()) } }, valueRange = 0f..100f)
                Text("Höher = auch leise Schläge zählen (mehr Fehlalarme), niedriger = nur deutliche Anschläge. Gehört seit Mikrofon ein: ${vm.heardCount}" +
                    (vm.lastHeardLevelDb?.let { ", letzter ${it.toInt()} dB" } ?: ""), fontSize = 12.sp, color = DimColor)
                Text("Mikrofon-Verzögerung: ${settings.micOffsetMs} ms" + (vm.calibration.micOffsetMeasuredMs?.let { " (gemessen $it)" } ?: ""), fontWeight = FontWeight.Bold)
                Slider(value = settings.micOffsetMs.toFloat(), onValueChange = { v -> vm.updateSettings { it.copy(micOffsetMs = v.toInt()) } }, valueRange = -150f..300f)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::startLoopbackMeasure, enabled = !vm.loopbackMeasuring) { Text("Automatisch messen") }
                    if (vm.loopbackMeasuring) { CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp); Text("hört die Klicks … (9 s)", fontSize = 12.sp) }
                }
                Text(
                    "Spielt neun Metronom-Klicks über den Lautsprecher des Handys und misst, wie spät das Mikrofon sie hört. Dafür Kopfhörer abstecken, " +
                        "Medien-Lautstärke hoch und das Handy frei hinlegen.",
                    fontSize = 12.sp, color = DimColor,
                )
                vm.micError?.let { Text(it, color = Color(0xFFEF5350), fontSize = 12.sp) }
                Text("Toleranzfenster ±${settings.practiceWindowMs} ms")
                Slider(value = settings.practiceWindowMs.toFloat(), onValueChange = { v -> vm.updateSettings { it.copy(practiceWindowMs = v.toInt()) } }, valueRange = 40f..250f)
                Dropdown(
                    label = "Raster ohne Noten",
                    options = listOf(0 to "Wie Metronom", 1 to "Viertel", 2 to "Achtel", 3 to "Triolen", 4 to "16tel"),
                    selected = settings.practiceGrid,
                    onSelect = { v -> vm.updateSettings { it.copy(practiceGrid = v) } },
                )
                TextButton(onClick = { onDismiss(); vm.openCalibration(song?.id) }) { Text("Instrument justieren (Trommeln / Töne beibringen) …") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
    )
}

/** Editor: play/record start at the loop or the bar the cursor is in, unless a pause left a position. */
fun editorStartTick(vm: MainViewModel, score: Score): Long {
    val barTicks = score.ppq * 4L / score.meter.denominator * score.meter.numerator
    return vm.loop?.ticks(score)?.first ?: if (vm.startTick > 0) vm.startTick else vm.cursorTick / barTicks * barTicks
}
