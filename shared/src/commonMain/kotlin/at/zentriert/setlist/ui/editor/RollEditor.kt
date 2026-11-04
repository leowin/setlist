package at.zentriert.setlist.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.zentriert.setlist.Instrument
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.Note
import at.zentriert.setlist.Part
import at.zentriert.setlist.Score
import at.zentriert.setlist.edit.DrumNames
import at.zentriert.setlist.edit.PartEdit
import at.zentriert.setlist.edit.Quantize
import at.zentriert.setlist.ui.Amber
import kotlin.math.roundToLong

enum class EditMode(val label: String) { SELECT("Auswahl"), DRAW("Zeichnen"), ERASE("Löschen") }

/** Maps ticks and keys to the canvas; shared by drawing and hit tests. */
private class RollGeometry(
    val score: Score, val part: Part, val instrument: Instrument,
    val beatPx: Float, val rowPx: Float,
) {
    val ticksPerBeat = score.ppq * 4f / score.meter.denominator
    val beatsPerBar = score.meter.numerator
    val pads: List<Int> = score.drumPads
    val isDrums = instrument == Instrument.DRUMS

    // Melodic: rows are keys from high to low; drums: one row per pad plus one for anything else.
    val highKey: Int
    val lowKey: Int
    init {
        if (isDrums) { highKey = 0; lowKey = 0 } else {
            val (defLow, defHigh) = when (instrument) {
                Instrument.BASS -> 28 to 55
                Instrument.GUITAR -> 40 to 76
                else -> 48 to 84
            }
            val keys = part.notes.map { it.key }
            lowKey = minOf(defLow, (keys.minOrNull() ?: defLow) - 3).coerceAtLeast(0)
            highKey = maxOf(defHigh, (keys.maxOrNull() ?: defHigh) + 3).coerceAtMost(127)
        }
    }
    val rows: Int get() = if (isDrums) pads.size + 1 else highKey - lowKey + 1

    fun xOf(tick: Long) = tick / ticksPerBeat * beatPx
    fun tickOf(x: Float) = (x / beatPx * ticksPerBeat).roundToLong().coerceAtLeast(0)
    fun rowOf(key: Int) = if (isDrums) pads.indexOf(key).let { if (it < 0) pads.size else it } else highKey - key
    fun keyOf(row: Int): Int? = if (isDrums) pads.getOrNull(row) else (highKey - row).takeIf { it in lowKey..highKey }
    fun rowLabel(row: Int) = if (isDrums) pads.getOrNull(row)?.let(DrumNames::name) ?: "Andere" else keyOf(row)?.let(DrumNames::noteName) ?: ""

    fun noteAt(x: Float, y: Float): Note? {
        val row = (y / rowPx).toInt()
        val tick = tickOf(x)
        return part.notes.lastOrNull { rowOf(it.key) == row && tick >= it.tick && tick < it.tick + maxOf(it.duration, (ticksPerBeat / 4).toLong()) }
    }
}

@Composable
fun RollEditor(vm: MainViewModel, score: Score, instrument: Instrument, mode: EditMode, beatDp: Float, modifier: Modifier = Modifier) {
    val part = score.parts.first { it.instrument == instrument }
    val density = LocalDensity.current
    val rowDp = if (instrument == Instrument.DRUMS) 40f else 26f
    val geo = remember(score, instrument, beatDp) {
        RollGeometry(score, part, instrument, with(density) { beatDp.dp.toPx() }, with(density) { rowDp.dp.toPx() })
    }
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val lastTick = part.notes.maxOfOrNull { it.tick + it.duration } ?: 0L
    val barTicks = (geo.ticksPerBeat * geo.beatsPerBar).toLong()
    val loopEnd = vm.loop?.ticks(score)?.second ?: 0L
    val totalBars = (maxOf(lastTick, loopEnd, vm.cursorTick) / barTicks + 4).coerceAtLeast(8)
    val widthDp = totalBars * geo.beatsPerBar * beatDp
    val grid = vm.noteValue().ticks(score.ppq)
    val textMeasurer = rememberTextMeasurer()

    // Long-press drag: preview offsets, applied on release.
    var dragTicks by remember { mutableStateOf(0L) }
    var dragRows by remember { mutableStateOf(0) }
    var resizing by remember { mutableStateOf(false) }
    var accumulatedY by remember { mutableStateOf(0f) }

    Column(modifier) {
        // Ruler: bar numbers, loop band, tap sets the cursor
        Row {
            Box(Modifier.width(56.dp))
            Box(Modifier.horizontalScroll(hScroll, enabled = false)) {
                Canvas(
                    Modifier.width(widthDp.dp).height(22.dp).pointerInput(score) {
                        detectTapGestures { pos ->
                            val tick = geo.tickOf(pos.x)
                            vm.cursorTick = tick / barTicks * barTicks
                        }
                    },
                ) {
                    vm.loop?.let { loop ->
                        val (a, b) = loop.ticks(score)
                        drawRect(Amber.copy(alpha = 0.25f), Offset(geo.xOf(a), 0f), Size(geo.xOf(b) - geo.xOf(a), size.height))
                    }
                    for (bar in 0 until totalBars.toInt()) {
                        val x = geo.xOf(bar * barTicks)
                        drawLine(Color.Gray, Offset(x, 0f), Offset(x, size.height))
                        drawText(textMeasurer, "${bar + 1}", Offset(x + 4f, 2f), style = androidx.compose.ui.text.TextStyle(color = Color.LightGray, fontSize = 11.sp))
                    }
                    val cx = geo.xOf(vm.cursorTick)
                    drawLine(Amber, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 3f)
                }
            }
        }
        Row(Modifier.verticalScroll(vScroll)) {
            Column(Modifier.width(56.dp)) {
                for (row in 0 until geo.rows) {
                    Box(Modifier.height(rowDp.dp).fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        val label = geo.rowLabel(row)
                        if (geo.isDrums || label.startsWith("C") && !label.startsWith("C#")) {
                            Text(label, fontSize = if (geo.isDrums) 10.sp else 11.sp, color = Color.LightGray, maxLines = 1,
                                modifier = Modifier.width(52.dp))
                        }
                    }
                }
            }
            Box(Modifier.horizontalScroll(hScroll)) {
                Canvas(
                    Modifier.width(widthDp.dp).height((geo.rows * rowDp).dp)
                        .pointerInput(score, mode, grid) {
                            detectTapGestures { pos ->
                                val hit = geo.noteAt(pos.x, pos.y)
                                when (mode) {
                                    EditMode.DRAW -> if (hit != null) vm.editSelection = setOf(hit) else {
                                        val key = geo.keyOf((pos.y / geo.rowPx).toInt()) ?: return@detectTapGestures
                                        val tick = Quantize.snap(geo.tickOf(pos.x), grid).let { if (it > geo.tickOf(pos.x)) it - grid else it }.coerceAtLeast(0)
                                        val length = if (geo.isDrums) (geo.ticksPerBeat / 4).toLong() else grid
                                        vm.editPart(score, instrument) { PartEdit.add(it, Note(tick, length, key, 100)) }
                                        vm.noteOn(score, instrument, key); vm.noteOff(score, instrument, key)
                                    }
                                    EditMode.SELECT -> vm.editSelection = when {
                                        hit == null -> emptySet()
                                        hit in vm.editSelection -> vm.editSelection - hit
                                        else -> vm.editSelection + hit
                                    }
                                    EditMode.ERASE -> if (hit != null) vm.editPart(score, instrument) { PartEdit.remove(it, setOf(hit)) }
                                }
                            }
                        }
                        .pointerInput(score, grid) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { pos ->
                                    val hit = geo.noteAt(pos.x, pos.y)
                                    if (hit != null) {
                                        if (hit !in vm.editSelection) vm.editSelection = setOf(hit)
                                        resizing = pos.x > geo.xOf(hit.tick + hit.duration) - 14.dp.toPx()
                                    } else vm.editSelection = emptySet()
                                    dragTicks = 0; dragRows = 0
                                },
                                onDrag = { change, delta ->
                                    change.consume()
                                    dragTicks += geo.tickOf(delta.x + geo.beatPx * 100) - geo.tickOf(geo.beatPx * 100)
                                    accumulatedY += delta.y
                                    dragRows = (accumulatedY / geo.rowPx).toInt()
                                },
                                onDragEnd = {
                                    if (vm.editSelection.isNotEmpty()) {
                                        val snapped = Quantize.snap(dragTicks, grid)
                                        if (resizing) vm.resizeSelection(score, instrument, snapped)
                                        else vm.moveSelection(score, instrument, snapped, if (geo.isDrums) 0 else -dragRows)
                                    }
                                    dragTicks = 0; dragRows = 0; accumulatedY = 0f
                                },
                                onDragCancel = { dragTicks = 0; dragRows = 0; accumulatedY = 0f },
                            )
                        },
                ) {
                    val h = size.height
                    // Row shading (black keys) and lines
                    for (row in 0 until geo.rows) {
                        val key = geo.keyOf(row)
                        val y = row * geo.rowPx
                        val dark = if (geo.isDrums) row % 2 == 1 else key != null && key % 12 in setOf(1, 3, 6, 8, 10)
                        if (dark) drawRect(Color.White.copy(alpha = 0.04f), Offset(0f, y), Size(size.width, geo.rowPx))
                        drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, y), Offset(size.width, y))
                    }
                    // Grid: sub-grid faint, beats medium, bars strong
                    var t = 0L
                    while (t <= totalBars * barTicks) {
                        val x = geo.xOf(t)
                        val alpha = when { t % barTicks == 0L -> 0.5f; t % geo.ticksPerBeat.toLong() == 0L -> 0.25f; else -> 0.1f }
                        drawLine(Color.White.copy(alpha = alpha), Offset(x, 0f), Offset(x, h), strokeWidth = if (t % barTicks == 0L) 2f else 1f)
                        t += grid
                    }
                    // Notes
                    for (n in part.notes) {
                        val selected = n in vm.editSelection
                        val tick = if (selected && !resizing) n.tick + dragTicks else n.tick
                        val dur = if (selected && resizing) (n.duration + dragTicks).coerceAtLeast(grid / 2) else n.duration
                        val row = if (selected && !geo.isDrums) geo.rowOf(n.key) + dragRows else geo.rowOf(n.key)
                        val x = geo.xOf(tick); val w = maxOf(geo.xOf(tick + dur) - x, 6f)
                        val y = row * geo.rowPx + 2f
                        val color = if (geo.isDrums) Color(0xFF4FC3F7) else Color(0xFF81C784)
                        drawRoundRect(color.copy(alpha = 0.35f + 0.6f * n.velocity / 127f), Offset(x, y), Size(w, geo.rowPx - 4f), androidx.compose.ui.geometry.CornerRadius(4f, 4f))
                        if (selected) drawRoundRect(Amber, Offset(x, y), Size(w, geo.rowPx - 4f), androidx.compose.ui.geometry.CornerRadius(4f, 4f), style = Stroke(3f))
                    }
                    // Cursors
                    val cx = geo.xOf(vm.cursorTick)
                    drawLine(Amber.copy(alpha = 0.8f), Offset(cx, 0f), Offset(cx, h), strokeWidth = 2f)
                    vm.playbackTick?.let { pt ->
                        val px = geo.xOf(pt)
                        drawLine(Color(0xFFFF5252), Offset(px, 0f), Offset(px, h), strokeWidth = 3f)
                    }
                }
            }
        }
    }
}
