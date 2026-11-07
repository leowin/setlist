package at.zentriert.setlist.ui.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.zentriert.setlist.AudioTrack
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.Score
import at.zentriert.setlist.Song
import at.zentriert.setlist.ui.Amber

/**
 * Cue editor: the waveform slides under a fixed cue line (start of bar 1); drag to move, zoom, nudge by
 * milliseconds, snap to the nearest attack, and listen from the cue with a click at the recording's tempo.
 * The cue may lie before the audio begins (negative offset: silence first).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun CueScreen(vm: MainViewModel, setlistId: String?, song: Song, score: Score, track: AudioTrack) {
    var offsetMs by remember { mutableIntStateOf(track.offsetMs) }
    var zoom by remember { mutableFloatStateOf(1f) }   // 1 = 4 s visible
    var text by remember { mutableStateOf(track.offsetMs.toString()) }
    val envelope = vm.trackEnvelopes[track.id]
    val back = { vm.stopCuePreview(); vm.openNotes(setlistId, song.id) }
    val save = { vm.updateTrack(score, track.copy(offsetMs = offsetMs)); back() }

    DisposableEffect(Unit) { onDispose { vm.stopCuePreview() } }
    BackHandler { back() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Startpunkt: ${track.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
                actions = { TextButton(onClick = save) { Text("Speichern") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Die gelbe Linie ist die Eins des ersten Takts. Wellenform verschieben, bis der erste Schlag auf der Linie liegt. " +
                    "Die grauen Striche zeigen die Schläge bei ♩ ${track.originalBpm.toInt()}, Taktstriche heller.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (envelope == null) {
                Box(Modifier.fillMaxWidth().height(180.dp).background(Color(0xFF1C1C1C)), contentAlignment = Alignment.Center) {
                    Text(if (track.id in vm.decodingTracks) "Aufnahme wird geladen …" else "Aufnahme nicht geladen", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val visibleMs = (4000 / zoom)
                Canvas(
                    Modifier.fillMaxWidth().height(180.dp).background(Color(0xFF1C1C1C))
                        .pointerInput(zoom) {
                            detectHorizontalDragGestures { _, dx ->
                                offsetMs -= (dx / size.width * visibleMs).toInt()
                                text = offsetMs.toString()
                            }
                        },
                ) {
                    val w = size.width; val h = size.height
                    val cueX = w * 0.3f
                    val msPerPx = visibleMs / w
                    val startMs = offsetMs - (cueX * msPerPx)
                    // Waveform: one column per pixel, peak of the milliseconds it covers.
                    for (px in 0 until w.toInt()) {
                        val a = (startMs + px * msPerPx).toInt(); val b = (startMs + (px + 1) * msPerPx).toInt().coerceAtLeast(a + 1)
                        var peak = 0f
                        for (ms in a until b) if (ms >= 0 && ms < envelope.size && envelope[ms] > peak) peak = envelope[ms]
                        if (a < 0 && b <= 0) continue   // before the recording starts
                        val hh = (peak * h * 0.48f).coerceAtLeast(0.5f)
                        drawLine(Color(0xFF80CBC4), Offset(px.toFloat(), h / 2 - hh), Offset(px.toFloat(), h / 2 + hh), 1f)
                    }
                    // Beat grid from the cue at the recording's tempo.
                    val beatMs = 60_000.0 / track.originalBpm * 4 / score.meter.denominator
                    val firstBeat = kotlin.math.floor((startMs - offsetMs) / beatMs).toInt()
                    var k = firstBeat
                    while (true) {
                        val ms = offsetMs + k * beatMs
                        val x = ((ms - startMs) / msPerPx).toFloat()
                        if (x > w) break
                        if (x >= 0 && k != 0) {
                            val bar = k % score.meter.numerator == 0
                            drawLine(if (bar) Color(0xFFBDBDBD) else Color(0xFF616161), Offset(x, if (bar) 0f else h * 0.15f), Offset(x, h), if (bar) 2f else 1f)
                        }
                        k++
                    }
                    drawLine(Amber, Offset(cueX, 0f), Offset(cueX, h), 3f)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = { zoom = (zoom / 1.6f).coerceAtLeast(0.25f) }) { Icon(Icons.Default.ZoomOut, "Weniger Zoom") }
                    IconButton(onClick = { zoom = (zoom * 1.6f).coerceAtMost(16f) }) { Icon(Icons.Default.ZoomIn, "Mehr Zoom") }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { offsetMs -= 10; text = offsetMs.toString() }) { Text("−10 ms") }
                    OutlinedButton(onClick = { offsetMs += 10; text = offsetMs.toString() }) { Text("+10 ms") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        text, { v -> text = v; v.trim().toIntOrNull()?.let { offsetMs = it } },
                        label = { Text("Takt 1 beginnt bei (ms)") }, singleLine = true, modifier = Modifier.width(190.dp),
                    )
                    OutlinedButton(onClick = { vm.snapToOnset(envelope, offsetMs)?.let { offsetMs = it; text = it.toString() } }) { Text("Auf Anschlag") }
                }
                Button(
                    onClick = { if (vm.cuePreviewing) vm.stopCuePreview() else vm.cuePreview(score, track, offsetMs) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(if (vm.cuePreviewing) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (vm.cuePreviewing) "Stopp" else "Vorhören ab Takt 1 mit Klick (♩ ${track.originalBpm.toInt()})", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                if (vm.cueStatus.isNotEmpty()) Text(vm.cueStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Negativ heißt: die Aufnahme beginnt erst nach der Eins (Pause vorne). Positiv: vor der Eins liegt Vorlauf, der übersprungen wird.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
