package at.zentriert.setlist.practice

import at.zentriert.setlist.currentTimeMillis
import at.zentriert.setlist.newId
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One heard hit as stored with a take. */
@Serializable
data class HitRecord(val seconds: Double, val deviationMs: Double? = null, val expectedKey: Int? = null, val playedKey: Int? = null, val expectedTick: Long? = null, val cents: Float? = null)

/** One practice take of a song; private to this phone. */
@Serializable
data class PracticeSession(
    val id: String = newId(),
    val songId: String,
    val date: Long = currentTimeMillis(),
    /** "score" (against the notes) or "grid" (against the metronome grid). */
    val mode: String,
    val instrument: String,
    val bpm: Double,
    val windowMs: Double,
    val summary: RatingSummary,
    val bars: List<BarRating>,
    val hits: List<HitRecord>,
)

/**
 * Calibration kept per phone: drum pad recordings, the partial profile of each melodic instrument
 * (how strong its overtones are, from the reference notes played) and the measured microphone delay.
 */
@Serializable
data class CalibrationData(
    val samples: Map<Int, List<List<Float>>> = emptyMap(),
    val micOffsetMeasuredMs: Int? = null,
    val profiles: Map<String, List<Float>> = emptyMap(),
    /** Reference notes already learned per instrument (MIDI numbers). */
    val learnedNotes: Map<String, List<Int>> = emptyMap(),
) {
    fun count(cls: Int) = samples[cls]?.size ?: 0
    fun add(cls: Int, features: FloatArray) = copy(samples = samples + (cls to (samples[cls].orEmpty() + listOf(features.toList()))))
    fun clear(cls: Int) = copy(samples = samples - cls)
    fun withProfile(instrument: String, profile: FloatArray, midi: Int) = copy(
        profiles = profiles + (instrument to profile.toList()),
        learnedNotes = learnedNotes + (instrument to (learnedNotes[instrument].orEmpty() + midi).distinct()),
    )
    fun clearProfile(instrument: String) = copy(profiles = profiles - instrument, learnedNotes = learnedNotes - instrument)
}

/** practice/<songId>.json (takes, newest last) and practice/calibration.json. */
class PracticeStore(private val dir: Path) {
    private val json = Json { ignoreUnknownKeys = true }

    fun sessions(songId: String): List<PracticeSession> = read(Path(dir, "$songId.json")) { json.decodeFromString<List<PracticeSession>>(it) } ?: emptyList()

    fun append(session: PracticeSession) {
        val all = (sessions(session.songId) + session).takeLast(200)
        write(Path(dir, "${session.songId}.json"), json.encodeToString(all))
    }

    fun calibration(): CalibrationData = read(Path(dir, "calibration.json")) { json.decodeFromString<CalibrationData>(it) } ?: CalibrationData()
    fun saveCalibration(data: CalibrationData) = write(Path(dir, "calibration.json"), json.encodeToString(data))

    private fun <T> read(path: Path, decode: (String) -> T): T? = try {
        if (!SystemFileSystem.exists(path)) null else decode(SystemFileSystem.source(path).buffered().use { it.readString() })
    } catch (e: Exception) { null }

    private fun write(path: Path, text: String) {
        try {
            SystemFileSystem.createDirectories(dir)
            val tmp = Path(dir, "${path.name}.tmp")
            SystemFileSystem.sink(tmp).buffered().use { it.writeString(text) }
            SystemFileSystem.atomicMove(tmp, path)
        } catch (e: Exception) { println("PracticeStore: could not save ${path.name}: $e") }
    }
}
