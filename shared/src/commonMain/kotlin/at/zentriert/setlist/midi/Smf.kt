package at.zentriert.setlist.midi

import at.zentriert.setlist.Instrument
import at.zentriert.setlist.MeterChange
import at.zentriert.setlist.Note
import at.zentriert.setlist.Part
import at.zentriert.setlist.Score
import at.zentriert.setlist.TempoChange

/** One track of a Standard MIDI File, already reduced to what the importer needs. */
data class MidiTrack(
    val index: Int,
    val name: String,
    val channels: Set<Int>,
    /** General MIDI programs used (0-based), in order of appearance. */
    val programs: List<Int>,
    /** Notes with ticks normalized to [Score.ppq]. */
    val notes: List<Note>,
) {
    val isDrums: Boolean get() = channels.size == 1 && 9 in channels
    val program: Int get() = programs.firstOrNull() ?: 0

    /** Best guess which band member plays this track; null = probably not needed (pads, strings …). */
    fun suggestedInstrument(): Instrument? = when {
        isDrums -> Instrument.DRUMS
        VOCAL_NAMES.any { name.lowercase().contains(it) } -> Instrument.VOCALS
        programs.any { it in 32..39 } || name.lowercase().contains("bass") -> Instrument.BASS
        programs.any { it in 24..31 } || name.lowercase().contains("guit") || name.lowercase().contains("gitar") -> Instrument.GUITAR
        else -> null
    }

    private companion object {
        val VOCAL_NAMES = listOf("voc", "vox", "gesang", "lead", "sing", "melod", "stimme", "chor")
    }
}

data class MidiFile(
    val tempoMap: List<TempoChange>,
    val meterMap: List<MeterChange>,
    val tracks: List<MidiTrack>,
) {
    /** Builds a score from the chosen tracks. Tracks for the same instrument are merged into one part. */
    fun toScore(assignments: Map<Int, Instrument>, base: Score? = null): Score {
        val parts = Instrument.entries.mapNotNull { instrument ->
            val chosen = tracks.filter { assignments[it.index] == instrument }
            if (chosen.isEmpty()) return@mapNotNull null
            Part(
                instrument = instrument,
                name = chosen.firstOrNull { it.name.isNotBlank() }?.name ?: instrument.label,
                program = if (instrument == Instrument.DRUMS) 0 else chosen.first().program,
                notes = chosen.flatMap { it.notes }.sortedWith(compareBy({ it.tick }, { it.key })),
            )
        }
        return (base ?: Score()).copy(
            ppq = Score.DEFAULT_PPQ,
            tempoMap = tempoMap.ifEmpty { listOf(TempoChange(0, 120.0)) },
            meterMap = meterMap.ifEmpty { listOf(MeterChange(0, 4, 4)) },
            parts = parts,
        )
    }
}

class MidiFormatException(message: String) : Exception(message)

/** Standard MIDI File reader/writer (formats 0 and 1). */
object Smf {
    fun parse(bytes: ByteArray): MidiFile {
        val r = Reader(bytes)
        if (r.chunkId() != "MThd") throw MidiFormatException("Keine MIDI-Datei (MThd fehlt)")
        val headerLength = r.int32()
        val format = r.int16()
        val trackCount = r.int16()
        val division = r.int16()
        r.skip(headerLength - 6)
        if (format !in 0..1) throw MidiFormatException("MIDI-Format $format wird nicht unterstützt")
        if (division and 0x8000 != 0) throw MidiFormatException("SMPTE-Zeitformat wird nicht unterstützt")
        val ppq = division.coerceAtLeast(1)

        val tempo = mutableListOf<TempoChange>()
        val meter = mutableListOf<MeterChange>()
        val tracks = mutableListOf<MidiTrack>()
        var index = 0
        while (r.remaining() >= 8 && index < trackCount) {
            val id = r.chunkId()
            val length = r.int32()
            val end = r.position + length
            if (id != "MTrk") { r.position = end; continue }
            tracks += parseTrack(r, end, index++, ppq, tempo, meter)
            r.position = end
        }
        return MidiFile(
            tempoMap = tempo.sortedBy { it.tick }.distinctBy { it.tick },
            meterMap = meter.sortedBy { it.tick }.distinctBy { it.tick },
            tracks = tracks.filter { it.notes.isNotEmpty() },
        )
    }

    private fun parseTrack(
        r: Reader, end: Int, index: Int, ppq: Int,
        tempo: MutableList<TempoChange>, meter: MutableList<MeterChange>,
    ): MidiTrack {
        var tick = 0L
        var status = 0
        var name = ""
        val channels = linkedSetOf<Int>()
        val programs = mutableListOf<Int>()
        val notes = mutableListOf<Note>()
        val open = HashMap<Int, Pair<Long, Int>>() // (channel shl 8 or key) -> (startTick, velocity)
        fun norm(t: Long) = t * Score.DEFAULT_PPQ / ppq

        fun noteOff(channel: Int, key: Int) {
            val (start, velocity) = open.remove(channel shl 8 or key) ?: return
            val duration = (norm(tick) - norm(start)).coerceAtLeast(1)
            notes += Note(norm(start), duration, key, velocity)
        }

        while (r.position < end) {
            tick += r.varLen()
            var b = r.uint8()
            if (b < 0x80) {
                if (status == 0) throw MidiFormatException("Ungültiger Status in Spur $index")
                r.position--
                b = status
            }
            when {
                b == 0xFF -> {
                    val type = r.uint8()
                    val length = r.varLen()
                    val start = r.position
                    when (type) {
                        0x03 -> if (name.isEmpty()) name = r.string(length)
                        0x51 -> if (length == 3) {
                            val micros = r.int24()
                            tempo += TempoChange(norm(tick), (60_000_000.0 / micros * 100).toLong() / 100.0)
                        }
                        0x58 -> if (length >= 2) {
                            val num = r.uint8()
                            val den = 1 shl r.uint8()
                            meter += MeterChange(norm(tick), num, den)
                        }
                    }
                    r.position = start + length
                }
                b == 0xF0 || b == 0xF7 -> r.skip(r.varLen())
                b >= 0xF0 -> {} // system real-time, no data
                else -> {
                    status = b
                    val channel = b and 0x0F
                    when (b and 0xF0) {
                        0x90 -> {
                            val key = r.uint8(); val velocity = r.uint8()
                            channels += channel
                            if (velocity == 0) noteOff(channel, key) else {
                                noteOff(channel, key)
                                open[channel shl 8 or key] = tick to velocity
                            }
                        }
                        0x80 -> { val key = r.uint8(); r.uint8(); noteOff(channel, key) }
                        0xC0 -> { val program = r.uint8(); if (program !in programs) programs += program }
                        0xA0, 0xB0, 0xE0 -> r.skip(2)
                        0xD0 -> r.skip(1)
                    }
                }
            }
        }
        // Notes still sounding at the end of the track get a quarter note.
        for ((k, v) in open) notes += Note(norm(v.first), Score.DEFAULT_PPQ.toLong(), k and 0xFF, v.second)
        return MidiTrack(index, name.trim(), channels, programs, notes.sortedWith(compareBy({ it.tick }, { it.key })))
    }

    /** Format-1 file: one conductor track plus one track per part. */
    fun write(score: Score): ByteArray {
        val out = Writer()
        out.chunk("MThd") {
            int16(1); int16(score.parts.size + 1); int16(score.ppq)
        }
        out.chunk("MTrk") {
            val events = mutableListOf<Pair<Long, ByteArray>>()
            events += 0L to meta(0x03, "Setlist".encodeToByteArray())
            score.tempoMap.forEach { t ->
                val micros = (60_000_000.0 / t.bpm).toInt()
                events += t.tick to meta(0x51, byteArrayOf((micros shr 16).toByte(), (micros shr 8).toByte(), micros.toByte()))
            }
            score.meterMap.forEach { m ->
                var denPow = 0; var d = m.denominator; while (d > 1) { d /= 2; denPow++ }
                events += m.tick to meta(0x58, byteArrayOf(m.numerator.toByte(), denPow.toByte(), 24, 8))
            }
            writeEvents(events)
        }
        var melodicChannel = 0
        score.parts.forEach { part ->
            val channel = if (part.instrument == Instrument.DRUMS) 9 else melodicChannel++.let { if (it >= 9) it + 1 else it }
            out.chunk("MTrk") {
                val events = mutableListOf<Pair<Long, ByteArray>>()
                events += 0L to meta(0x03, part.name.encodeToByteArray())
                events += 0L to byteArrayOf((0xC0 or channel).toByte(), part.program.toByte())
                part.notes.forEach { n ->
                    events += n.tick to byteArrayOf((0x90 or channel).toByte(), n.key.toByte(), n.velocity.coerceIn(1, 127).toByte())
                    events += (n.tick + n.duration) to byteArrayOf((0x80 or channel).toByte(), n.key.toByte(), 0)
                }
                writeEvents(events)
            }
        }
        return out.toByteArray()
    }

    private fun meta(type: Int, data: ByteArray) = byteArrayOf(0xFF.toByte(), type.toByte()) + varLen(data.size) + data

    private fun Writer.writeEvents(events: List<Pair<Long, ByteArray>>) {
        // Note-offs before note-ons at the same tick, so re-struck notes don't get cut off.
        val sorted = events.sortedWith(compareBy({ it.first }, { if ((it.second[0].toInt() and 0xF0) == 0x80) 0 else 1 }))
        var last = 0L
        for ((tick, data) in sorted) {
            bytes(varLen((tick - last).toInt()))
            bytes(data)
            last = tick
        }
        bytes(byteArrayOf(0, 0xFF.toByte(), 0x2F, 0))
    }

    private fun varLen(value: Int): ByteArray {
        var v = value
        val buffer = ArrayDeque<Byte>()
        buffer.addFirst((v and 0x7F).toByte())
        v = v ushr 7
        while (v > 0) { buffer.addFirst((v and 0x7F or 0x80).toByte()); v = v ushr 7 }
        return buffer.toByteArray()
    }

    private class Reader(val bytes: ByteArray) {
        var position = 0
        fun remaining() = bytes.size - position
        fun uint8(): Int { if (position >= bytes.size) throw MidiFormatException("Datei unvollständig"); return bytes[position++].toInt() and 0xFF }
        fun int16() = (uint8() shl 8) or uint8()
        fun int24() = (int16() shl 8) or uint8()
        fun int32() = (int16() shl 16) or int16()
        fun skip(n: Int) { position += n }
        fun chunkId() = String(CharArray(4) { uint8().toChar() })
        fun string(length: Int) = bytes.copyOfRange(position, position + length).also { position += length }.decodeToString()
        fun varLen(): Int { var v = 0; while (true) { val b = uint8(); v = (v shl 7) or (b and 0x7F); if (b and 0x80 == 0) return v } }
    }

    private class Writer {
        private val data = mutableListOf<Byte>()
        fun int16(v: Int) { data += (v shr 8).toByte(); data += v.toByte() }
        fun int32(v: Int) { int16(v shr 16); int16(v and 0xFFFF) }
        fun bytes(b: ByteArray) { b.forEach { data += it } }
        fun chunk(id: String, body: Writer.() -> Unit) {
            val w = Writer().apply(body)
            bytes(id.encodeToByteArray()); int32(w.data.size); data += w.data
        }
        fun toByteArray() = data.toByteArray()
    }
}
