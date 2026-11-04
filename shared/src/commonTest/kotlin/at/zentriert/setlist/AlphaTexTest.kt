package at.zentriert.setlist

import at.zentriert.setlist.notation.AlphaTex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlphaTexTest {
    @Test
    fun bassLineWithRestsAndTabs() {
        val score = Score(
            tempoMap = listOf(TempoChange(0, 100.0)),
            parts = listOf(Part(Instrument.BASS, "Bass", 33, listOf(Note(0, 480, 40), Note(960, 240, 43), Note(1200, 240, 45)))),
        )
        val tex = AlphaTex.render(score, "Test", view = AlphaTex.View.BOTH)
        assertTrue(tex.contains("\\tempo 100"), tex)
        assertTrue(tex.contains("\\track \"Bass\" { instrument 33 }"), tex)
        assertTrue(tex.contains("\\staff {score tabs}"), tex)
        assertTrue(tex.contains("\\tuning (G2 D2 A1 E1)"), tex)
        assertTrue(tex.contains("\\ts (4 4)"), tex)
        // E2 on the D string (string 2) fret 2, quarter; quarter rest; G2 open G, eighth; A2 fret 2 on G, eighth; rest
        assertTrue(tex.contains("2.2.4 r.4 0.1.8 2.1.8 r.4 |"), tex)
    }

    @Test
    fun longNotesAreSplitAndTiedAcrossBars() {
        val score = Score(parts = listOf(Part(Instrument.VOCALS, "Gesang", 81, listOf(Note(0, 1920 + 720, 60)))))
        val tex = AlphaTex.render(score, "T", view = AlphaTex.View.SCORE)
        assertTrue(tex.contains("C4.1 |"), tex)                 // whole note in bar 1
        assertTrue(tex.contains("C4{-}.4{d} r.2 r.8 |"), tex)   // dotted quarter tied, then rests
    }

    @Test
    fun drumsUseArticulationsAndChords() {
        val score = Score(parts = listOf(Part(Instrument.DRUMS, "Drums", 0, listOf(Note(0, 120, 36), Note(0, 120, 42), Note(480, 120, 38)))))
        val tex = AlphaTex.render(score, "T")
        assertTrue(tex.contains("{ instrument percussion }"), tex)
        assertTrue(tex.contains("\\articulation (p36 36)"), tex)
        assertTrue(tex.contains("(p42 p36).4 p38.4 r.2 |"), tex)
    }

    @Test
    fun decomposeUsesDots() {
        assertEquals(listOf(2 to true), AlphaTex.decompose(1440, 480))
        assertEquals(listOf(4 to false, 32 to false), AlphaTex.decompose(540, 480))
    }

    @Test
    fun fretChoicePrefersLowFretsAndFreeStrings() {
        val used = mutableSetOf<Int>()
        assertEquals(2 to 2, AlphaTex.fretFor(40, listOf(43, 38, 33, 28), used))   // E2 → D string (2) fret 2
        assertEquals(7 to 3, AlphaTex.fretFor(40, listOf(43, 38, 33, 28), used))   // D string taken → A string (3) fret 7
    }
}
