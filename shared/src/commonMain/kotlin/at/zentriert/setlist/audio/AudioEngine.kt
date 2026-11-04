package at.zentriert.setlist.audio

/** Thin facade over the native audio engine; one instance per process. */
interface AudioEngine {
    fun setTransport(config: TransportConfig)
    fun setClickVolume(volume: Float)
    fun startMetronome()
    fun stopMetronome()
    val metronomeRunning: Boolean
    /** Position in beats since bar 0 (negative during count-in), or null when stopped. */
    fun positionBeats(): Double?
    /** Estimated delay between scheduling and sound leaving the speaker. */
    fun outputLatencyNanos(): Long
    /** Closes the output stream to save battery; it reopens on the next start. */
    fun suspendOutput()
    fun release()
}

expect fun createAudioEngine(): AudioEngine

/** Engine that stays silent; used where no native backend exists yet. */
class SilentAudioEngine : AudioEngine {
    private var config = TransportConfig()
    private var startedAtMillis: Long? = null
    override fun setTransport(config: TransportConfig) { this.config = config }
    override fun setClickVolume(volume: Float) {}
    override fun startMetronome() { startedAtMillis = at.zentriert.setlist.currentTimeMillis() }
    override fun stopMetronome() { startedAtMillis = null }
    override val metronomeRunning get() = startedAtMillis != null
    override fun positionBeats(): Double? {
        val started = startedAtMillis ?: return null
        val seconds = (at.zentriert.setlist.currentTimeMillis() - started) / 1000.0
        return seconds / config.beatSeconds - config.countInBars * config.numerator
    }
    override fun outputLatencyNanos() = 0L
    override fun suspendOutput() {}
    override fun release() {}
}
