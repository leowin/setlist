package at.zentriert.setlist

import at.zentriert.setlist.practice.BarRating
import at.zentriert.setlist.practice.PracticeSession
import at.zentriert.setlist.practice.PracticeStore
import at.zentriert.setlist.practice.RatingSummary
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class PracticeStoreTest {
    @Test
    fun sessionsAndCalibrationRoundTrip() {
        val store = PracticeStore(Path(SystemTemporaryDirectory, "practice-${newId()}"))
        val summary = RatingSummary(4, 3, 1, 0, 5.0, 12.0, 1.0, 82)
        store.append(PracticeSession(songId = "s1", mode = "score", instrument = "DRUMS", bpm = 120.0, windowMs = 120.0, summary = summary, bars = listOf(BarRating(1, summary)), hits = emptyList()))
        store.append(PracticeSession(songId = "s1", mode = "grid", instrument = "DRUMS", bpm = 100.0, windowMs = 120.0, summary = summary.copy(score = 90), bars = emptyList(), hits = emptyList()))
        assertEquals(listOf(82, 90), store.sessions("s1").map { it.summary.score })
        assertEquals(emptyList(), store.sessions("other"))
        var cal = store.calibration()
        assertEquals(0, cal.count(36))
        cal = cal.add(36, FloatArray(20) { it.toFloat() }).add(36, FloatArray(20)).add(38, FloatArray(20))
        store.saveCalibration(cal.copy(micOffsetMeasuredMs = 23))
        val loaded = store.calibration()
        assertEquals(2, loaded.count(36)); assertEquals(1, loaded.count(38)); assertEquals(23, loaded.micOffsetMeasuredMs)
        assertEquals(19f, loaded.samples[36]!![0][19])
        assertEquals(0, loaded.clear(36).count(36))
    }
}
