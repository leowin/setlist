package at.zentriert.setlist.ui.metronome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import at.zentriert.setlist.audio.MetronomePosition
import at.zentriert.setlist.ui.Amber

private val DimColor = Color(0xFF9E9E9E)

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

/** Label above a button that opens a list of choices. */
@Composable
fun <T> Dropdown(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, fontSize = 12.sp, color = DimColor)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(options.firstOrNull { it.first == selected }?.second ?: "–", modifier = Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); open = false })
                }
            }
        }
    }
}
