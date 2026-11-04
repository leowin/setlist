package at.zentriert.setlist

import at.zentriert.setlist.audio.ClickType
import at.zentriert.setlist.audio.MetronomePattern
import at.zentriert.setlist.audio.MetronomePosition
import at.zentriert.setlist.audio.TransportConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/** The same cases are checked against the C++ engine in audio/tests/transport_test.cpp. */
class MetronomeTest {
    @Test
    fun fourFourAt120() {
        val clicks = MetronomePattern.clicks(TransportConfig(120.0, 4, 4), bars = 1)
        assertEquals(listOf(0.0, 0.5, 1.0, 1.5), clicks.map { it.seconds })
        assertEquals(listOf(ClickType.ACCENT, ClickType.BEAT, ClickType.BEAT, ClickType.BEAT), clicks.map { it.type })
    }

    @Test
    fun sixEightHasEighthBeats() {
        val clicks = MetronomePattern.clicks(TransportConfig(120.0, 6, 8), bars = 1)
        assertEquals(6, clicks.size)
        assertEquals(0.25, clicks[1].seconds)
        assertEquals(1.25, clicks[5].seconds)
    }

    @Test
    fun sevenEightWithEighthSubdivision() {
        val clicks = MetronomePattern.clicks(TransportConfig(140.0, 7, 8, subdivision = 2), bars = 1)
        assertEquals(14, clicks.size)
        assertEquals(listOf(ClickType.ACCENT, ClickType.SUB, ClickType.BEAT, ClickType.SUB), clicks.take(4).map { it.type })
        assertEquals(60.0 / 140 / 2 / 2, clicks[1].seconds, 1e-9)
    }

    @Test
    fun countInComesFirstWithNegativeBars() {
        val clicks = MetronomePattern.clicks(TransportConfig(120.0, 3, 4, countInBars = 1), bars = 1)
        assertEquals(6, clicks.size)
        assertEquals(-1, clicks[0].bar)
        assertEquals(0, clicks[3].bar)
        assertEquals(1.5, clicks[3].seconds)
    }

    @Test
    fun positionFromBeats() {
        assertEquals(MetronomePosition(-1, 2, countingIn = true), MetronomePosition.fromBeats(-2.0, 4))
        assertEquals(MetronomePosition(0, 0, countingIn = false), MetronomePosition.fromBeats(0.3, 4))
        assertEquals(MetronomePosition(1, 3, countingIn = false), MetronomePosition.fromBeats(7.9, 4))
    }
}
