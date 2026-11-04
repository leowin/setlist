package at.zentriert.setlist

import at.zentriert.setlist.edit.LatencyCalibration
import at.zentriert.setlist.edit.LoopBars
import at.zentriert.setlist.edit.NoteValue
import at.zentriert.setlist.edit.PartEdit
import at.zentriert.setlist.edit.Quantize
import at.zentriert.setlist.edit.RecordedPress
import at.zentriert.setlist.edit.Recording
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EditingTest {
    private val score = Score(tempoMap = listOf(TempoChange(0, 120.0)))

    @Test
    fun noteValues() {
        assertEquals(480, NoteValue(4).ticks(480))
        assertEquals(160, NoteValue(8, triplet = true).ticks(480))
        assertEquals(360, NoteValue(8, dotted = true).ticks(480))
        assertEquals("1/8T", NoteValue(8, triplet = true).label)
    }

    @Test
    fun quantizeWithStrength() {
        assertEquals(480, Quantize.snap(470, 240))
        assertEquals(475, Quantize.snap(470, 240, 0.5f))
        assertEquals(0, Quantize.snap(100, 240))
    }

    @Test
    fun moveRefusesToLeaveTheRange() {
        val part = Part(Instrument.BASS, notes = listOf(Note(0, 480, 40), Note(480, 480, 43)))
        val moved = PartEdit.move(part, setOf(part.notes[1]), -480, 2)
        assertEquals(listOf(Note(0, 480, 40), Note(0, 480, 45)), moved.notes)
        assertEquals(part, PartEdit.move(part, setOf(part.notes[0]), -1, 0))
    }

    @Test
    fun replaceRangeKeepsNotesOutside() {
        val part = Part(Instrument.DRUMS, notes = listOf(Note(0, 120, 36), Note(960, 120, 36), Note(2000, 120, 38)))
        val result = PartEdit.replaceRange(part, 0, 1920, listOf(Note(480, 120, 38)))
        assertEquals(listOf(Note(480, 120, 38), Note(2000, 120, 38)), result.notes)
    }

    @Test
    fun recordingCompensatesLatencyAndQuantizes() {
        // Tap heard at 1.03 s with 30 ms latency → played at 1.00 s = tick 960 at 120 bpm
        val presses = listOf(RecordedPress(1.03, 1.28, 42, 100))
        val notes = Recording.toNotes(presses, score, 0, 0.0, 0.03, gridTicks = 120, strength = 1f, defaultDuration = 120)
        assertEquals(listOf(Note(960, 240, 42, 100)), notes)
    }

    @Test
    fun recordingWithCountInOffsetAndFromTick() {
        val presses = listOf(RecordedPress(2.5, null, 60, 90))   // 2 s count-in, 0.5 s in → one beat after fromTick
        val notes = Recording.toNotes(presses, score, 1920, 2.0, 0.0, gridTicks = null, strength = 0f, defaultDuration = 240)
        assertEquals(listOf(Note(2400, 240, 60, 90)), notes)
    }

    @Test
    fun calibrationUsesTrimmedMean() {
        val taps = listOf(0.06, 1.05, 2.07, 3.05, 4.30, 5.06)  // one outlier
        val latency = LatencyCalibration.estimate(taps, 0.5)
        assertNotNull(latency)
        assertTrue(latency in 0.028..0.032, "was $latency")
    }

    @Test
    fun loopBarsToTicks() {
        assertEquals(1920L to 5760L, LoopBars(2, 3).ticks(score))
    }
}
