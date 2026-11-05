package at.zentriert.setlist.audio

import at.zentriert.setlist.currentTimeMillis

/** One note on/off for the sequencer; seconds are relative to the start of playback. */
data class PlaybackEvent(val seconds: Double, val part: Int, val key: Int, val velocity: Int)

/** One hit the listener heard: engine frame (output clock), recognised pad or -1, loudness in dB, sound features. */
data class DetectedHit(val frame: Long, val cls: Int, val confidence: Float, val levelDb: Float, val features: FloatArray)

const val HIT_FEATURES = 20

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
    /** Loops [startSeconds, endSeconds) of the sequence; end <= start disables looping. */
    fun setLoop(startSeconds: Double, endSeconds: Double)
    /**
     * Starts the sequence [leadSeconds] from now, optionally with the metronome locked to it;
     * [metronomePhaseBeats] places the metronome mid-bar when the sequence starts mid-bar.
     */
    fun play(leadSeconds: Double, withMetronome: Boolean, metronomePhaseBeats: Double = 0.0)
    /** Starts the sequence (or, with [metronomeOnly], just the metronome) when the local monotonic clock reaches [atNanos]. */
    fun playAt(atNanos: Long, withMetronome: Boolean, metronomeOnly: Boolean = false, metronomePhaseBeats: Double = 0.0)
    fun stopPlayback()
    val playing: Boolean
    /** Seconds since playback start, or null when stopped. */
    fun positionSeconds(): Double?
    fun noteOn(part: Int, key: Int, velocity: Int)
    fun noteOff(part: Int, key: Int)

    /** Estimated delay between scheduling and sound leaving the speaker. */
    fun outputLatencyNanos(): Long

    // --- listening (microphone) ---
    /** Opens (or closes) the microphone for hit detection; false when it is not available. */
    fun setListening(on: Boolean): Boolean
    /** Ignore the metronome click in the microphone signal (speaker playback). */
    fun setClickSuppression(on: Boolean)
    fun pollHits(): List<DetectedHit>
    fun addCalibrationSample(cls: Int, features: FloatArray)
    fun clearCalibration(cls: Int)
    fun inputLatencyNanos(): Long
    /** Engine frame of sequence time 0 while playing (moves on loop wraps). */
    fun sequenceStartFrame(): Long
    /** Engine frame of bar 0 / beat 0 of the running metronome and the beat length in frames. */
    fun beatZeroFrame(): Long
    fun beatFrames(): Double
    fun sampleRate(): Int
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
    override fun setLoop(startSeconds: Double, endSeconds: Double) { if (endSeconds > startSeconds) sequenceEnd = Double.MAX_VALUE }
    override fun play(leadSeconds: Double, withMetronome: Boolean, metronomePhaseBeats: Double) {
        playStartedAt = currentTimeMillis() + (leadSeconds * 1000).toLong()
        if (withMetronome) metronomeStartedAt = playStartedAt
    }
    override fun playAt(atNanos: Long, withMetronome: Boolean, metronomeOnly: Boolean, metronomePhaseBeats: Double) {
        val lead = (atNanos - at.zentriert.setlist.monotonicNanos()) / 1e9
        if (metronomeOnly) metronomeStartedAt = currentTimeMillis() + (lead * 1000).toLong() else play(lead, withMetronome, metronomePhaseBeats)
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
    override fun setListening(on: Boolean) = false
    override fun setClickSuppression(on: Boolean) {}
    override fun pollHits(): List<DetectedHit> = emptyList()
    override fun addCalibrationSample(cls: Int, features: FloatArray) {}
    override fun clearCalibration(cls: Int) {}
    override fun inputLatencyNanos() = 0L
    override fun sequenceStartFrame() = 0L
    override fun beatZeroFrame() = 0L
    override fun beatFrames() = 24000.0
    override fun sampleRate() = 48000
    override fun suspendOutput() {}
    override fun release() {}
}
