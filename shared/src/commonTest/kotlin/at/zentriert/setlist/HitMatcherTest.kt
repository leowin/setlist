package at.zentriert.setlist

import at.zentriert.setlist.practice.Expected
import at.zentriert.setlist.practice.HitMatcher
import at.zentriert.setlist.practice.Observed
import at.zentriert.setlist.practice.Rating
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HitMatcherTest {
    private val score = Score().withTempo(120.0)   // beat = 0.5 s, bar = 2 s
    private val drums = Part(Instrument.DRUMS, notes = listOf(
        Note(0, 240, 36), Note(480, 240, 42), Note(960, 240, 38), Note(1440, 240, 42),   // kick, hat, snare, hat
    ))

    @Test
    fun expectedTimesFollowTheTempoMap() {
        val e = HitMatcher.expectedFromScore(score, drums)
        assertEquals(listOf(0.0, 0.5, 1.0, 1.5), e.map { it.seconds })
        assertEquals(listOf(36, 42, 38, 42), e.map { it.key })
        val grid = HitMatcher.expectedFromGrid(score, 2, 0, 1920)
        assertEquals(8, grid.size)
        assertEquals(0.25, grid[1].seconds)
        assertNull(grid[1].key)
    }

    @Test
    fun earlyLateMissedAndExtra() {
        val e = HitMatcher.expectedFromScore(score, drums)
        val heard = listOf(Observed(0.010, 36), Observed(0.480, 42), Observed(1.300), Observed(1.500, 42))   // snare at 1.0 missing, extra at 1.3
        val r = HitMatcher.match(e, heard, windowMs = 120.0)
        assertEquals(listOf(10.0, -20.0, null, 0.0), r.hits.map { it.deviationMs })
        assertEquals(listOf(960L), r.missed.map { it.tick })
        assertEquals(1, r.extra.size)
        val s = Rating.summarize(r)
        assertEquals(4, s.expected); assertEquals(3, s.hit); assertEquals(1, s.missed); assertEquals(1, s.extra)
        assertEquals(1.0, s.keyAccuracy)
        assertTrue(s.score in 70..90, "score ${s.score}")
    }

    @Test
    fun rightDrumWinsOverSlightlyCloserWrongOne() {
        val e = listOf(Expected(1.0, 480, 36), Expected(1.05, 504, 42))
        val heard = listOf(Observed(1.03, 42))
        val r = HitMatcher.match(e, heard)
        assertEquals(42, r.hits.single().expected?.key)
        assertEquals(true, r.hits.single().keyCorrect)
        // Unknown drum (-1) is matched by time only and not counted against the key accuracy.
        val r2 = HitMatcher.match(e, listOf(Observed(1.01, -1)))
        assertEquals(36, r2.hits.single().expected?.key)
        assertNull(r2.hits.single().keyCorrect)
    }

    @Test
    fun doubleHitOnlyMatchesOnce() {
        val e = listOf(Expected(1.0, 480, 38))
        val r = HitMatcher.match(e, listOf(Observed(0.99, 38), Observed(1.02, 38)))
        assertEquals(1, r.hits.count { it.expected != null })
        assertEquals(-10.0, r.hits.first { it.expected != null }.deviationMs)
        assertEquals(1, r.extra.size)
    }

    @Test
    fun perfectTakeScoresHundredAndSloppyOneLess() {
        val e = HitMatcher.expectedFromScore(score, drums)
        val perfect = Rating.summarize(HitMatcher.match(e, e.map { Observed(it.seconds, it.key) }))
        assertEquals(100, perfect.score)
        val sloppy = Rating.summarize(HitMatcher.match(e, listOf(Observed(0.06, 36), Observed(0.44, 42), Observed(1.07, 38), Observed(1.45, 38))))
        assertTrue(sloppy.score < 70, "score ${sloppy.score}")
        assertEquals(0.75, sloppy.keyAccuracy)
        val bars = Rating.perBar(HitMatcher.match(e, e.map { Observed(it.seconds + 0.02, it.key) }), barOfTick = { (it / 1920).toInt() + 1 }, barOfSeconds = { (it / 2.0).toInt() + 1 })
        assertEquals(listOf(1), bars.map { it.bar })
        assertEquals(20.0, bars.single().summary.meanMs)
    }
}
