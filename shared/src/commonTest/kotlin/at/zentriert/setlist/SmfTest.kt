package at.zentriert.setlist

import at.zentriert.setlist.midi.MidiFormatException
import at.zentriert.setlist.midi.Smf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SmfTest {
    private val score = Score(
        tempoMap = listOf(TempoChange(0, 100.0), TempoChange(1920, 140.0)),
        meterMap = listOf(MeterChange(0, 3, 4)),
        parts = listOf(
            Part(Instrument.BASS, "Bass", program = 33, notes = listOf(Note(0, 480, 40, 100), Note(480, 240, 43, 90), Note(480, 240, 47, 90))),
            Part(Instrument.DRUMS, "Drums", notes = listOf(Note(0, 120, 36, 110), Note(480, 120, 38, 100))),
        ),
    )

    @Test
    fun roundTripKeepsNotesTempoAndMeter() {
        val file = Smf.parse(Smf.write(score))
        assertEquals(listOf(TempoChange(0, 100.0), TempoChange(1920, 140.0)), file.tempoMap)
        assertEquals(listOf(MeterChange(0, 3, 4)), file.meterMap)
        assertEquals(2, file.tracks.size)
        val bass = file.tracks.first { it.name == "Bass" }
        assertEquals(listOf(33), bass.programs)
        assertEquals(score.parts[0].notes, bass.notes)
        val drums = file.tracks.first { it.name == "Drums" }
        assertTrue(drums.isDrums)
        assertEquals(score.parts[1].notes, drums.notes)
    }

    @Test
    fun pianoTracksBecomeKeys() {
        val piano = Score(parts = listOf(Part(Instrument.KEYS, "Piano", program = 0, notes = listOf(Note(0, 480, 60)))))
        val file = Smf.parse(Smf.write(piano))
        assertEquals(Instrument.KEYS, file.tracks.single().suggestedInstrument())
    }

    @Test
    fun suggestsInstruments() {
        val file = Smf.parse(Smf.write(score))
        assertEquals(Instrument.BASS, file.tracks.first { it.name == "Bass" }.suggestedInstrument())
        assertEquals(Instrument.DRUMS, file.tracks.first { it.name == "Drums" }.suggestedInstrument())
    }

    @Test
    fun toScoreMergesTracksOfSameInstrument() {
        val file = Smf.parse(Smf.write(score))
        val result = file.toScore(file.tracks.associate { it.index to Instrument.GUITAR })
        assertEquals(1, result.parts.size)
        assertEquals(Instrument.GUITAR, result.parts[0].instrument)
        assertEquals(5, result.parts[0].notes.size)
    }

    @Test
    fun runningStatusAndVelocityZeroNoteOff() {
        // Format 0, 96 ppq: note on C4 (running status reused for the second note), then both off via velocity 0
        val track = byteArrayOf(
            0x00, 0x90.toByte(), 60, 100,          // note on 60
            0x00, 64, 100,                          // running status: note on 64
            0x60, 60, 0,                            // 96 ticks later: note off 60 (vel 0)
            0x00, 64, 0,                            // note off 64
            0x00, 0xFF.toByte(), 0x2F, 0x00,        // end of track
        )
        val bytes = "MThd".encodeToByteArray() + byteArrayOf(0, 0, 0, 6, 0, 0, 0, 1, 0, 96) +
            "MTrk".encodeToByteArray() + byteArrayOf(0, 0, 0, track.size.toByte()) + track
        val file = Smf.parse(bytes)
        assertEquals(1, file.tracks.size)
        // 96 ticks at 96 ppq = one quarter = 480 ticks normalized
        assertEquals(listOf(Note(0, 480, 60, 100), Note(0, 480, 64, 100)), file.tracks[0].notes)
    }

    @Test
    fun rejectsNonMidi() {
        assertFailsWith<MidiFormatException> { Smf.parse("RIFF....".encodeToByteArray()) }
    }
}
