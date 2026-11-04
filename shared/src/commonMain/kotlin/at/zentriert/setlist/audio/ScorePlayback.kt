package at.zentriert.setlist.audio

import at.zentriert.setlist.Score
import at.zentriert.setlist.TempoMap

/** Turns a score into sequencer events; pure so it can be unit-tested. */
object ScorePlayback {
    /**
     * Events from [fromTick] on, shifted by [offsetSeconds] (the count-in). Notes already sounding at
     * [fromTick] start right away with their remaining length.
     */
    fun events(score: Score, fromTick: Long = 0, offsetSeconds: Double = 0.0): List<PlaybackEvent> {
        val map = TempoMap(score)
        val origin = map.tickToMicros(fromTick)
        fun seconds(tick: Long) = (map.tickToMicros(maxOf(tick, fromTick)) - origin) / 1_000_000.0 + offsetSeconds
        val out = ArrayList<PlaybackEvent>()
        score.parts.forEachIndexed { part, p ->
            for (n in p.notes) {
                if (n.tick + n.duration <= fromTick) continue
                out += PlaybackEvent(seconds(n.tick), part, n.key, n.velocity.coerceIn(1, 127))
                out += PlaybackEvent(seconds(n.tick + n.duration), part, n.key, 0)
            }
        }
        // Note-offs first at equal times so a re-struck note isn't cut off.
        return out.sortedWith(compareBy({ it.seconds }, { if (it.velocity == 0) 0 else 1 }))
    }

    /** Converts a playback position back to a tick, or null while still in the count-in. */
    fun tickAt(score: Score, fromTick: Long, offsetSeconds: Double, positionSeconds: Double): Long? {
        val local = positionSeconds - offsetSeconds
        if (local < 0) return null
        val map = TempoMap(score)
        return map.microsToTick(map.tickToMicros(fromTick) + (local * 1_000_000).toLong())
    }

    /** Bar (1-based) and beat (1-based) of a tick, using the first meter. */
    fun barBeat(score: Score, tick: Long): Pair<Int, Int> {
        val m = score.meter
        val beatTicks = score.ppq * 4 / m.denominator
        val barTicks = beatTicks * m.numerator
        return (tick / barTicks).toInt() + 1 to ((tick % barTicks) / beatTicks).toInt() + 1
    }

    fun lastTick(score: Score): Long = score.parts.flatMap { it.notes }.maxOfOrNull { it.tick + it.duration } ?: 0
}
