package at.zentriert.setlist.audio

import at.zentriert.setlist.currentTimeMillis

/** One note on/off for the sequencer; seconds are relative to the start of playback. */
data class PlaybackEvent(val seconds: Double, val part: Int, val key: Int, val velocity: Int)

/** Thin facade over the native audio engine; one instance per process. */
interface AudioEngine {
    // --- metronome ---
    fun setTransport(config: TransportConfig)
    fun setClickVolume(volume: Float)
    fun startMetronome()
    fun stopMetronome()
    val metronomeRunning: Boolean
    /** Position in beats since bar 0 (negative during count-in), or null when stopped. */
    fun positionBeats(): Double?

    // --- SoundFont playback ---
    fun loadSoundFont(bytes: ByteArray): Boolean
    val soundFontLoaded: Boolean
    fun setPartPreset(part: Int, program: Int, drums: Boolean)
    fun setPartGain(part: Int, gain: Float)
    fun setSequence(events: List<PlaybackEvent>)
    /** Starts the sequence [leadSeconds] from now, optionally with the metronome locked to it. */
    fun play(leadSeconds: Double, withMetronome: Boolean)
    fun stopPlayback()
    val playing: Boolean
    /** Seconds since playback start, or null when stopped. */
    fun positionSeconds(): Double?
    fun noteOn(part: Int, key: Int, velocity: Int)
    fun noteOff(part: Int, key: Int)

    /** Estimated delay between scheduling and sound leaving the speaker. */
    fun outputLatencyNanos(): Long
    /** Closes the output stream to save battery; it reopens on the next start. */
    fun suspendOutput()
    fun release()
}

expect fun createAudioEngine(): AudioEngine

/** Engine that stays silent but keeps time; used where no native backend exists yet. */
class SilentAudioEngine : AudioEngine {
    private var config = TransportConfig()
    private var metronomeStartedAt: Long? = null
    private var playStartedAt: Long? = null
    private var sequenceEnd = 0.0

    override fun setTransport(config: TransportConfig) { this.config = config }
    override fun setClickVolume(volume: Float) {}
    override fun startMetronome() { metronomeStartedAt = currentTimeMillis() }
    override fun stopMetronome() { metronomeStartedAt = null }
    override val metronomeRunning get() = metronomeStartedAt != null
    override fun positionBeats(): Double? {
        val started = metronomeStartedAt ?: return null
        val seconds = (currentTimeMillis() - started) / 1000.0
        return seconds / config.beatSeconds - config.countInBars * config.numerator
    }

    override fun loadSoundFont(bytes: ByteArray) = true
    override val soundFontLoaded get() = true
    override fun setPartPreset(part: Int, program: Int, drums: Boolean) {}
    override fun setPartGain(part: Int, gain: Float) {}
    override fun setSequence(events: List<PlaybackEvent>) { sequenceEnd = events.maxOfOrNull { it.seconds } ?: 0.0 }
    override fun play(leadSeconds: Double, withMetronome: Boolean) {
        playStartedAt = currentTimeMillis() + (leadSeconds * 1000).toLong()
        if (withMetronome) metronomeStartedAt = playStartedAt
    }
    override fun stopPlayback() { playStartedAt = null; metronomeStartedAt = null }
    override val playing: Boolean get() = positionSeconds() != null
    override fun positionSeconds(): Double? {
        val started = playStartedAt ?: return null
        val seconds = (currentTimeMillis() - started) / 1000.0
        if (seconds > sequenceEnd + 1.0) { playStartedAt = null; return null }
        return seconds
    }
    override fun noteOn(part: Int, key: Int, velocity: Int) {}
    override fun noteOff(part: Int, key: Int) {}

    override fun outputLatencyNanos() = 0L
    override fun suspendOutput() {}
    override fun release() {}
}
