package at.zentriert.setlist

import kotlin.test.Test
import kotlin.test.assertEquals

class TempoMapTest {
    @Test
    fun constantTempo() {
        val map = TempoMap(listOf(TempoChange(0, 120.0)), ppq = 480)
        assertEquals(500_000, map.tickToMicros(480)) // one quarter at 120 bpm = 0.5 s
        assertEquals(2_000_000, map.tickToMicros(1920))
        assertEquals(1920, map.microsToTick(2_000_000))
    }

    @Test
    fun tempoChangeHalfway() {
        // 2 beats at 120 (1 s), then 60 bpm (1 s per beat)
        val map = TempoMap(listOf(TempoChange(0, 120.0), TempoChange(960, 60.0)), ppq = 480)
        assertEquals(1_000_000, map.tickToMicros(960))
        assertEquals(3_000_000, map.tickToMicros(1920))
        assertEquals(1920, map.microsToTick(3_000_000))
        assertEquals(1440, map.microsToTick(2_000_000))
    }

    @Test
    fun bpmText() {
        assertEquals("120", 120.0.toBpmText())
        assertEquals("98.5", 98.5.toBpmText())
    }
}
