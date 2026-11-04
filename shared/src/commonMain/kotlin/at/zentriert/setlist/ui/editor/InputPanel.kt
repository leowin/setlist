package at.zentriert.setlist.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.zentriert.setlist.Instrument
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.Score
import at.zentriert.setlist.edit.DrumNames
import at.zentriert.setlist.ui.Amber

/** Multi-touch: every pointer maps to a key; sliding onto another key re-triggers. */
private fun Modifier.multiTouchKeys(keyAt: (Offset) -> Int?, onDown: (Int) -> Unit, onUp: (Int) -> Unit) =
    pointerInput(keyAt) {
        awaitPointerEventScope {
            val active = HashMap<PointerId, Int>()
            while (true) {
                val event = awaitPointerEvent()
                for (change in event.changes) {
                    val key = keyAt(change.position)
                    when {
                        change.changedToDown() -> if (key != null) { active[change.id] = key; onDown(key) }
                        change.changedToUp() -> active.remove(change.id)?.let(onUp)
                        change.pressed && key != null && active[change.id] != null && active[change.id] != key -> {
                            active.remove(change.id)?.let(onUp); active[change.id] = key; onDown(key)
                        }
                    }
                    change.consume()
                }
            }
        }
    }

@Composable
fun InputPanel(vm: MainViewModel, score: Score, instrument: Instrument, assignPads: Boolean, modifier: Modifier = Modifier) {
    if (instrument == Instrument.DRUMS) DrumPads(vm, score, assignPads, modifier) else Keyboard(vm, score, instrument, modifier)
}

@Composable
private fun DrumPads(vm: MainViewModel, score: Score, assignPads: Boolean, modifier: Modifier) {
    var pressed by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var assigning by remember { mutableStateOf<Int?>(null) }
    val pads = score.drumPads
    BoxWithConstraints(modifier.fillMaxWidth().padding(4.dp)) {
        val cols = 8; val rowsN = 2
        val cellW = constraints.maxWidth.toFloat() / cols; val cellH = constraints.maxHeight.toFloat() / rowsN
        fun padAt(p: Offset): Int? {
            val c = (p.x / cellW).toInt(); val r = (p.y / cellH).toInt()
            return (r * cols + c).takeIf { c in 0 until cols && r in 0 until rowsN && it < pads.size }
        }
        Column(
            Modifier.fillMaxSize().multiTouchKeys(
                keyAt = ::padAt,
                onDown = { i -> if (assignPads) assigning = i else { pressed = pressed + i; vm.noteOn(score, Instrument.DRUMS, pads[i], 110) } },
                onUp = { i -> pressed = pressed - i; if (!assignPads) vm.noteOff(score, Instrument.DRUMS, pads[i]) },
            ),
        ) {
            for (r in 0 until rowsN) Row(Modifier.weight(1f)) {
                for (c in 0 until cols) {
                    val i = r * cols + c
                    Box(
                        Modifier.weight(1f).fillMaxHeight().padding(2.dp)
                            .background(if (i in pressed) Amber else Color(0xFF2E2E2E), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(DrumNames.name(pads[i]), fontSize = 10.sp, color = if (i in pressed) Color.Black else Color.White, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        }
    }
    assigning?.let { index ->
        AlertDialog(
            onDismissRequest = { assigning = null },
            title = { Text("Pad ${index + 1} belegen") },
            text = {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(DrumNames.ALL) { (key, name) ->
                        Text(
                            "$name ($key)",
                            color = if (pads[index] == key) Amber else Color.White,
                            modifier = Modifier.fillMaxWidth().clickable {
                                vm.setDrumPad(score, index, key); vm.noteOn(score, Instrument.DRUMS, key, 110); vm.noteOff(score, Instrument.DRUMS, key); assigning = null
                            }.padding(vertical = 8.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { assigning = null }) { Text("Schließen") } },
        )
    }
}

private val WHITE_OFFSETS = listOf(0, 2, 4, 5, 7, 9, 11)
private val BLACK_AFTER_WHITE = mapOf(0 to 1, 1 to 3, 3 to 6, 4 to 8, 5 to 10)  // white index in octave -> semitone

@Composable
private fun Keyboard(vm: MainViewModel, score: Score, instrument: Instrument, modifier: Modifier) {
    var baseKey by remember(instrument) { mutableStateOf(if (instrument == Instrument.BASS) 36 else 48) }  // C2 / C3
    var pressed by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val octaves = 2
    val whites = octaves * 7 + 1

    Row(modifier.fillMaxWidth()) {
        Column(Modifier.width(44.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = { baseKey = (baseKey + 12).coerceAtMost(96) }) { Icon(Icons.Default.KeyboardArrowUp, "Oktave höher") }
            Text(DrumNames.noteName(baseKey), fontSize = 11.sp, color = Color.LightGray)
            IconButton(onClick = { baseKey = (baseKey - 12).coerceAtLeast(12) }) { Icon(Icons.Default.KeyboardArrowDown, "Oktave tiefer") }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val wPx = constraints.maxWidth.toFloat(); val hPx = constraints.maxHeight.toFloat()
            val whiteW = wPx / whites
            val blackW = whiteW * 0.6f; val blackH = hPx * 0.6f
            fun keyAt(p: Offset): Int? {
                if (p.x < 0 || p.x >= wPx || p.y < 0 || p.y >= hPx) return null
                val whiteIndex = (p.x / whiteW).toInt()
                if (p.y < blackH) {
                    // black key straddles the boundary to the right of white key `wi`
                    for (wi in listOf(whiteIndex - 1, whiteIndex)) {
                        val semis = BLACK_AFTER_WHITE[((wi % 7) + 7) % 7] ?: continue
                        if (wi < 0 || wi >= whites - 1) continue
                        val cx = (wi + 1) * whiteW
                        if (p.x in (cx - blackW / 2)..(cx + blackW / 2)) return baseKey + (wi / 7) * 12 + semis
                    }
                }
                return baseKey + (whiteIndex / 7) * 12 + WHITE_OFFSETS[whiteIndex % 7]
            }
            Canvas(
                Modifier.fillMaxSize().multiTouchKeys(
                    keyAt = ::keyAt,
                    onDown = { k -> pressed = pressed + k; vm.noteOn(score, instrument, k, 100) },
                    onUp = { k -> pressed = pressed - k; vm.noteOff(score, instrument, k) },
                ),
            ) {
                for (wi in 0 until whites) {
                    val key = baseKey + (wi / 7) * 12 + WHITE_OFFSETS[wi % 7]
                    drawRect(if (key in pressed) Amber else Color(0xFFEEEEEE), Offset(wi * whiteW + 1f, 0f), Size(whiteW - 2f, hPx))
                }
                for (wi in 0 until whites - 1) {
                    val semis = BLACK_AFTER_WHITE[wi % 7] ?: continue
                    val key = baseKey + (wi / 7) * 12 + semis
                    val cx = (wi + 1) * whiteW
                    drawRect(if (key in pressed) Amber else Color(0xFF111111), Offset(cx - blackW / 2, 0f), Size(blackW, blackH))
                }
            }
        }
    }
}
