package at.zentriert.setlist.practice

import at.zentriert.setlist.Part
import at.zentriert.setlist.Score
import at.zentriert.setlist.TempoMap
import kotlin.math.abs
import kotlin.math.roundToLong

/** A moment the player should hit: song time in seconds, the tick, and the drum/note if the reference has one. */
data class Expected(val seconds: Double, val tick: Long, val key: Int? = null)

/** What the microphone heard, in song seconds. */
data class Observed(val seconds: Double, val key: Int? = null, val confidence: Float = 0f, val levelDb: Float = 0f)

/** One heard hit after matching: its expected counterpart (null = extra hit) and the deviation (+ = late). */
data class MatchedHit(val observed: Observed, val expected: Expected?, val deviationMs: Double?) {
    val keyCorrect: Boolean? get() = if (expected?.key == null || observed.key == null || observed.key < 0) null else expected.key == observed.key
}

data class MatchResult(val hits: List<MatchedHit>, val missed: List<Expected>) {
    val extra: List<MatchedHit> get() = hits.filter { it.expected == null }
}

object HitMatcher {
    /** Every note of [part] between the ticks, as song seconds through the score's tempo map. */
    fun expectedFromScore(score: Score, part: Part, fromTick: Long = 0, toTick: Long = Long.MAX_VALUE): List<Expected> {
        val map = TempoMap(score)
        return part.notes.filter { it.tick in fromTick until toTick }
            .map { Expected(map.tickToMicros(it.tick) / 1_000_000.0, it.tick, it.key) }
            .sortedBy { it.seconds }
    }

    /**
     * Metronome grid: every subdivision of the beat between the ticks (subdivision 1 = beats, 2 = eighths,
     * 3 = triplets, 4 = sixteenths), so free playing is rated against the nearest grid point.
     */
    fun expectedFromGrid(score: Score, subdivision: Int, fromTick: Long, toTick: Long): List<Expected> {
        val map = TempoMap(score)
        val beatTicks = score.ppq * 4L / score.meter.denominator
        val step = (beatTicks / subdivision.coerceIn(1, 8)).coerceAtLeast(1)
        val out = ArrayList<Expected>()
        var t = (fromTick / step) * step
        while (t < toTick) { if (t >= fromTick) out += Expected(map.tickToMicros(t) / 1_000_000.0, t); t += step }
        return out
    }

    /**
     * Pairs heard hits with expected ones inside ±[windowMs], closest first; with [preferKey] a hit of the
     * right drum beats a slightly closer wrong one. Each side is used at most once.
     */
    fun match(expected: List<Expected>, observed: List<Observed>, windowMs: Double = 120.0, preferKey: Boolean = true): MatchResult {
        data class Pair(val e: Int, val o: Int, val devMs: Double, val cost: Double)
        val pairs = ArrayList<Pair>()
        for ((oi, o) in observed.withIndex()) {
            for ((ei, e) in expected.withIndex()) {
                val dev = (o.seconds - e.seconds) * 1000.0
                if (abs(dev) > windowMs) continue
                val wrongKey = preferKey && e.key != null && o.key != null && o.key >= 0 && o.key != e.key
                pairs += Pair(ei, oi, dev, abs(dev) + if (wrongKey) windowMs * 0.5 else 0.0)
            }
        }
        pairs.sortBy { it.cost }
        val usedE = BooleanArray(expected.size); val usedO = BooleanArray(observed.size)
        val match = HashMap<Int, Pair>()
        for (p in pairs) {
            if (usedE[p.e] || usedO[p.o]) continue
            usedE[p.e] = true; usedO[p.o] = true
            match[p.o] = p
        }
        val hits = observed.mapIndexed { oi, o ->
            val p = match[oi]
            if (p == null) MatchedHit(o, null, null) else MatchedHit(o, expected[p.e], (p.devMs * 10).roundToLong() / 10.0)
        }
        val missed = expected.filterIndexed { i, _ -> !usedE[i] }
        return MatchResult(hits, missed)
    }
}
