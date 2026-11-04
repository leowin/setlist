package at.zentriert.setlist

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import at.zentriert.setlist.audio.AudioEngine
import at.zentriert.setlist.audio.MetronomePosition
import at.zentriert.setlist.audio.ScorePlayback
import at.zentriert.setlist.audio.TransportConfig
import at.zentriert.setlist.edit.LatencyCalibration
import at.zentriert.setlist.edit.LoopBars
import at.zentriert.setlist.edit.NoteValue
import at.zentriert.setlist.edit.PartEdit
import at.zentriert.setlist.edit.Quantize
import at.zentriert.setlist.edit.RecordedPress
import at.zentriert.setlist.edit.Recording
import at.zentriert.setlist.midi.MidiFile
import at.zentriert.setlist.midi.Smf
import at.zentriert.setlist.res.Res
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    class Import(val state: ImportState) : Screen
    data class Edit(val setlistId: String) : Screen
    data class Perform(val setlistId: String) : Screen
    data object Library : Screen
    /** Notes of one song; setlistId is null when opened from the library. */
    data class Notes(val setlistId: String?, val songId: String) : Screen
    class MidiImport(val state: MidiImportState) : Screen
    data class Editor(val setlistId: String?, val songId: String, val instrument: Instrument) : Screen
}

/** Where an imported score should go. */
data class ImportTarget(val setlistId: String?, val songId: String?)

class MidiImportState(val file: MidiFile, val fileName: String, val target: ImportTarget) {
    val assignments = mutableStateMapOf<Int, Instrument?>().apply {
        file.tracks.forEach { put(it.index, it.suggestedInstrument()) }
    }
    var useTempo by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    val chosenCount get() = assignments.values.count { it != null }
}

class ImportState(text: String, name: String) {
    var name by mutableStateOf(name)
    var text by mutableStateOf(text)
    var editingText by mutableStateOf(text.isBlank())
    val lines: List<String> by derivedStateOf { SetlistParser.lines(this.text) }
    var cuts by mutableStateOf(SetlistParser.suggestCuts(lines))

    fun autoCut() { cuts = SetlistParser.suggestCuts(lines) }
    fun cutEveryLine() { cuts = lines.indices.filter { lines[it].isNotBlank() }.toSet() }
    fun clearCuts() { cuts = emptySet() }
    fun toggleCut(i: Int) { cuts = if (i in cuts) cuts - i else cuts + i }
    fun songs() = SetlistParser.split(lines, cuts)
}

/** Keys a stage controller may send: arrow/page keys of Bluetooth page turners, volume buttons … */
enum class PerformKey { NEXT, PREVIOUS }

/** Tempo and meter as edited in the song dialog. */
data class TempoEdit(val bpm: Double, val numerator: Int, val denominator: Int) {
    val label get() = "${bpm.toBpmText()} · $numerator/$denominator"

    companion object {
        val DEFAULT = TempoEdit(120.0, 4, 4)
        fun of(score: Score?) = score?.let { TempoEdit(it.bpm, it.meter.numerator, it.meter.denominator) }
    }
}

class MainViewModel(
    private val repo: SetlistRepository,
    private val settingsStore: SettingsStore,
    private val audio: AudioEngine,
) : ViewModel() {
    val scores = repo.scores
    val data = repo.data

    /** The SoundFont ships as a resource and is handed to the synth once per process. */
    var soundFontReady by mutableStateOf(audio.soundFontLoaded)
        private set

    init {
        if (!audio.soundFontLoaded) viewModelScope.launch {
            soundFontReady = try { audio.loadSoundFont(Res.readBytes("files/setlist.sf2")) } catch (e: Exception) { false }
        }
    }

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set

    var settings by mutableStateOf(settingsStore.load())
        private set

    var performIndex by mutableIntStateOf(0)
        private set
    var blackout by mutableStateOf(false)
        private set

    fun setlist(id: String) = repo.get(id)

    // --- navigation ---

    fun goHome() { leavePerformance(); screen = Screen.Home }
    fun openLibrary() { screen = Screen.Library }
    fun edit(id: String) { leavePerformance(); screen = Screen.Edit(id) }

    fun perform(id: String, startAt: Int = 0) {
        performIndex = startAt
        blackout = false
        performBpm = null
        screen = Screen.Perform(id)
    }

    private fun leavePerformance() {
        if (screen is Screen.Perform || screen is Screen.Notes || screen is Screen.Editor) {
            if (recording) recording = false
            stopMetronome()
            stopPlayback()
            audio.suspendOutput()
        }
    }

    fun openNotes(setlistId: String?, songId: String) { leavePerformance(); screen = Screen.Notes(setlistId, songId) }

    /** Back from the notes screen returns to where it was opened. */
    fun closeNotes(setlistId: String?) = if (setlistId != null) edit(setlistId) else openLibrary()

    fun findSong(setlistId: String?, songId: String): Song? =
        if (setlistId != null) repo.get(setlistId)?.songs?.firstOrNull { it.id == songId }
        else repo.data.value.library.firstOrNull { it.id == songId }

    fun startImport(text: String, name: String) {
        screen = Screen.Import(ImportState(text, name))
    }

    // --- import ---

    fun finishImport(state: ImportState) {
        val name = state.name.trim().ifEmpty {
            "Setlist " + todayLabel()
        }
        // Songs without instructions get them from the library, if known there.
        val songs = state.songs().map { song ->
            val known = repo.findInLibrary(song.title)
            song.copy(
                notes = if (song.notes.isNotBlank()) song.notes else known?.notes.orEmpty(),
                scoreId = known?.scoreId,
            )
        }
        repo.remember(songs)
        val setlist = Setlist(name = name, songs = songs)
        repo.upsert(setlist)
        edit(setlist.id)
    }

    // --- editing ---

    private fun change(id: String, transform: (Setlist) -> Setlist) {
        repo.get(id)?.let { repo.upsert(transform(it)) }
    }

    fun deleteSetlist(id: String) = repo.delete(id)

    fun rename(id: String, name: String) = change(id) { it.copy(name = name.trim().ifEmpty { it.name }) }

    fun saveSong(id: String, song: Song, tempo: TempoEdit? = null) {
        val saved = withTempo(song, tempo)
        change(id) { s ->
            val i = s.songs.indexOfFirst { it.id == song.id }
            s.copy(songs = if (i < 0) s.songs + saved else s.songs.toMutableList().also { it[i] = saved })
        }
        repo.remember(listOf(saved))
    }

    fun score(song: Song?): Score? = repo.score(song?.scoreId)

    /** Stores tempo/meter in the song's score, creating the score when the song has none yet. */
    private fun withTempo(song: Song, tempo: TempoEdit?): Song {
        if (tempo == null) return song
        val score = (repo.score(song.scoreId) ?: Score())
            .withTempo(tempo.bpm.coerceIn(Score.MIN_BPM, Score.MAX_BPM))
            .withMeter(tempo.numerator, tempo.denominator)
        repo.saveScore(score)
        return song.copy(scoreId = score.id)
    }

    /** Adds copies of library songs, so concert-specific edits don't change other setlists. */
    fun addFromLibrary(id: String, songs: List<Song>) = change(id) { s ->
        s.copy(songs = s.songs + songs.map { it.copy(id = newId()) })
    }

    // --- library ---

    fun saveLibrarySong(song: Song, tempo: TempoEdit? = null) = repo.saveLibrarySong(withTempo(song, tempo))
    fun deleteLibrarySong(songId: String) = repo.deleteLibrarySong(songId)

    fun deleteSong(id: String, songId: String) = change(id) { s ->
        s.copy(songs = s.songs.filterNot { it.id == songId })
    }

    fun moveSong(id: String, from: Int, delta: Int) = change(id) { s ->
        val to = from + delta
        if (from !in s.songs.indices || to !in s.songs.indices) return@change s
        s.copy(songs = s.songs.toMutableList().also { it.add(to, it.removeAt(from)) })
    }

    /** Appends the instructions of the following song to this one and removes the follower. */
    fun mergeWithNext(id: String, songId: String) = change(id) { s ->
        val i = s.songs.indexOfFirst { it.id == songId }
        if (i < 0 || i >= s.songs.lastIndex) return@change s
        val a = s.songs[i]
        val b = s.songs[i + 1]
        val notes = listOf(a.notes, b.title, b.notes).filter { it.isNotBlank() }.joinToString("\n")
        s.copy(songs = s.songs.toMutableList().apply {
            set(i, a.copy(notes = notes))
            removeAt(i + 1)
        })
    }

    // --- performance ---

    fun next(count: Int) { if (performIndex < count - 1) moveTo(performIndex + 1) }
    fun previous() { if (performIndex > 0) moveTo(performIndex - 1) }
    fun jumpTo(i: Int) = moveTo(i)

    private fun moveTo(i: Int) {
        stopMetronome()
        performBpm = null
        performIndex = i
    }

    // --- metronome ---

    /** Tempo changed with ± during the concert; not saved unless adopted. */
    var performBpm by mutableStateOf<Double?>(null)
        private set
    var metronomeOn by mutableStateOf(false)
        private set
    var metronomePosition by mutableStateOf<MetronomePosition?>(null)
        private set
    private var positionPoller: Job? = null
    private var metronomeNumerator = 4

    private fun applyTransport(config: TransportConfig) {
        metronomeNumerator = config.numerator
        audio.setTransport(config)
    }

    fun tempoFor(song: Song?): TempoEdit {
        val base = TempoEdit.of(score(song)) ?: TempoEdit.DEFAULT
        return performBpm?.let { base.copy(bpm = it) } ?: base
    }

    fun hasOwnTempo(song: Song?) = score(song) != null && performBpm == null

    fun transportFor(song: Song?): TransportConfig {
        val t = tempoFor(song)
        return TransportConfig(t.bpm, t.numerator, t.denominator, settings.subdivision, settings.countInBars)
    }

    fun toggleMetronome(song: Song?) = if (metronomeOn) stopMetronome() else startMetronome(song)

    fun startMetronome(song: Song?) {
        audio.setClickVolume(settings.clickVolume)
        applyTransport(transportFor(song))
        audio.startMetronome()
        metronomeOn = true
        positionPoller?.cancel()
        positionPoller = viewModelScope.launch {
            while (isActive) {
                metronomePosition = audio.positionBeats()?.let { MetronomePosition.fromBeats(it, metronomeNumerator) }
                delay(16)
            }
        }
    }

    fun stopMetronome() {
        if (!metronomeOn && positionPoller == null) return
        audio.stopMetronome()
        positionPoller?.cancel()
        positionPoller = null
        metronomeOn = false
        metronomePosition = null
    }

    /** ± during the concert; takes effect on the next beat while running. */
    fun nudgeTempo(song: Song?, delta: Double) {
        val bpm = (tempoFor(song).bpm + delta).coerceIn(Score.MIN_BPM, Score.MAX_BPM)
        performBpm = bpm
        if (metronomeOn) applyTransport(transportFor(song))
    }

    /** Tempo/meter edited from the concert view: saved into the song's score right away. */
    fun setTempo(setlistId: String, song: Song, tempo: TempoEdit) {
        saveSong(setlistId, song, tempo)
        performBpm = null
        if (metronomeOn) {
            applyTransport(TransportConfig(tempo.bpm, tempo.numerator, tempo.denominator, settings.subdivision, settings.countInBars))
        }
    }

    /** Saves the nudged tempo into the song's score (shared with the library). */
    fun adoptTempo(setlistId: String, song: Song) {
        val bpm = performBpm ?: return
        saveSong(setlistId, song, tempoFor(song).copy(bpm = bpm))
        performBpm = null
    }
    fun screenOff() { blackout = true }
    fun screenOn() { blackout = false }

    // --- score playback ---

    var playing by mutableStateOf(false)
        private set
    /** Current tick while playing (null in the count-in or when stopped). */
    var playbackTick by mutableStateOf<Long?>(null)
        private set
    private var playbackPoller: Job? = null
    private var playbackScore: Score? = null
    private var playbackFrom = 0L
    private var playbackOffset = 0.0

    /** Per-part gain (0..1) and mute, by instrument; solo wins over everything. */
    val partGain = mutableStateMapOf<Instrument, Float>()
    val partMuted = mutableStateMapOf<Instrument, Boolean>()
    var solo by mutableStateOf<Instrument?>(null)
        private set

    fun effectiveGain(instrument: Instrument): Float {
        val base = partGain[instrument] ?: 1f
        if (solo != null) return if (solo == instrument) base else 0f
        if (partMuted[instrument] == true) return 0f
        val me = settings.myInstrument
        return when (settings.mixMode) {
            MixMode.ALL -> base
            MixMode.ONLY_ME -> if (instrument == me) base else 0f
            MixMode.WITHOUT_ME -> if (instrument == me) 0f else base
        }
    }

    private fun applyMix(score: Score) {
        score.parts.forEachIndexed { i, part -> audio.setPartGain(i, effectiveGain(part.instrument)) }
    }

    fun setPartGain(score: Score, instrument: Instrument, gain: Float) { partGain[instrument] = gain; applyMix(score) }
    fun toggleMute(score: Score, instrument: Instrument) { partMuted[instrument] = partMuted[instrument] != true; applyMix(score) }
    fun toggleSolo(score: Score, instrument: Instrument) { solo = if (solo == instrument) null else instrument; applyMix(score) }
    fun setMixMode(score: Score?, mode: MixMode) { updateSettings { it.copy(mixMode = mode) }; score?.let(::applyMix) }
    fun setMyInstrument(score: Score?, instrument: Instrument?) { updateSettings { it.copy(myInstrument = instrument) }; score?.let(::applyMix) }

    fun playScore(score: Score, fromTick: Long = 0, withMetronome: Boolean = true, loop: LoopBars? = null) {
        stopPlayback()
        stopMetronome()
        score.parts.forEachIndexed { i, part ->
            audio.setPartPreset(i, part.program, part.instrument == Instrument.DRUMS)
        }
        applyMix(score)
        val transport = TransportConfig(score.bpm, score.meter.numerator, score.meter.denominator, settings.subdivision, if (withMetronome) settings.countInBars else 0)
        val offset = if (withMetronome) transport.countInBars * transport.numerator * transport.beatSeconds else 0.0
        audio.setSequence(ScorePlayback.events(score, fromTick, offset))
        if (loop != null) {
            val (a, b) = loop.ticks(score)
            val map = TempoMap(score)
            val origin = map.tickToMicros(fromTick)
            fun sec(t: Long) = (map.tickToMicros(t) - origin) / 1_000_000.0 + offset
            audio.setLoop(sec(maxOf(a, fromTick)), sec(b))
        } else audio.setLoop(0.0, 0.0)
        applyTransport(transport)
        audio.setClickVolume(settings.clickVolume)
        audio.play(leadSeconds = 0.15, withMetronome = withMetronome)
        playbackScore = score; playbackFrom = fromTick; playbackOffset = offset
        playing = true
        metronomeOn = withMetronome
        playbackPoller?.cancel()
        playbackPoller = viewModelScope.launch {
            while (isActive) {
                val pos = audio.positionSeconds()
                if (pos == null) {
                    if (!audio.playing && audio.positionBeats() == null) { finishPlayback(); break }
                } else {
                    playbackTick = ScorePlayback.tickAt(score, fromTick, offset, pos)
                }
                metronomePosition = audio.positionBeats()?.let { MetronomePosition.fromBeats(it, metronomeNumerator) }
                delay(16)
            }
        }
    }

    private fun finishPlayback() {
        playing = false
        playbackTick = null
        metronomeOn = false
        metronomePosition = null
        playbackPoller?.cancel()
        playbackPoller = null
    }

    fun stopPlayback() {
        if (!playing) return
        audio.stopPlayback()
        finishPlayback()
    }

    /** Live playing (pads/keys); while recording or in step input the press also becomes a note. */
    fun noteOn(score: Score, instrument: Instrument, key: Int, velocity: Int = 100) {
        val part = score.parts.indexOfFirst { it.instrument == instrument }
        if (part >= 0) { audio.setPartPreset(part, score.parts[part].program, instrument == Instrument.DRUMS); audio.noteOn(part, key, velocity) }
        when {
            recording && instrument == recordInstrument -> audio.positionSeconds()?.let { pos ->
                openPresses[key] = presses.size
                presses += RecordedPress(pos, null, key, velocity)
            }
            stepInput && !playing -> stepInsert(score, instrument, key, velocity)
        }
    }
    fun noteOff(score: Score, instrument: Instrument, key: Int) {
        val part = score.parts.indexOfFirst { it.instrument == instrument }
        if (part >= 0) audio.noteOff(part, key)
        if (recording) openPresses.remove(key)?.let { i ->
            val pos = audio.positionSeconds() ?: return
            presses[i] = presses[i].copy(offSeconds = pos)
        }
    }

    // --- editor ---

    var editSelection by mutableStateOf<Set<Note>>(emptySet())
    private val undoStack = ArrayDeque<Score>()
    private val redoStack = ArrayDeque<Score>()
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun noteValue() = NoteValue(settings.noteDivision, settings.noteTriplet)
    fun quantizeGrid(score: Score): Long? = if (settings.quantizeOn) NoteValue(settings.quantizeDivision).ticks(score.ppq) else null

    fun openEditor(setlistId: String?, song: Song, instrument: Instrument) {
        val score = ensurePart(ensureScore(setlistId, song), instrument)
        if (score.id != song.scoreId) {
            val updated = song.copy(scoreId = score.id)
            if (setlistId != null) saveSong(setlistId, updated) else repo.saveLibrarySong(updated)
        }
        undoStack.clear(); redoStack.clear(); editSelection = emptySet()
        leavePerformance()
        screen = Screen.Editor(setlistId, song.id, instrument)
    }

    private fun ensureScore(setlistId: String?, song: Song): Score =
        repo.score(song.scoreId) ?: Score().also { repo.saveScore(it) }

    private fun ensurePart(score: Score, instrument: Instrument): Score {
        if (score.parts.any { it.instrument == instrument }) return score
        val program = when (instrument) { Instrument.BASS -> 33; Instrument.GUITAR -> 27; Instrument.VOCALS -> 81; Instrument.DRUMS -> 0 }
        return score.copy(parts = score.parts + Part(instrument, program = program)).also { repo.saveScore(it) }
    }

    fun editScore(current: Score, transform: (Score) -> Score) {
        val next = transform(current)
        if (next == current) return
        undoStack.addLast(current)
        if (undoStack.size > 60) undoStack.removeFirst()
        redoStack.clear()
        repo.saveScore(next)
    }

    fun editPart(score: Score, instrument: Instrument, transform: (Part) -> Part) = editScore(score) { s ->
        s.copy(parts = s.parts.map { if (it.instrument == instrument) transform(it) else it })
    }

    fun undo(current: Score) {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(current); editSelection = emptySet(); repo.saveScore(previous)
    }
    fun redo(current: Score) {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(current); editSelection = emptySet(); repo.saveScore(next)
    }

    fun deleteSelection(score: Score, instrument: Instrument) {
        editPart(score, instrument) { PartEdit.remove(it, editSelection) }
        editSelection = emptySet()
    }
    fun moveSelection(score: Score, instrument: Instrument, deltaTicks: Long, deltaKeys: Int) {
        editPart(score, instrument) { PartEdit.move(it, editSelection, deltaTicks, deltaKeys) }
        editSelection = editSelection.map { it.copy(tick = it.tick + deltaTicks, key = it.key + deltaKeys) }.toSet()
    }
    fun resizeSelection(score: Score, instrument: Instrument, deltaTicks: Long) {
        val minimum = NoteValue(32).ticks(score.ppq)
        editPart(score, instrument) { PartEdit.resize(it, editSelection, deltaTicks, minimum) }
        editSelection = editSelection.map { it.copy(duration = (it.duration + deltaTicks).coerceAtLeast(minimum)) }.toSet()
    }
    fun quantizeSelection(score: Score, instrument: Instrument) {
        val grid = NoteValue(settings.quantizeDivision).ticks(score.ppq)
        val target = editSelection.ifEmpty { score.parts.first { it.instrument == instrument }.notes.toSet() }
        editPart(score, instrument) { p ->
            p.copy(notes = p.notes.map { if (it in target) Quantize.notes(listOf(it), grid, settings.quantizeStrength).first() else it })
        }
        editSelection = emptySet()
    }
    fun setDrumPad(score: Score, index: Int, key: Int) = editScore(score) { s ->
        s.copy(drumPads = s.drumPads.toMutableList().also { if (index in it.indices) it[index] = key })
    }

    // --- step input ---

    var stepInput by mutableStateOf(false)
    var cursorTick by mutableStateOf(0L)

    private fun stepInsert(score: Score, instrument: Instrument, key: Int, velocity: Int) {
        val length = noteValue().ticks(score.ppq)
        editPart(score, instrument) { PartEdit.add(it, Note(cursorTick, length, key, velocity)) }
        if (instrument != Instrument.DRUMS) cursorTick += length
    }
    fun stepAdvance(score: Score, steps: Int) {
        cursorTick = (cursorTick + steps * noteValue().ticks(score.ppq)).coerceAtLeast(0)
    }

    // --- recording ---

    var recording by mutableStateOf(false)
        private set
    var recordInstrument by mutableStateOf<Instrument?>(null)
        private set
    var loop by mutableStateOf<LoopBars?>(null)
    private val presses = mutableListOf<RecordedPress>()
    private val openPresses = HashMap<Int, Int>()

    /** Default when not calibrated: the engine's output latency plus a typical touch delay. */
    fun inputLatencySeconds(): Double =
        if (settings.inputLatencyMs >= 0) settings.inputLatencyMs / 1000.0 else audio.outputLatencyNanos() / 1e9 + 0.03

    fun startRecording(score: Score, instrument: Instrument, fromTick: Long) {
        presses.clear(); openPresses.clear()
        recordInstrument = instrument
        playScore(score, fromTick, withMetronome = true, loop = loop)
        recording = true
    }

    fun stopRecording(score: Score) {
        if (!recording) return
        val instrument = recordInstrument ?: return
        val from = playbackFrom; val offset = playbackOffset
        stopPlayback()
        recording = false
        if (presses.isEmpty()) return
        val grid = quantizeGrid(score)
        val notes = Recording.toNotes(
            presses.toList(), score, from, offset, inputLatencySeconds(), grid, settings.quantizeStrength,
            if (instrument == Instrument.DRUMS) NoteValue(16).ticks(score.ppq) else noteValue().ticks(score.ppq),
        )
        editPart(score, instrument) { part ->
            if (settings.recordReplace) {
                val (a, b) = loop?.ticks(score) ?: (notes.minOf { it.tick } to notes.maxOf { it.tick + it.duration })
                PartEdit.replaceRange(part, a, b, notes)
            } else notes.fold(part) { acc, n -> PartEdit.add(acc, n) }
        }
    }

    // --- latency calibration ---

    var calibrating by mutableStateOf(false)
        private set
    var calibrationTaps by mutableStateOf(0)
        private set
    private val calibrationPositions = mutableListOf<Double>()

    fun startCalibration() {
        calibrationPositions.clear(); calibrationTaps = 0
        applyTransport(TransportConfig(100.0, 4, 4, 1, 0))
        audio.setClickVolume(settings.clickVolume)
        audio.startMetronome()
        metronomeOn = true
        calibrating = true
    }
    fun calibrationTap() {
        audio.positionBeats()?.let { calibrationPositions += it; calibrationTaps = calibrationPositions.size }
    }
    /** Stores the result; returns it in ms or null when too few taps. */
    fun finishCalibration(): Int? {
        audio.stopMetronome(); metronomeOn = false; calibrating = false
        val seconds = LatencyCalibration.estimate(calibrationPositions.drop(2), 0.6) ?: return null
        val ms = (seconds * 1000).toInt().coerceIn(0, 400)
        updateSettings { it.copy(inputLatencyMs = ms) }
        return ms
    }

    // --- MIDI import ---

    var importError by mutableStateOf<String?>(null)

    fun startMidiImport(bytes: ByteArray, fileName: String, target: ImportTarget = ImportTarget(null, null)) {
        val file = try { Smf.parse(bytes) } catch (e: Exception) {
            importError = e.message ?: "Datei konnte nicht gelesen werden"
            return
        }
        if (file.tracks.isEmpty()) { importError = "Die Datei enthält keine Noten"; return }
        leavePerformance()
        screen = Screen.MidiImport(MidiImportState(file, fileName, target))
    }

    fun cancelMidiImport(state: MidiImportState) {
        val t = state.target
        if (t.songId != null) openNotes(t.setlistId, t.songId) else if (t.setlistId != null) edit(t.setlistId) else goHome()
    }

    fun finishMidiImport(state: MidiImportState) {
        val assignments = state.assignments.filterValues { it != null }.mapValues { it.value!! }
        if (assignments.isEmpty()) { state.error = "Mindestens eine Spur einem Instrument zuordnen"; return }
        val target = state.target
        val song = target.songId?.let { findSong(target.setlistId, it) }
        val base = repo.score(song?.scoreId)
        var score = state.file.toScore(assignments, base)
        if (!state.useTempo && base != null) score = score.copy(tempoMap = base.tempoMap, meterMap = base.meterMap)
        repo.saveScore(score)
        if (song != null) {
            val updated = song.copy(scoreId = score.id)
            if (target.setlistId != null) saveSong(target.setlistId, updated) else repo.saveLibrarySong(updated)
            openNotes(target.setlistId, song.id)
        } else {
            val title = state.fileName.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { "Importiert" }
            val newSong = Song(title = title, scoreId = score.id)
            if (target.setlistId != null) {
                saveSong(target.setlistId, newSong)
                openNotes(target.setlistId, newSong.id)
            } else {
                repo.remember(listOf(newSong))
                val stored = repo.findInLibrary(title) ?: newSong
                openNotes(null, stored.id)
            }
        }
    }

    fun updateSettings(transform: (PerformSettings) -> PerformSettings) {
        settings = transform(settings)
        settingsStore.save(settings)
        audio.setClickVolume(settings.clickVolume)
    }

    /** Metronome settings changed while it runs are applied immediately. */
    fun updateMetronomeSettings(song: Song?, transform: (PerformSettings) -> PerformSettings) {
        updateSettings(transform)
        if (metronomeOn) applyTransport(transportFor(song))
    }

    fun changeFontSize(delta: Float) = updateSettings {
        it.copy(fontSize = (it.fontSize + delta).coerceIn(PerformSettings.MIN_FONT, PerformSettings.MAX_FONT))
    }

    val inPerformance: Boolean get() = screen is Screen.Perform

    /** Hardware keys during a concert. While the screen is "off" any key just wakes it. */
    fun handleKey(key: PerformKey) {
        val current = screen as? Screen.Perform ?: return
        when {
            blackout -> screenOn()
            key == PerformKey.NEXT -> next(repo.get(current.setlistId)?.songs?.size ?: 0)
            else -> previous()
        }
    }
}
