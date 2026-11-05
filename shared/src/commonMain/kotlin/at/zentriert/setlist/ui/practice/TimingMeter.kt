package at.zentriert.setlist.ui.practice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.edit.DrumNames
import at.zentriert.setlist.practice.MatchedHit
import at.zentriert.setlist.ui.Amber

val Good = Color(0xFF66BB6A)
val Fair = Color(0xFFFFC107)
val Bad = Color(0xFFEF5350)

fun deviationColor(ms: Double?, windowMs: Double): Color = when {
    ms == null -> Bad
    kotlin.math.abs(ms) <= windowMs * 0.2 -> Good
    kotlin.math.abs(ms) <= windowMs * 0.5 -> Fair
    else -> Bad
}

/** Live strip while listening: scale ±window with the last hits as dots (newest brightest), tendency, drum. */
@Composable
fun TimingMeter(vm: MainViewModel, modifier: Modifier = Modifier) {
    val window = vm.settings.practiceWindowMs.toDouble()
    val hits = vm.liveHits
    val last = hits.lastOrNull()
    val summary = vm.liveSummary
    Row(modifier.fillMaxWidth().height(30.dp).background(Color(0xFF0E0E0E)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("👂", fontSize = 14.sp)
        Text(
            when {
                last == null -> if (vm.playing || vm.metronomeOn) "hört zu …" else "bereit"
                last.expected == null -> "extra"
                else -> last.deviationMs!!.let { d -> if (kotlin.math.abs(d) < 5) "genau" else if (d < 0) "${(-d).toInt()} ms früh" else "${d.toInt()} ms spät" }
            },
            color = if (last == null) Color(0xFF9E9E9E) else deviationColor(last.deviationMs, window),
            fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(96.dp).padding(start = 6.dp),
        )
        Canvas(Modifier.weight(1f).height(20.dp)) {
            val w = size.width; val h = size.height
            drawLine(Color(0xFF3A3A3A), Offset(0f, h / 2), Offset(w, h / 2), 2f)
            drawLine(Amber, Offset(w / 2, 0f), Offset(w / 2, h), 2f)
            for (f in listOf(0.2f, 0.5f)) {
                drawLine(Color(0xFF555555), Offset(w / 2 - w / 2 * f, h * 0.3f), Offset(w / 2 - w / 2 * f, h * 0.7f), 1.5f)
                drawLine(Color(0xFF555555), Offset(w / 2 + w / 2 * f, h * 0.3f), Offset(w / 2 + w / 2 * f, h * 0.7f), 1.5f)
            }
            val n = hits.size
            hits.forEachIndexed { i, hit: MatchedHit ->
                val dev = hit.deviationMs ?: return@forEachIndexed
                val x = (w / 2 + (dev / window).toFloat() * w / 2).coerceIn(0f, w)
                val alpha = 0.25f + 0.75f * (i + 1) / n
                drawCircle(deviationColor(dev, window).copy(alpha = alpha), radius = if (i == n - 1) 7f else 4.5f, center = Offset(x, h / 2))
            }
        }
        Column(Modifier.padding(start = 8.dp), horizontalAlignment = Alignment.End) {
            last?.observed?.key?.takeIf { it >= 0 }?.let { Text(DrumNames.name(it), color = if (last.keyCorrect == false) Bad else Color.White, fontSize = 11.sp) }
            summary?.let { Text("${it.score} · ${if (it.meanMs < 0) "früh" else "spät"} ${kotlin.math.abs(it.meanMs).toInt()} ms", color = Color(0xFF9E9E9E), fontSize = 10.sp) }
        }
    }
}
