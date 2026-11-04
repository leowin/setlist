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
import at.zentriert.setlist.band.Band
import at.zentriert.setlist.band.BandEvent
import at.zentriert.setlist.band.BandLink
import at.zentriert.setlist.band.BandSession
import at.zentriert.setlist.band.CloudSession
import at.zentriert.setlist.band.Msg
import at.zentriert.setlist.band.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import at.zentriert.setlist.sync.SharePayload
import at.zentriert.setlist.sync.SyncEngine
import at.zentriert.setlist.sync.SyncStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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
    data class Notation(val setlistId: String?, val songId: String) : Screen
    data object Band : Screen
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

private const val START_LEAD_NANOS = 1_500_000_000L

/** `https://host/x` → `wss://host/x/ws`; a ws(s) address is used as given. */
fun String.toWebSocketUrl(): String {
    val s = trim().trimEnd('/')
    if (s.isEmpty()) return s
    if (s.startsWith("ws://") || s.startsWith("wss://")) return s
    val base = s.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
    return (if (base.startsWith("ws")) base else "wss://$base") + "/ws"
}

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
    private val discovery: Discovery? = null,
) : ViewModel() {
    val scores = repo.scores
    val data = repo.data

    /** The SoundFont ships as a resource and is handed to the synth once per process. */
    var soundFontReady by mutableStateOf(audio.soundFontLoaded)
        private set

    init {
        settingsStore.load().let { repo.author = it.myName.ifBlank { it.deviceId.take(8) } }
        watchChanges()
        syncAll()
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
    fun openBand() { leavePerformance(); screen = Screen.Band }
    fun openLibrary() { screen = Screen.Library }
    fun edit(id: String) { leavePerformance(); screen = Screen.Edit(id) }

    fun perform(id: String, startAt: Int = 0) {
        performIndex = startAt
        blackout = false
        performBpm = null
        screen = Screen.Perform(id)
        if (bandRole == Role.HOST) {
            shareSetlist(id)
            band?.broadcast(Msg.Navigate(startAt))
        }
    }

    private fun leavePerformance() {
        if (screen is Screen.Perform || screen is Screen.Notes || screen is Screen.Editor || screen is Screen.Notation) {
            if (recording) recording = false
            stopMetronome()
            stopPlayback()
            audio.suspendOutput()
        }
    }

    fun openNotes(setlistId: String?, songId: String) { leavePerformance(); screen = Screen.Notes(setlistId, songId) }
    fun openNotation(setlistId: String?, songId: String) { leavePerformance(); screen = Screen.Notation(setlistId, songId) }

    /** Playback position in alphaTab ticks (960 per quarter), for the notation cursor. */
    fun notationCursor(score: Score): Long? = playbackTick?.let { it * 960 / score.ppq }

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
        val bandId = currentBandId
        val songs = state.songs().map { song ->
            val known = repo.findInLibrary(song.title, bandId)
            song.copy(
                notes = if (song.notes.isNotBlank()) song.notes else known?.notes.orEmpty(),
                scoreId = known?.scoreId,
            )
        }
        repo.remember(songs, bandId)
        val setlist = Setlist(name = name, songs = songs, bandId = bandId)
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
        repo.remember(listOf(saved), repo.get(id)?.bandId)
    }

    // --- bands ---

    val currentBandId: String? get() = settings.currentBandId?.takeIf { id -> repo.data.value.band(id) != null }
    val currentBand: Band? get() = repo.data.value.band(currentBandId)
    val bands: List<Band> get() = repo.data.value.bands

    fun selectBand(bandId: String?) = updateSettings { it.copy(currentBandId = bandId) }

    /** Sync state per band, for the band screen. */
    val syncStatus = mutableStateMapOf<String, SyncStatus>()
    private val engines = HashMap<String, SyncEngine>()
    private var syncJob: Job? = null

    /** A band can carry its own server; otherwise the one from the settings is used. */
    fun serverFor(band: Band) = band.serverUrl.ifBlank { settings.serverUrl }.trim()

    private fun engine(band: Band): SyncEngine? {
        val server = serverFor(band)
        if (server.isEmpty()) return null
        return engines.getOrPut(band.id) {
            SyncEngine(repo, band, server, repo.syncDir, myName = { bandName() }, onStatus = { s ->
                viewModelScope.launch(Dispatchers.Main) { syncStatus[band.id] = s }
            })
        }
    }

    fun syncBand(bandId: String) {
        val band = repo.data.value.band(bandId) ?: return
        val engine = engine(band) ?: return
        viewModelScope.launch(Dispatchers.Default) { engine.sync() }
    }

    fun syncAll() = repo.data.value.bands.forEach { syncBand(it.id) }

    /** The private library is empty or not; tells whether to offer "take everything into the band". */
    val hasPrivateData: Boolean get() = repo.data.value.let { it.setlists(null).isNotEmpty() || it.library(null).isNotEmpty() }

    fun createBand(name: String, adoptPrivate: Boolean) {
        val band = Band.create(name.ifBlank { "Band" }, "")
        addBand(band, adoptPrivate)
    }

    /** Joins from an invitation link or just its pasted code; false when the text isn't one. */
    fun joinBandFromInvite(text: String, adoptPrivate: Boolean): Boolean {
        val band = Band.fromInvite(text, settings.serverUrl) ?: return false
        addBand(band.copy(serverUrl = if (band.serverUrl == settings.serverUrl) "" else band.serverUrl), adoptPrivate)
        return true
    }

    private fun addBand(band: Band, adoptPrivate: Boolean) {
        repo.addBand(band)
        if (adoptPrivate) repo.adoptPrivate(band.id)
        selectBand(band.id)
        syncBand(band.id)
    }

    /** Leaves locally: the band's songs and setlists stay here as private copies. */
    fun leaveBandLocally(bandId: String) {
        engines.remove(bandId)
        syncStatus.remove(bandId)
        SyncEngine.forget(repo.syncDir, bandId)
        repo.removeBand(bandId)
        if (settings.currentBandId == bandId) selectBand(null)
    }

    /** Wipes the band's room on the server; the phones keep everything. */
    fun deleteBandOnServer(bandId: String, then: (String?) -> Unit) {
        val band = repo.data.value.band(bandId) ?: return
        val engine = engine(band) ?: return then("Kein Server eingetragen")
        viewModelScope.launch(Dispatchers.Default) {
            val error = runCatching { engine.deleteRoom() }.exceptionOrNull()?.message
            withContext(Dispatchers.Main) { then(error) }
        }
    }

    fun inviteLink(band: Band): String = band.inviteLink(serverFor(band))

    fun copySongToBand(songId: String, targetBandId: String?) { repo.copySong(songId, targetBandId) }
    fun copySetlistToBand(setlistId: String, targetBandId: String?) { repo.copySetlist(setlistId, targetBandId) }

    // --- share links & incoming links ---

    private val http by lazy { HttpClient(CIO) }
    val canShareLinks: Boolean get() = settings.serverUrl.isNotBlank()

    class ShareRequest(val title: String, val payload: SharePayload)
    var shareRequest by mutableStateOf<ShareRequest?>(null)
        private set
    /** A share link that was opened: shown for review before it lands in the library. */
    var incomingShare by mutableStateOf<SharePayload?>(null)
        private set
    var linkMessage by mutableStateOf<String?>(null)

    fun shareSongLink(songId: String) {
        val song = repo.data.value.library.firstOrNull { it.id == songId && !it.deleted } ?: return
        val scores = listOfNotNull(repo.score(song.scoreId)).associateBy { it.id }
        shareRequest = ShareRequest(song.title, SharePayload(songs = listOf(song.stripped()), scores = scores, from = bandName()))
    }

    fun shareSetlistLink(setlistId: String) {
        val setlist = repo.get(setlistId) ?: return
        val scores = setlist.songs.mapNotNull { repo.score(it.scoreId) }.associateBy { it.id }
        shareRequest = ShareRequest(setlist.name, SharePayload(setlistName = setlist.name, songs = setlist.songs.map { it.stripped() }, scores = scores, from = bandName()))
    }

    private fun Song.stripped() = copy(bandId = null, rev = 0, updatedAt = 0, updatedBy = "", deleted = false)

    fun cancelShare() { shareRequest = null }

    /** Encrypts the payload with a fresh key, stores it on the server and hands back the link (or an error). */
    fun uploadShare(ttlDays: Int, onDone: (link: String?, error: String?) -> Unit) {
        val request = shareRequest ?: return
        val server = settings.serverUrl.trimEnd('/')
        viewModelScope.launch(Dispatchers.Default) {
            val result = runCatching {
                val sealed = request.payload.seal()
                val response = http.post("$server/shares?ttl=$ttlDays") { setBody(sealed.bytes) }
                if (response.status != HttpStatusCode.Created) throw IllegalStateException("Server: ${response.status}")
                val id = Regex("\"id\":\"([^\"]+)\"").find(response.bodyAsText())?.groupValues?.get(1) ?: throw IllegalStateException("Antwort unverständlich")
                SharePayload.link(server, id, sealed.keyBase64)
            }
            withContext(Dispatchers.Main) {
                shareRequest = null
                onDone(result.getOrNull(), result.exceptionOrNull()?.let { it.message ?: it.toString() })
            }
        }
    }

    /** A link opened with the app: a band invitation or a shared song/setlist. */
    fun openLink(url: String) {
        val trimmed = url.trim()
        if (trimmed.contains("/join#") || Band.fromInvite(trimmed, settings.serverUrl) != null && !trimmed.contains("/s/")) {
            val band = Band.fromInvite(trimmed, settings.serverUrl) ?: return
            if (repo.data.value.band(band.id) != null) { selectBand(band.id); linkMessage = "Du bist schon in „${band.name}“."; goHome(); return }
            if (joinBandFromInvite(trimmed, adoptPrivate = false)) { linkMessage = "Willkommen in der Band „${band.name}“!"; openBand() }
            return
        }
        val (server, id, key) = SharePayload.parseLink(trimmed) ?: run { linkMessage = "Diesen Link kennt die App nicht."; return }
        viewModelScope.launch(Dispatchers.Default) {
            val result = runCatching {
                val response = http.get("$server/shares/$id/blob")
                if (response.status != HttpStatusCode.OK) throw IllegalStateException(if (response.status == HttpStatusCode.NotFound) "Der Link ist abgelaufen oder wurde gelöscht." else "Server: ${response.status}")
                SharePayload.open(response.bodyAsBytes(), key) ?: throw IllegalStateException("Der Schlüssel im Link passt nicht.")
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { incomingShare = it }.onFailure { linkMessage = it.message ?: it.toString() }
            }
        }
    }

    fun dismissIncomingShare() { incomingShare = null }

    /** Puts the shared songs (and setlist) into the chosen band's library. */
    fun acceptShare(payload: SharePayload, bandId: String?) {
        val scoreIds = HashMap<String, String>()
        payload.scores.values.forEach { score ->
            val fresh = score.copy(id = newId(), rev = 0, updatedAt = 0, updatedBy = "")
            repo.saveScore(fresh)
            scoreIds[score.id] = fresh.id
        }
        val songs = payload.songs.map { s -> s.stripped().copy(id = newId(), scoreId = s.scoreId?.let { scoreIds[it] }) }
        repo.remember(songs, bandId)
        if (payload.setlistName != null) {
            val setlist = Setlist(name = payload.setlistName, songs = songs, bandId = bandId)
            repo.upsert(setlist)
            selectBand(bandId)
            incomingShare = null
            edit(setlist.id)
        } else {
            selectBand(bandId)
            incomingShare = null
            openLibrary()
        }
    }

    /** Local changes are pushed a moment after the last one. */
    private fun watchChanges() {
        syncJob = viewModelScope.launch {
            repo.changes.collectLatest {
                delay(2000)
                syncAll()
            }
        }
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

    fun saveLibrarySong(song: Song, tempo: TempoEdit? = null) =
        repo.saveLibrarySong(withTempo(song, tempo).let { if (repo.data.value.library.none { l -> l.id == it.id }) it.copy(bandId = currentBandId) else it })
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

    private fun moveTo(i: Int, fromHost: Boolean = false) {
        stopMetronome()
        stopPlayback()
        performBpm = null
        startTick = 0
        performIndex = i
        if (bandRole == Role.HOST && !fromHost) band?.broadcast(Msg.Navigate(i))
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
        if (bandRole == Role.HOST) {
            val t = transportFor(song)
            val at = referenceNow(START_LEAD_NANOS)
            band?.broadcast(Msg.Start(at, null, 0, t.bpm, t.numerator, t.denominator, t.subdivision, t.countInBars, true))
            audio.playAt(hostToLocal(at), withMetronome = true, metronomeOnly = true)
        } else audio.startMetronome()
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
        if (bandRole == Role.HOST && metronomeOn) band?.broadcast(Msg.Stop(referenceNow(0)))
        audio.stopMetronome()
        positionPoller?.cancel()
        positionPoller = null
        metronomeOn = false
        metronomePosition = null
    }

    /** ± during the concert; takes effect on the next beat (metronome) or restarts the notes at the new tempo. */
    fun nudgeTempo(song: Song?, delta: Double) {
        val bpm = (tempoFor(song).bpm + delta).coerceIn(Score.MIN_BPM, Score.MAX_BPM)
        performBpm = bpm
        if (playing) {
            val score = score(song) ?: return
            pausePlayback()
            playFromCursor(score)
        } else if (metronomeOn) applyTransport(transportFor(song))
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

    /** Mixer values live in the score (per song); solo wins over everything. */
    fun effectiveGain(score: Score, instrument: Instrument): Float {
        val m = score.mixOf(instrument)
        val base = m.gain
        if (score.solo != null) return if (score.solo == instrument) base else 0f
        if (m.muted) return 0f
        val me = settings.myInstrument
        return when (settings.mixMode) {
            MixMode.ALL -> base
            MixMode.ONLY_ME -> if (instrument == me) base else 0f
            MixMode.WITHOUT_ME -> if (instrument == me) 0f else base
        }
    }

    private fun applyMix(score: Score) {
        score.parts.forEachIndexed { i, part -> audio.setPartGain(i, effectiveGain(score, part.instrument)) }
    }

    private fun saveMix(score: Score) { repo.saveScore(score); applyMix(score) }
    fun setPartGain(score: Score, instrument: Instrument, gain: Float) =
        saveMix(score.copy(mix = score.mix + (instrument to score.mixOf(instrument).copy(gain = gain))))
    fun toggleMute(score: Score, instrument: Instrument) =
        saveMix(score.copy(mix = score.mix + (instrument to score.mixOf(instrument).let { it.copy(muted = !it.muted) })))
    fun toggleSolo(score: Score, instrument: Instrument) =
        saveMix(score.copy(solo = if (score.solo == instrument) null else instrument))
    fun setMixMode(score: Score?, mode: MixMode) { updateSettings { it.copy(mixMode = mode) }; score?.let(::applyMix) }
    fun setMyInstrument(score: Score?, instrument: Instrument?) { updateSettings { it.copy(myInstrument = instrument) }; score?.let(::applyMix) }

    fun playScore(score: Score, fromTick: Long = 0, withMetronome: Boolean = true, loop: LoopBars? = null, atNanos: Long? = null, countIn: Boolean = true) {
        stopPlayback()
        stopMetronome()
        // Metronome phase: where inside the bar the notes start (count-in only from a bar line).
        val beatTicks = score.ppq * 4L / score.meter.denominator
        val barTicks = beatTicks * score.meter.numerator
        val phaseBeats = (fromTick % barTicks).toDouble() / beatTicks
        val useCountIn = countIn && withMetronome && phaseBeats == 0.0
        score.parts.forEachIndexed { i, part ->
            audio.setPartPreset(i, part.program, part.instrument == Instrument.DRUMS)
        }
        applyMix(score)
        val transport = TransportConfig(score.bpm, score.meter.numerator, score.meter.denominator, settings.subdivision, if (useCountIn) settings.countInBars else 0)
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
        if (atNanos != null) audio.playAt(atNanos, withMetronome, metronomePhaseBeats = phaseBeats)
        else audio.play(leadSeconds = 0.15, withMetronome = withMetronome, metronomePhaseBeats = phaseBeats)
        playbackScore = score; playbackFrom = fromTick; playbackOffset = offset
        playing = true
        metronomeOn = withMetronome
        playbackPoller?.cancel()
        playbackPoller = viewModelScope.launch {
            // The audio thread picks the start up on its next block; don't mistake that gap for "finished".
            val startedAt = currentTimeMillis()
            var seen = false
            while (isActive) {
                val pos = audio.positionSeconds()
                if (pos == null) {
                    val engineIdle = !audio.playing && audio.positionBeats() == null
                    if (engineIdle && (seen || currentTimeMillis() - startedAt > 1500 + (atNanos?.let { (it - monotonicNanos()) / 1_000_000 } ?: 0))) { finishPlayback(); break }
                } else {
                    seen = true
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
        if (bandRole == Role.HOST && screen is Screen.Perform) band?.broadcast(Msg.Stop(referenceNow(0)))
        audio.stopPlayback()
        finishPlayback()
    }

    /** Where play starts (and where a paused playback resumes), in ticks. */
    var startTick by mutableStateOf(0L)

    fun pausePlayback() {
        startTick = playbackTick ?: playbackFrom
        stopPlayback()
    }

    /** In the concert view the ± tempo applies to the notes as well. */
    private fun withPerformTempo(score: Score): Score =
        if (screen is Screen.Perform) performBpm?.let { score.withTempo(it) } ?: score else score

    /** Play from the cursor; a tap on the notation sets the cursor and, while playing, jumps there. */
    fun playFromCursor(original: Score, withMetronome: Boolean = true, countIn: Boolean = startTick == 0L) {
        val score = withPerformTempo(original)
        val from = startTick.coerceIn(0, ScorePlayback.lastTick(score))
        if (bandRole == Role.HOST && screen is Screen.Perform) {
            val at = referenceNow(START_LEAD_NANOS)
            band?.broadcast(Msg.Start(at, score.id, from, score.bpm, score.meter.numerator, score.meter.denominator, settings.subdivision, if (countIn) settings.countInBars else 0, withMetronome))
            playScore(score, from, withMetronome, atNanos = hostToLocal(at), countIn = countIn)
        } else playScore(score, from, withMetronome, countIn = countIn)
    }

    /** A tap on the notation moves the cursor; while playing it continues right there, without count-in. */
    fun jumpTo(score: Score, tick: Long) {
        startTick = tick.coerceAtLeast(0)
        if (playing) playFromCursor(score, countIn = false)
    }

    /** Concert view: play the current song's notes for everyone (host) or just here. */
    fun performPlay(song: Song?) {
        val score = score(song) ?: return
        if (score.parts.none { it.notes.isNotEmpty() }) return startMetronome(song)
        playFromCursor(score)
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
        return score.copy(parts = score.parts + Part(instrument, program = instrument.defaultProgram)).also { repo.saveScore(it) }
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
                repo.remember(listOf(newSong), currentBandId)
                val stored = repo.findInLibrary(title, currentBandId) ?: newSong
                openNotes(null, stored.id)
            }
        }
    }

    // --- band (WLAN sync) ---

    private var band: BandLink? = null
    var bandRole by mutableStateOf<Role?>(null)
        private set
    var bandStatus by mutableStateOf("")
        private set
    var bandPeers by mutableStateOf<List<String>>(emptyList())
        private set
    val bandHosts = mutableStateMapOf<String, Pair<String, Int>>()  // name -> (host, port)
    var clockInfo by mutableStateOf<BandEvent.Clock?>(null)
        private set
    var syncTest by mutableStateOf(false)
        private set
    private var bandSetlistId: String? = null

    fun bandName(): String = settings.myName.ifBlank { "Setlist" }

    fun startDiscovery() {
        bandHosts.clear()
        discovery?.discover(
            onFound = { name, host, port -> viewModelScope.launch { bandHosts[name] = host to port } },
            onLost = { name -> viewModelScope.launch { bandHosts.remove(name) } },
        )
    }
    fun stopDiscovery() = discovery?.stopDiscovery()

    fun hostBand() {
        leaveBand()
        val session = BandSession(bandName(), ::onBandEvent)
        band = session
        bandRole = Role.HOST
        bandStatus = "Starte …"
        session.host()
        discovery?.advertise(bandName(), at.zentriert.setlist.band.DEFAULT_PORT)
        (screen as? Screen.Perform)?.let { shareSetlist(it.setlistId) }
    }

    fun joinBand(host: String, port: Int = at.zentriert.setlist.band.DEFAULT_PORT) {
        leaveBand()
        val session = BandSession(bandName(), ::onBandEvent)
        band = session
        bandRole = Role.MEMBER
        session.join(host, port, settings.myInstrument)
    }

    /** Band over the internet via the server; the first to host the band's room leads it. */
    fun joinCloud(asHost: Boolean) {
        val myBand = currentBand
        val url = (myBand?.let { serverFor(it) } ?: settings.serverUrl).trim().toWebSocketUrl()
        if (myBand == null || url.isEmpty()) { bandStatus = "Zuerst eine Band gründen oder beitreten (und Server eintragen)"; return }
        leaveBand()
        val keys = myBand.keys()
        val session = CloudSession(url, myBand.id, bandName(), asHost, settings.myInstrument, joinAuth = { ts ->
            at.zentriert.setlist.sync.Crypto.hex(at.zentriert.setlist.sync.Crypto.hmac(keys.authKey, at.zentriert.setlist.sync.BandKeys.signedText(ts, "JOIN", myBand.id, ByteArray(0)).encodeToByteArray()))
        }) { event ->
            if (event is BandEvent.Status) viewModelScope.launch(Dispatchers.Main) { bandRole = (band as? CloudSession)?.role }
            onBandEvent(event)
        }
        band = session
        bandRole = if (asHost) Role.HOST else Role.MEMBER
        bandStatus = "Verbinde …"
        session.connect()
        if (asHost) (screen as? Screen.Perform)?.let { shareSetlist(it.setlistId) }
    }

    fun leaveBand() {
        if (syncTest) stopSyncTest()
        discovery?.stopAdvertising()
        band?.close()
        band = null
        bandRole = null
        bandPeers = emptyList()
        bandStatus = ""
        clockInfo = null
    }

    private fun shareSetlist(setlistId: String) {
        val setlist = repo.get(setlistId) ?: return
        val scores = setlist.songs.mapNotNull { s -> repo.score(s.scoreId)?.let { it.id to it } }.toMap()
        band?.broadcast(Msg.SetlistSync(setlist, scores))
    }

    /** Events arrive on the session's thread; state changes happen on Main. */
    private fun onBandEvent(event: BandEvent) {
        viewModelScope.launch(Dispatchers.Main) {
            when (event) {
                is BandEvent.Status -> bandStatus = event.text
                is BandEvent.Peers -> bandPeers = event.names
                is BandEvent.Clock -> clockInfo = event
                is BandEvent.Ended -> { bandStatus = event.reason; if (bandRole == Role.MEMBER || band is CloudSession) { band = null; bandRole = null; clockInfo = null } }
                is BandEvent.Message -> handleBandMessage(event.msg)
            }
        }
    }

    /** Reference clock (host over WLAN, server in the cloud) [leadNanos] from now. */
    private fun referenceNow(leadNanos: Long): Long = (band?.clock?.toHost(monotonicNanos()) ?: monotonicNanos()) + leadNanos

    private fun hostToLocal(hostNanos: Long): Long =
        (band?.clock?.toLocal(hostNanos) ?: hostNanos) + settings.syncOffsetMs * 1_000_000L

    private fun handleBandMessage(msg: Msg) {
        when (msg) {
            is Msg.SetlistSync -> {
                msg.scores.values.forEach { repo.saveScore(it) }
                // The host's setlist shows up in the band it belongs to, or in my current one if I'm not in that band.
                val bandId = msg.setlist.bandId?.takeIf { repo.data.value.band(it) != null } ?: currentBandId
                repo.upsert(msg.setlist.copy(bandId = bandId))
                bandSetlistId = msg.setlist.id
                if (screen !is Screen.Band || true) {
                    performIndex = 0; blackout = false; performBpm = null
                    screen = Screen.Perform(msg.setlist.id)
                }
            }
            is Msg.Navigate -> { if (screen !is Screen.Perform) bandSetlistId?.let { screen = Screen.Perform(it) }; moveTo(msg.index, fromHost = true) }
            is Msg.Start -> {
                val at = hostToLocal(msg.atHostNanos)
                val score = msg.scoreId?.let { repo.score(it) }
                if (score != null) {
                    playScore(score, msg.fromTick, msg.withMetronome, atNanos = at, countIn = msg.countInBars > 0)
                } else {
                    stopPlayback(); stopMetronome()
                    audio.setClickVolume(settings.clickVolume)
                    applyTransport(TransportConfig(msg.bpm, msg.numerator, msg.denominator, msg.subdivision, msg.countInBars))
                    audio.playAt(at, withMetronome = true, metronomeOnly = true)
                    metronomeOn = true
                    positionPoller?.cancel()
                    positionPoller = viewModelScope.launch {
                        while (isActive) {
                            metronomePosition = audio.positionBeats()?.let { MetronomePosition.fromBeats(it, metronomeNumerator) }
                            delay(16)
                        }
                    }
                }
            }
            is Msg.Stop -> { audio.stopPlayback(); finishPlayback(); audio.stopMetronome(); positionPoller?.cancel(); positionPoller = null; metronomeOn = false; metronomePosition = null }
            is Msg.SyncTest -> if (msg.on) runSyncTest() else stopSyncTest()
            is Msg.Changed -> currentBandId?.let { syncBand(it) }
            else -> {}
        }
    }

    /** Everyone clicks and flashes on the host's whole seconds – a quick check by ear and eye. */
    fun toggleSyncTest() {
        if (syncTest) { if (bandRole == Role.HOST) band?.broadcast(Msg.SyncTest(false)); stopSyncTest() }
        else { if (bandRole == Role.HOST) band?.broadcast(Msg.SyncTest(true)); runSyncTest() }
    }

    private fun runSyncTest() {
        stopPlayback(); stopMetronome()
        // Start on the next whole host second + 2 s; 60 bpm = one click per second from then on.
        val hostNow = band?.clock?.toHost(monotonicNanos()) ?: monotonicNanos()
        val hostStart = (hostNow / 1_000_000_000L + 2) * 1_000_000_000L
        applyTransport(TransportConfig(60.0, 4, 4, 1, 0))
        audio.setClickVolume(settings.clickVolume)
        audio.playAt(hostToLocal(hostStart), withMetronome = true, metronomeOnly = true)
        syncTest = true
        metronomeOn = true
        positionPoller?.cancel()
        positionPoller = viewModelScope.launch {
            while (isActive) {
                metronomePosition = audio.positionBeats()?.let { MetronomePosition.fromBeats(it, 4) }
                delay(16)
            }
        }
    }

    private fun stopSyncTest() {
        syncTest = false
        audio.stopMetronome()
        positionPoller?.cancel(); positionPoller = null
        metronomeOn = false; metronomePosition = null
    }

    override fun onCleared() {
        leaveBand()
        stopDiscovery()
    }

    fun updateSettings(transform: (PerformSettings) -> PerformSettings) {
        settings = transform(settings)
        settingsStore.save(settings)
        audio.setClickVolume(settings.clickVolume)
        repo.author = settings.myName.ifBlank { settings.deviceId.take(8) }
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
