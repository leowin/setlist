package at.zentriert.setlist.ui.metronome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
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
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.Setlist
import at.zentriert.setlist.Song
import at.zentriert.setlist.audio.MetronomePosition
import at.zentriert.setlist.ui.Amber

private val DimColor = Color(0xFF9E9E9E)
private val ButtonGray = Color(0xFF2A2A2A)

/**
 * Visible from across the stage: one column per beat behind the current song, the running beat
 * lit up (beat 1 brighter), grey while counting in. Uses no extra screen space.
 */
@Composable
fun BeatBackground(position: MetronomePosition?, numerator: Int) {
    Row(Modifier.fillMaxSize()) {
        repeat(numerator) { beat ->
            val active = position != null && position.beat == beat
            val color = when {
                !active -> Color.Transparent
                position.countingIn -> Color.White.copy(alpha = if (beat == 0) 0.22f else 0.12f)
                else -> Amber.copy(alpha = if (beat == 0) 0.42f else 0.24f)
            }
            Box(Modifier.weight(1f).fillMaxHeight().background(color), contentAlignment = Alignment.BottomCenter) {
                if (position != null) {
                    Text(
                        "${beat + 1}",
                        color = Color.White.copy(alpha = if (active) 0.35f else 0.08f),
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        }
    }
}

/** Start/stop, tempo display with ±, adopt button and metronome settings; sits above Zurück/Weiter. */
@Composable
fun MetronomeBar(vm: MainViewModel, setlist: Setlist, song: Song?) {
    val tempo = vm.tempoFor(song)
    var showSettings by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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
        IconButton(onClick = { vm.nudgeTempo(song, -1.0) }) { Icon(Icons.Default.Remove, "Langsamer", tint = DimColor) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("♩ ${tempo.label}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (!vm.hasOwnTempo(song)) {
                Text(
                    if (vm.performBpm != null) "geändert" else "Standard",
                    color = DimColor,
                    fontSize = 11.sp,
                )
            }
        }
        IconButton(onClick = { vm.nudgeTempo(song, 1.0) }) { Icon(Icons.Default.Add, "Schneller", tint = DimColor) }
        Spacer(Modifier.weight(1f))
        if (vm.performBpm != null && song != null) {
            TextButton(onClick = { vm.adoptTempo(setlist.id, song) }) { Text("Übernehmen", color = Amber) }
        }
        IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Tune, "Metronom-Einstellungen", tint = DimColor) }
    }

    if (showSettings) MetronomeSettingsDialog(vm, song, onDismiss = { showSettings = false })
}

@Composable
private fun MetronomeSettingsDialog(vm: MainViewModel, song: Song?, onDismiss: () -> Unit) {
    val settings = vm.settings
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Metronom") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Lautstärke")
                Slider(
                    value = settings.clickVolume,
                    onValueChange = { v -> vm.updateSettings { it.copy(clickVolume = v) } },
                    valueRange = 0f..1f,
                )
                Text("Unterteilung")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1 to "Aus", 2 to "Achtel", 3 to "Triolen", 4 to "16tel").forEach { (value, label) ->
                        FilterChip(
                            selected = settings.subdivision == value,
                            onClick = { vm.updateMetronomeSettings(song) { it.copy(subdivision = value) } },
                            label = { Text(label) },
                        )
                    }
                }
                Text("Vorzähler")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Keiner", 1 to "1 Takt", 2 to "2 Takte").forEach { (value, label) ->
                        FilterChip(
                            selected = settings.countInBars == value,
                            onClick = { vm.updateMetronomeSettings(song) { it.copy(countInBars = value) } },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
    )
}
