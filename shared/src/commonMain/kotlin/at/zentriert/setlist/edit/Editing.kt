package at.zentriert.setlist.edit

import at.zentriert.setlist.Note
import at.zentriert.setlist.Part
import at.zentriert.setlist.Score
import at.zentriert.setlist.TempoMap
import kotlin.math.roundToLong

/** A note length / grid: 4 = quarter, 8 = eighth … optionally triplet or dotted. */
data class NoteValue(val division: Int = 8, val triplet: Boolean = false, val dotted: Boolean = false) {
    fun ticks(ppq: Int): Long {
        var t = ppq * 4L / division
        if (triplet) t = t * 2 / 3
        if (dotted) t = t * 3 / 2
        return t.coerceAtLeast(1)
    }

    val label: String get() = "1/$division" + (if (triplet) "T" else "") + (if (dotted) "." else "")

    companion object {
        val CHOICES = listOf(1, 2, 4, 8, 16, 32)
    }
}

object Quantize {
    /** Snaps [tick] towards the nearest grid line by [strength] (1 = exactly on the grid). */
    fun snap(tick: Long, gridTicks: Long, strength: Float = 1f): Long {
        if (gridTicks <= 0 || strength <= 0f) return tick
        val nearest = (tick.toDouble() / gridTicks).roundToLong() * gridTicks
        return (tick + (nearest - tick) * strength).roundToLong().coerceAtLeast(0)
    }

    fun notes(notes: List<Note>, gridTicks: Long, strength: Float = 1f, lengths: Boolean = false): List<Note> =
        notes.map { n ->
            val start = snap(n.tick, gridTicks, strength)
            val duration = if (lengths) snap(n.duration, gridTicks, strength).coerceAtLeast(gridTicks) else n.duration
            n.copy(tick = start, duration = duration)
        }
}

/** Pure edit operations on a part's notes; the view model wraps them with an undo stack. */
object PartEdit {
    fun add(part: Part, note: Note): Part = part.copy(notes = (part.notes + note).sorted())

    fun remove(part: Part, selection: Set<Note>): Part = part.copy(notes = part.notes.filterNot { it in selection })

    /** Moves the selection by [deltaTicks]/[deltaKeys]; nothing moves before tick 0 or outside 0..127. */
    fun move(part: Part, selection: Set<Note>, deltaTicks: Long, deltaKeys: Int): Part {
        if (selection.any { it.tick + deltaTicks < 0 || it.key + deltaKeys !in 0..127 }) return part
        return part.copy(notes = part.notes.map {
            if (it in selection) it.copy(tick = it.tick + deltaTicks, key = it.key + deltaKeys) else it
        }.sorted())
    }

    fun resize(part: Part, selection: Set<Note>, deltaTicks: Long, minimum: Long): Part =
        part.copy(notes = part.notes.map {
            if (it in selection) it.copy(duration = (it.duration + deltaTicks).coerceAtLeast(minimum)) else it
        })

    fun setVelocity(part: Part, selection: Set<Note>, velocity: Int): Part =
        part.copy(notes = part.notes.map { if (it in selection) it.copy(velocity = velocity.coerceIn(1, 127)) else it })

    /** Replaces everything in [fromTick, toTick) with [notes] (used when recording in replace mode). */
    fun replaceRange(part: Part, fromTick: Long, toTick: Long, notes: List<Note>): Part =
        part.copy(notes = (part.notes.filterNot { it.tick in fromTick until toTick } + notes).sorted())

    fun transpose(part: Part, semitones: Int): Part =
        if (part.notes.any { it.key + semitones !in 0..127 }) part
        else part.copy(notes = part.notes.map { it.copy(key = it.key + semitones) })

    private fun List<Note>.sorted() = sortedWith(compareBy({ it.tick }, { it.key }))
}

/** A key press while recording, in playback seconds. */
data class RecordedPress(val onSeconds: Double, val offSeconds: Double?, val key: Int, val velocity: Int)

object Recording {
    /**
     * Converts presses to notes. [latencySeconds] (output latency + touch delay) is subtracted so a
     * tap that *sounded* on the beat lands on the beat; then the start is quantized if a grid is given.
     */
    fun toNotes(
        presses: List<RecordedPress>, score: Score, fromTick: Long, offsetSeconds: Double,
        latencySeconds: Double, gridTicks: Long?, strength: Float, defaultDuration: Long,
    ): List<Note> {
        val map = TempoMap(score)
        val origin = map.tickToMicros(fromTick)
        fun tick(seconds: Double): Long {
            val local = seconds - offsetSeconds - latencySeconds
            return map.microsToTick(origin + (local * 1_000_000).toLong()).coerceAtLeast(fromTick)
        }
        return presses.map { p ->
            val start = tick(p.onSeconds).let { if (gridTicks != null) Quantize.snap(it, gridTicks, strength) else it }
            val end = p.offSeconds?.let(::tick) ?: (start + defaultDuration)
            Note(start, (end - start).coerceAtLeast(gridTicks ?: 1), p.key, p.velocity)
        }.sortedWith(compareBy({ it.tick }, { it.key }))
    }
}

/** Tap-the-beat calibration: mean signed offset of taps to the nearest beat, in seconds. */
object LatencyCalibration {
    fun estimate(tapPositionsBeats: List<Double>, beatSeconds: Double): Double? {
        if (tapPositionsBeats.size < 4) return null
        val offsets = tapPositionsBeats.map { pos ->
            val nearest = kotlin.math.round(pos)
            (pos - nearest) * beatSeconds
        }
        return offsets.sorted().let { it.drop(1).dropLast(1).ifEmpty { it } }.average()
    }
}

/** Loop region in whole bars (1-based, inclusive). */
data class LoopBars(val from: Int, val to: Int) {
    fun ticks(score: Score): Pair<Long, Long> {
        val m = score.meter
        val barTicks = score.ppq * 4L / m.denominator * m.numerator
        return (from - 1) * barTicks to to * barTicks
    }
}
