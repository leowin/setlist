package at.zentriert.setlist

import at.zentriert.setlist.audio.ScorePlayback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScorePlaybackTest {
    private val score = Score(
        tempoMap = listOf(TempoChange(0, 120.0)),
        parts = listOf(Part(Instrument.BASS, notes = listOf(Note(0, 480, 40), Note(480, 480, 43), Note(480, 480, 40)))),
    )

    @Test
    fun eventsAreOrderedWithOffsBeforeOns() {
        val events = ScorePlayback.events(score)
        assertEquals(listOf(0.0, 0.5, 0.5, 0.5, 1.0, 1.0), events.map { it.seconds })
        // at 0.5 s: off(40) before on(43)/on(40)
        assertEquals(0, events[1].velocity)
        assertEquals(40, events[1].key)
    }

    @Test
    fun startInsideANoteAndCountInOffset() {
        val events = ScorePlayback.events(score, fromTick = 240, offsetSeconds = 2.0)
        assertEquals(2.0, events.first().seconds)       // note 40 still sounding → starts right away
        assertEquals(2.25, events[1].seconds)           // its off comes after the remaining quarter
        assertNull(ScorePlayback.tickAt(score, 240, 2.0, 1.5))
        assertEquals(480, ScorePlayback.tickAt(score, 240, 2.0, 2.25))
    }

    @Test
    fun barBeat() {
        assertEquals(1 to 1, ScorePlayback.barBeat(score, 0))
        assertEquals(1 to 4, ScorePlayback.barBeat(score, 1440))
        assertEquals(3 to 2, ScorePlayback.barBeat(score, 1920 * 2 + 480))
    }
}
