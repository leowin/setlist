package at.zentriert.setlist

import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

enum class Instrument(val label: String) {
    BASS("Bass"), DRUMS("Schlagzeug"), GUITAR("Gitarre"), VOCALS("Gesang"),
}

@Serializable
data class TempoChange(val tick: Long, val bpm: Double)

@Serializable
data class MeterChange(val tick: Long, val numerator: Int, val denominator: Int) {
    val label get() = "$numerator/$denominator"
}

@Serializable
data class Note(val tick: Long, val duration: Long, val key: Int, val velocity: Int = 100)

@Serializable
data class Part(
    val instrument: Instrument,
    val name: String = instrument.label,
    /** General MIDI program (ignored for drums). */
    val program: Int = 0,
    val notes: List<Note> = emptyList(),
)

/**
 * Everything musical about a song: tempo, meter and the notes per instrument.
 * Shared between all setlist copies of a song via [Song.scoreId].
 */
@Serializable
data class Score(
    val id: String = newId(),
    val ppq: Int = 480,
    val tempoMap: List<TempoChange> = listOf(TempoChange(0, 120.0)),
    val meterMap: List<MeterChange> = listOf(MeterChange(0, 4, 4)),
    val parts: List<Part> = emptyList(),
    /** GM drum keys on the 16 pads, left to right, top to bottom. */
    val drumPads: List<Int> = DEFAULT_DRUM_PADS,
) {
    val bpm: Double get() = tempoMap.firstOrNull()?.bpm ?: 120.0
    val meter: MeterChange get() = meterMap.firstOrNull() ?: MeterChange(0, 4, 4)

    /** Short form for lists, e.g. "120 · 4/4". */
    val summary: String get() = "${bpm.toBpmText()} · ${meter.label}"

    fun withTempo(bpm: Double) = copy(tempoMap = listOf(TempoChange(0, bpm)) + tempoMap.drop(1))
    fun withMeter(numerator: Int, denominator: Int) =
        copy(meterMap = listOf(MeterChange(0, numerator, denominator)) + meterMap.drop(1))

    companion object {
        val METERS = listOf(2 to 4, 3 to 4, 4 to 4, 5 to 4, 6 to 8, 7 to 8, 9 to 8, 12 to 8)
        const val MIN_BPM = 20.0
        const val MAX_BPM = 300.0
        val DEFAULT_DRUM_PADS = listOf(
            36, 38, 42, 46, // Kick, Snare, HH closed, HH open
            45, 47, 48, 50, // Toms
            49, 51, 53, 57, // Crash, Ride, Ride bell, Crash 2
            37, 39, 54, 56, // Side stick, Clap, Tambourine, Cowbell
        )
    }
}

fun Double.toBpmText(): String {
    val rounded = (this * 10).toLong()
    return if (rounded % 10 == 0L) "${rounded / 10}" else "${rounded / 10}.${rounded % 10}"
}

/** Converts between ticks and time using a tempo map (tempo changes at given ticks). */
class TempoMap(private val changes: List<TempoChange>, private val ppq: Int) {
    constructor(score: Score) : this(score.tempoMap, score.ppq)

    private val sorted = changes.sortedBy { it.tick }.ifEmpty { listOf(TempoChange(0, 120.0)) }

    /** Microseconds at [tick]. */
    fun tickToMicros(tick: Long): Long {
        var micros = 0.0
        var lastTick = 0L
        var bpm = sorted.first().bpm
        for (change in sorted) {
            if (change.tick >= tick) break
            if (change.tick > lastTick) micros += (change.tick - lastTick) * microsPerTick(bpm)
            lastTick = maxOf(lastTick, change.tick)
            bpm = change.bpm
        }
        micros += (tick - lastTick) * microsPerTick(bpm)
        return micros.roundToLong()
    }

    fun microsToTick(micros: Long): Long {
        var elapsed = 0.0
        var lastTick = 0L
        var bpm = sorted.first().bpm
        for (change in sorted) {
            if (change.tick <= lastTick) { bpm = change.bpm; continue }
            val segment = (change.tick - lastTick) * microsPerTick(bpm)
            if (elapsed + segment >= micros) break
            elapsed += segment
            lastTick = change.tick
            bpm = change.bpm
        }
        return lastTick + ((micros - elapsed) / microsPerTick(bpm)).roundToLong()
    }

    private fun microsPerTick(bpm: Double) = 60_000_000.0 / bpm / ppq
}
