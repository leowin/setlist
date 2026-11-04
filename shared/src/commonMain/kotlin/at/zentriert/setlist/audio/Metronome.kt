package at.zentriert.setlist.audio

/** What the metronome plays. Mirrored by `audio/src/Transport.h` in the C++ engine. */
data class TransportConfig(
    val bpm: Double = 120.0,
    val numerator: Int = 4,
    val denominator: Int = 4,
    /** Clicks per beat: 1 = none, 2 = eighths, 3 = triplets, 4 = sixteenths. */
    val subdivision: Int = 1,
    val countInBars: Int = 0,
) {
    /** Seconds per beat; bpm always refers to quarter notes, so 6/8 at 120 has eighths of 0.25 s. */
    val beatSeconds: Double get() = 60.0 / bpm * 4.0 / denominator
}

enum class ClickType { ACCENT, BEAT, SUB }

data class Click(val seconds: Double, val type: ClickType, val bar: Int, val beat: Int, val sub: Int)

/** Position of the metronome: bar is negative while counting in. */
data class MetronomePosition(val bar: Int, val beat: Int, val countingIn: Boolean) {
    companion object {
        fun fromBeats(positionBeats: Double, numerator: Int): MetronomePosition {
            val bar = kotlin.math.floor(positionBeats / numerator).toInt()
            val beat = (positionBeats - bar * numerator).toInt().coerceIn(0, numerator - 1)
            return MetronomePosition(bar, beat, countingIn = bar < 0)
        }
    }
}

/**
 * Reference implementation of the click pattern. The C++ engine produces the same sequence in real
 * time; `audio/tests` checks it against the same cases as `MetronomeTest`.
 */
object MetronomePattern {
    /** Clicks for [bars] bars from the start of the count-in (time 0 = first click). */
    fun clicks(config: TransportConfig, bars: Int): List<Click> {
        val out = mutableListOf<Click>()
        val subSeconds = config.beatSeconds / config.subdivision
        var index = 0
        for (bar in -config.countInBars until bars) {
            for (beat in 0 until config.numerator) {
                for (sub in 0 until config.subdivision) {
                    val type = when {
                        sub > 0 -> ClickType.SUB
                        beat == 0 -> ClickType.ACCENT
                        else -> ClickType.BEAT
                    }
                    out += Click(index * subSeconds, type, bar, beat, sub)
                    index++
                }
            }
        }
        return out
    }
}
