package at.zentriert.setlist.ui.notation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import at.zentriert.setlist.Instrument
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.NotationView
import at.zentriert.setlist.Score
import at.zentriert.setlist.Song
import at.zentriert.setlist.audio.ScorePlayback
import at.zentriert.setlist.notation.AlphaTex

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun NotationScreen(vm: MainViewModel, setlistId: String?, song: Song, score: Score) {
    val settings = vm.settings
    val available = score.parts.filter { it.notes.isNotEmpty() }.map { it.instrument }
    var instrument by remember { mutableStateOf(settings.myInstrument?.takeIf { it in available } ?: available.firstOrNull()) }
    var menuOpen by remember { mutableStateOf(false) }
    val view = AlphaTex.View.entries.getOrElse(settings.notationView) { AlphaTex.View.BOTH }
    val tex = remember(score, instrument, view) {
        AlphaTex.render(score, song.title, instruments = listOfNotNull(instrument), view = view)
    }
    val position = (vm.playbackTick ?: vm.startTick).let { ScorePlayback.barBeat(score, it) }

    BackHandler { vm.openNotes(setlistId, song.id) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { vm.openNotes(setlistId, song.id) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
                actions = {
                    IconButton(onClick = { vm.updateSettings { it.copy(notationLight = !it.notationLight) } }) {
                        Icon(if (settings.notationLight) Icons.Default.DarkMode else Icons.Default.LightMode, "Hell/Dunkel")
                    }
                    IconButton(onClick = { vm.updateSettings { it.copy(notationScale = (it.notationScale / 1.15f).coerceAtLeast(0.6f)) } }) { Icon(Icons.Default.ZoomOut, "Kleiner") }
                    IconButton(onClick = { vm.updateSettings { it.copy(notationScale = (it.notationScale * 1.15f).coerceAtMost(2.5f)) } }) { Icon(Icons.Default.ZoomIn, "Größer") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                // Compact instrument dropdown
                Box {
                    TextButton(onClick = { menuOpen = true }) {
                        Text(instrument?.symbol ?: "–", fontSize = 20.sp)
                        Icon(Icons.Default.ArrowDropDown, "Instrument wählen")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        available.forEach { inst ->
                            DropdownMenuItem(
                                text = { Text("${inst.symbol}  ${inst.label}") },
                                onClick = { instrument = inst; menuOpen = false },
                            )
                        }
                    }
                }
                if (instrument == Instrument.BASS || instrument == Instrument.GUITAR) {
                    AlphaTex.View.entries.forEach { v ->
                        FilterChip(selected = view == v, onClick = { vm.updateSettings { it.copy(notationView = v.ordinal) } }, label = { Text(v.label) })
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("${position.first}·${position.second}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                FilledIconButton(onClick = { if (vm.playing) vm.pausePlayback() else vm.playFromCursor(score) }, enabled = vm.soundFontReady) {
                    Icon(if (vm.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (vm.playing) "Pause" else "Abspielen")
                }
                IconButton(onClick = { vm.stopPlayback(); vm.startTick = 0 }) { Icon(Icons.Default.Stop, "Stop (zum Anfang)") }
            }
            if (instrument == null) {
                Text("Keine Noten vorhanden.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                NotationView(
                    tex,
                    vm.notationCursor(score) ?: (vm.startTick * 960 / score.ppq).takeIf { !vm.playing },
                    settings.notationScale,
                    settings.notationLight,
                    onTap = { tick -> vm.jumpTo(score, tick * score.ppq / 960) },
                    Modifier.fillMaxSize(),
                )
            }
        }
    }
}
