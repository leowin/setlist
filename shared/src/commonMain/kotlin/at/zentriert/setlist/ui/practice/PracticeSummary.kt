package at.zentriert.setlist.ui.practice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.practice.PracticeSession
import at.zentriert.setlist.practice.Rating
import at.zentriert.setlist.ui.Amber

/** After a take: the score, timing numbers, a bar per bar, the deviation histogram and the song's history. */
@Composable
fun PracticeDialogs(vm: MainViewModel) {
    val session = vm.practiceResult ?: return
    val history = remember(session.id) { vm.practiceHistory(session.songId) }
    val s = session.summary
    AlertDialog(
        onDismissRequest = { vm.practiceResult = null },
        title = { Text("${s.score} · ${Rating.label(s.score)}", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    (if (session.mode == "score") "Gegen die Noten" else "Gegen das Raster") + " · ♩ ${session.bpm.toInt()}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Stat("Treffer", "${s.hit}/${s.expected}")
                    Stat("Streuung", "±${s.sdMs.toInt()} ms")
                    Stat("Tendenz", if (s.meanMs < 0) "${(-s.meanMs).toInt()} ms früh" else "${s.meanMs.toInt()} ms spät")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Stat("Verpasst", "${s.missed}")
                    Stat("Zusätzlich", "${s.extra}")
                    s.keyAccuracy?.let { Stat("Richtige Trommel", "${(it * 100).toInt()} %") }
                }
                if (session.bars.isNotEmpty()) {
                    Text("Pro Takt", style = MaterialTheme.typography.labelLarge)
                    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
                        val n = session.bars.size
                        val w = size.width / n
                        session.bars.forEachIndexed { i, b ->
                            val hgt = size.height * b.summary.score / 100f
                            val c = when { b.summary.score >= 80 -> Good; b.summary.score >= 55 -> Fair; else -> Bad }
                            drawRect(c, Offset(i * w + 1f, size.height - hgt), Size((w - 2f).coerceAtLeast(1f), hgt))
                        }
                    }
                    Text("Takte ${session.bars.first().bar}–${session.bars.last().bar}, Höhe = Wertung", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val devs = session.hits.mapNotNull { it.deviationMs }
                if (devs.size >= 3) {
                    Text("Verteilung (früh … spät)", style = MaterialTheme.typography.labelLarge)
                    Canvas(Modifier.fillMaxWidth().height(50.dp)) {
                        val bins = 15
                        val counts = IntArray(bins)
                        devs.forEach { d -> counts[((d / session.windowMs + 1) / 2 * (bins - 1)).toInt().coerceIn(0, bins - 1)]++ }
                        val max = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
                        val w = size.width / bins
                        counts.forEachIndexed { i, c ->
                            val hgt = size.height * c / max
                            drawRect(if (i == bins / 2) Amber else Color(0xFF9E9E9E), Offset(i * w + 1f, size.height - hgt), Size(w - 2f, hgt))
                        }
                    }
                }
                if (history.size >= 2) {
                    Text("Verlauf (${history.size} Durchgänge)", style = MaterialTheme.typography.labelLarge)
                    Canvas(Modifier.fillMaxWidth().height(60.dp)) {
                        val pts = history.takeLast(30)
                        val step = if (pts.size > 1) size.width / (pts.size - 1) else 0f
                        var prev: Offset? = null
                        pts.forEachIndexed { i, p ->
                            val o = Offset(i * step, size.height - size.height * p.summary.score / 100f)
                            prev?.let { drawLine(Amber, it, o, 3f) }
                            drawCircle(if (p.id == session.id) Color.White else Amber, 4f, o)
                            prev = o
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.practiceResult = null }) { Text("OK") } },
    )
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
