package at.zentriert.setlist.ui.notation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.zentriert.setlist.Instrument
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.NotationView
import at.zentriert.setlist.Score
import at.zentriert.setlist.Song
import at.zentriert.setlist.notation.AlphaTex
import at.zentriert.setlist.ui.Amber
import at.zentriert.setlist.ui.InstrumentDropdown
import at.zentriert.setlist.ui.transport.DimColor

/**
 * The sheet-music frame shared by the concert and the notation screen: instrument dropdown, Noten/Tab/Beides,
 * size and light/dark, and the alphaTab view with the playback cursor (tap = move cursor / jump).
 * [showTools] hides the size and light buttons where the screen already has them (concert top strip).
 */
@Composable
fun NotationPane(
    vm: MainViewModel, song: Song, score: Score, modifier: Modifier = Modifier,
    showTools: Boolean = true, title: String? = null, titleSize: Float = 24f,
) {
    val settings = vm.settings
    val available = score.parts.filter { it.notes.isNotEmpty() }.map { it.instrument }
    val instrument = vm.notationInstrument(score)
    val view = AlphaTex.View.entries.getOrElse(settings.notationView) { AlphaTex.View.BOTH }
    val tex = remember(score, instrument, view, song.title) { AlphaTex.render(score, song.title, listOfNotNull(instrument), view) }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (title != null) Text(
                title, color = Color.White, fontSize = titleSize.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            InstrumentDropdown(
                selected = instrument, options = available, onSelect = { it?.let(vm::selectNotationInstrument) },
                labelFor = { it.symbol }, placeholder = "–",
            )
            if (instrument == Instrument.BASS || instrument == Instrument.GUITAR) {
                TextButton(onClick = { vm.updateSettings { it.copy(notationView = (it.notationView + 1) % AlphaTex.View.entries.size) } }) {
                    Text(view.label, color = Amber, fontSize = 13.sp)
                }
            }
            if (title == null) Spacer(Modifier.weight(1f))
            if (showTools) {
                IconButton(onClick = { vm.updateSettings { it.copy(notationScale = (it.notationScale / 1.15f).coerceAtLeast(0.6f)) } }) { Icon(Icons.Default.ZoomOut, "Kleiner", tint = DimColor) }
                IconButton(onClick = { vm.updateSettings { it.copy(notationScale = (it.notationScale * 1.15f).coerceAtMost(2.5f)) } }) { Icon(Icons.Default.ZoomIn, "Größer", tint = DimColor) }
                IconButton(onClick = { vm.updateSettings { it.copy(notationLight = !it.notationLight) } }) {
                    Icon(if (settings.notationLight) Icons.Default.DarkMode else Icons.Default.LightMode, "Hell/Dunkel", tint = DimColor)
                }
            }
        }
        if (instrument == null) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) { Text("Keine Noten vorhanden.", color = DimColor) }
        } else {
            NotationView(
                tex,
                vm.notationCursor(score) ?: (vm.startTick * 960 / score.ppq).takeIf { !vm.playing },
                settings.notationScale, settings.notationLight,
                onTap = { tick -> vm.jumpTo(score, tick * score.ppq / 960) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}
