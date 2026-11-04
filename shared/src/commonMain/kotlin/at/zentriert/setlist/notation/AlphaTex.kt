package at.zentriert.setlist.notation

import at.zentriert.setlist.Instrument
import at.zentriert.setlist.Note
import at.zentriert.setlist.Part
import at.zentriert.setlist.Score

/**
 * Renders a score as alphaTex (https://alphatab.net/docs/alphatex) for the alphaTab viewer.
 * Rhythms are quantized to 1/32; longer values are split into tied notes; drums use custom
 * articulations named after their General-MIDI key ("p36" – the prefix must not look like a pitch).
 */
object AlphaTex {
    enum class View(val label: String, val staff: String) {
        SCORE("Noten", "{score}"), TAB("Tab", "{tabs}"), BOTH("Noten + Tab", "{score tabs}"),
    }

    private val NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val BASS_TUNING = listOf(43, 38, 33, 28)          // string 1 (G2) … 4 (E1)
    private val GUITAR_TUNING = listOf(64, 59, 55, 50, 45, 40) // E4 … E2
    /** alphaTab's built-in percussion articulations we can address (same numbering as GM here). */
    private val DRUM_KEYS = (35..81).toSet()

    fun render(score: Score, title: String, instruments: Collection<Instrument> = Instrument.entries, view: View = View.BOTH): String {
        val sb = StringBuilder()
        sb.append("\\title \"").append(escape(title)).append("\"\n")
        sb.append("\\tempo ").append(score.bpm.toInt()).append("\n")
        val parts = score.parts.filter { it.instrument in instruments && it.notes.isNotEmpty() }
        for (part in parts) sb.append(renderTrack(score, part, view))
        return sb.toString()
    }

    private fun renderTrack(score: Score, part: Part, view: View): String {
        val sb = StringBuilder()
        val drums = part.instrument == Instrument.DRUMS
        val tuning = when (part.instrument) { Instrument.BASS -> BASS_TUNING; Instrument.GUITAR -> GUITAR_TUNING; else -> null }
        sb.append("\\track \"").append(escape(part.name)).append("\"")
        if (drums) sb.append(" { instrument percussion }") else sb.append(" { instrument ").append(part.program).append(" }")
        sb.append("\n")
        when {
            drums -> sb.append("\\staff {score}\n\\clef neutral\n")
            tuning != null -> {
                sb.append("\\staff ").append(view.staff).append("\n")
                sb.append("\\tuning (").append(tuning.joinToString(" ") { pitchName(it) }).append(") { hide }\n")
            }
            part.instrument == Instrument.KEYS -> sb.append("\\staff {score}\n\\tuning piano\n")
            else -> sb.append("\\staff {score}\n\\tuning voice\n")
        }
        if (drums) {
            val used = part.notes.map { drumKey(it.key) }.toSortedSet()
            used.forEach { sb.append("\\articulation (p").append(it).append(" ").append(it).append(")\n") }
        }
        sb.append("\\ts (").append(score.meter.numerator).append(" ").append(score.meter.denominator).append(")\n")

        val beatTicks = score.ppq * 4L / score.meter.denominator
        val barTicks = beatTicks * score.meter.numerator
        val grid = (score.ppq / 8).toLong().coerceAtLeast(1)   // 1/32
        val lastTick = part.notes.maxOf { it.tick + it.duration }
        val bars = ((lastTick + barTicks - 1) / barTicks).coerceAtLeast(1)

        // Quantize onsets/ends to the grid; drums get a fixed short length (gap to next hit decides).
        val notes = part.notes.map { n ->
            val start = snap(n.tick, grid)
            val end = if (drums) start + grid else maxOf(snap(n.tick + n.duration, grid), start + grid)
            n.copy(tick = start, duration = end - start)
        }.sortedWith(compareBy({ it.tick }, { -it.key }))

        for (bar in 0 until bars) {
            val barStart = bar * barTicks
            val barEnd = barStart + barTicks
            var pos = barStart
            val beats = mutableListOf<String>()
            val onsets = notes.filter { it.tick < barEnd && it.tick + it.duration > barStart }
                .groupBy { maxOf(it.tick, barStart) }.toSortedMap()
            for ((onset, chord) in onsets) {
                if (onset < pos) continue  // already covered by an earlier chord that rings on
                if (onset > pos) { beats += rests(onset - pos, score.ppq); pos = onset }
                val nextOnset = onsets.keys.firstOrNull { it > onset } ?: barEnd
                val sounding = notes.filter { it.tick <= onset && it.tick + it.duration > onset }
                // Drums: a hit lasts until the next hit, at most one beat; melodic: until the first note ends.
                val length = if (drums) minOf(nextOnset, barEnd, onset + beatTicks) - onset
                else minOf(nextOnset, barEnd, sounding.minOf { it.tick + it.duration }) - onset
                if (length <= 0) continue
                var first = true
                for ((value, dotted) in decompose(length, score.ppq)) {
                    val tied = !first || sounding.any { it.tick < onset }
                    beats += beat(sounding, value, dotted, tied && !drums, drums, tuning)
                    first = false
                }
                pos = onset + length
            }
            // Notes still ringing at the end of a bar continue tied in the next bar (they appear in
            // its onsets at barStart); within a bar, a chord that outlasts a shorter note is cut at
            // that note's end – an acceptable simplification for lead sheets.
            if (pos < barEnd) beats += rests(barEnd - pos, score.ppq)
            sb.append(beats.joinToString(" ")).append(" |\n")
        }
        return sb.toString()
    }

    private fun beat(notes: List<Note>, value: Int, dotted: Boolean, tied: Boolean, drums: Boolean, tuning: List<Int>?): String {
        val tie = if (tied) "{-}" else ""
        val content = if (drums) {
            notes.map { "p${drumKey(it.key)}" }.distinct().joinToString(" ")
        } else if (tuning != null) {
            val used = HashSet<Int>()
            notes.sortedByDescending { it.key }.map { n ->
                val (fret, string) = fretFor(n.key, tuning, used)
                "$fret.$string$tie"
            }.joinToString(" ")
        } else notes.map { pitchName(it.key) + tie }.joinToString(" ")
        val wrapped = if (notes.size > 1 || drums && notes.size > 1) "($content)" else content
        return wrapped + "." + value + (if (dotted) "{d}" else "")
    }

    private fun rests(length: Long, ppq: Int): List<String> =
        decompose(length, ppq).map { (value, dotted) -> "r.$value" + if (dotted) "{d}" else "" }

    /** Splits a length into note values (whole … 1/32, optionally dotted), largest first. */
    fun decompose(length: Long, ppq: Int): List<Pair<Int, Boolean>> {
        val out = mutableListOf<Pair<Int, Boolean>>()
        var rest = length
        val values = listOf(1, 2, 4, 8, 16, 32)
        while (rest > 0) {
            // A dotted value only when it fits exactly (reads better than odd rest chains); else the
            // largest plain value that fits.
            var chosen: Pair<Int, Boolean>? = null
            for (v in values) {
                val plain = ppq * 4L / v
                if (v <= 16 && plain * 3 / 2 == rest) { chosen = v to true; break }
                if (plain <= rest) { chosen = v to false; break }
            }
            val (v, dotted) = chosen ?: (32 to false)
            out += v to dotted
            val used = ppq * 4L / v * (if (dotted) 3 else 2) / 2
            rest -= used
            if (used <= 0) break
        }
        return out
    }

    /** Lowest playable fret, preferring higher strings; strings already used in the chord are skipped. */
    fun fretFor(key: Int, tuning: List<Int>, used: MutableSet<Int>): Pair<Int, Int> {
        var best: Pair<Int, Int>? = null
        for ((i, open) in tuning.withIndex()) {
            val string = i + 1
            if (string in used) continue
            val fret = key - open
            if (fret in 0..24 && (best == null || fret < best.first)) best = fret to string
        }
        val result = best ?: run {
            val string = (tuning.indices.firstOrNull { it + 1 !in used } ?: 0) + 1
            (key - tuning[string - 1]).coerceIn(0, 24) to string
        }
        used += result.second
        return result
    }

    private fun drumKey(key: Int) = if (key in DRUM_KEYS) key else 38

    private fun snap(tick: Long, grid: Long) = (tick + grid / 2) / grid * grid

    fun pitchName(key: Int) = NAMES[key % 12] + (key / 12 - 1)

    private fun escape(s: String) = s.replace("\"", "'")
}
