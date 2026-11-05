package at.zentriert.setlist.practice

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Numbers for one bar or the whole take. */
@Serializable
data class RatingSummary(
    val expected: Int,
    val hit: Int,
    val missed: Int,
    val extra: Int,
    /** Mean deviation in ms (+ = late) and its spread. */
    val meanMs: Double,
    val sdMs: Double,
    /** Share of matched hits on the right drum/note; null when the reference has none. */
    val keyAccuracy: Double? = null,
    /** 0–100. */
    val score: Int,
) {
    val hitRate: Double get() = if (expected == 0) 1.0 else hit.toDouble() / expected
}

@Serializable
data class BarRating(val bar: Int, val summary: RatingSummary)

object Rating {
    fun summarize(result: MatchResult): RatingSummary {
        val matched = result.hits.filter { it.expected != null }
        val devs = matched.mapNotNull { it.deviationMs }
        val mean = if (devs.isEmpty()) 0.0 else devs.average()
        val sd = if (devs.size < 2) 0.0 else sqrt(devs.sumOf { (it - mean) * (it - mean) } / (devs.size - 1))
        val withKey = matched.mapNotNull { it.keyCorrect }
        val keyAcc = if (withKey.isEmpty()) null else withKey.count { it }.toDouble() / withKey.size
        val expected = matched.size + result.missed.size
        val hitRate = if (expected == 0) 1.0 else matched.size.toDouble() / expected
        val extraPenalty = if (expected == 0) 0.0 else 10.0 * (result.extra.size.toDouble() / expected).coerceAtMost(1.0)
        val score = (100.0 - 0.4 * sd - 0.2 * abs(mean) - 30.0 * (1 - hitRate) - 20.0 * (1 - (keyAcc ?: 1.0)) - extraPenalty)
            .coerceIn(0.0, 100.0)
        return RatingSummary(expected, matched.size, result.missed.size, result.extra.size, (mean * 10).roundToInt() / 10.0, (sd * 10).roundToInt() / 10.0, keyAcc, score.roundToInt())
    }

    /** Splits by bar (ticks → bar through [barOfTick]) and rates each bar on its own. */
    fun perBar(result: MatchResult, barOfTick: (Long) -> Int, barOfSeconds: (Double) -> Int): List<BarRating> {
        val bars = HashMap<Int, Pair<MutableList<MatchedHit>, MutableList<Expected>>>()
        fun slot(bar: Int) = bars.getOrPut(bar) { mutableListOf<MatchedHit>() to mutableListOf() }
        for (h in result.hits) slot(h.expected?.let { barOfTick(it.tick) } ?: barOfSeconds(h.observed.seconds)).first += h
        for (m in result.missed) slot(barOfTick(m.tick)).second += m
        return bars.entries.sortedBy { it.key }.map { (bar, lists) -> BarRating(bar, summarize(MatchResult(lists.first, lists.second))) }
    }

    fun label(score: Int) = when {
        score >= 90 -> "Sehr gut"
        score >= 75 -> "Gut"
        score >= 60 -> "Okay"
        score >= 40 -> "Üben"
        else -> "Noch mal"
    }
}
