package at.zentriert.setlist.band

import at.zentriert.setlist.Instrument
import at.zentriert.setlist.Score
import at.zentriert.setlist.Setlist
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Messages between host and members, one JSON object per line over TCP. Times are host monotonic ns. */
@Serializable
sealed class Msg {
    @Serializable data class Hello(val name: String, val instrument: Instrument?, val version: Int = 1) : Msg()
    @Serializable data class Welcome(val hostName: String, val peerId: String, val udpPort: Int) : Msg()
    @Serializable data class Peers(val names: List<String>) : Msg()
    /** The host's current setlist with every score it needs. */
    @Serializable data class SetlistSync(val setlist: Setlist, val scores: Map<String, Score>) : Msg()
    @Serializable data class Navigate(val index: Int) : Msg()
    /** Start the metronome (score == null) or the score at [atHostNanos]. */
    @Serializable data class Start(
        val atHostNanos: Long, val scoreId: String?, val fromTick: Long,
        val bpm: Double, val numerator: Int, val denominator: Int, val subdivision: Int, val countInBars: Int,
        val withMetronome: Boolean,
    ) : Msg()
    @Serializable data class Stop(val atHostNanos: Long) : Msg()
    /** Everybody flashes and clicks on whole host seconds, for checking the sync by ear. */
    @Serializable data class SyncTest(val on: Boolean) : Msg()
    @Serializable data class Bye(val reason: String = "") : Msg()

    companion object {
        val json = Json { ignoreUnknownKeys = true; classDiscriminator = "t" }
        fun encode(msg: Msg): String = json.encodeToString(serializer(), msg)
        fun decode(line: String): Msg? = try { json.decodeFromString(serializer(), line) } catch (e: Exception) { null }
    }
}

const val SERVICE_TYPE = "_setlist._tcp"
const val DEFAULT_PORT = 47129
