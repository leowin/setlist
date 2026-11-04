package at.zentriert.setlist.audio

actual fun createAudioEngine(): AudioEngine = AndroidAudioEngine()

/** Oboe-backed engine; the output stream is opened lazily on the first start. */
class AndroidAudioEngine : AudioEngine {
    private var handle = NativeAudio.create()
    private var streamRunning = false
    private var fontLoaded = false

    private fun ensureStream() {
        if (!streamRunning) streamRunning = NativeAudio.start(handle)
    }

    override fun setTransport(config: TransportConfig) = NativeAudio.setTransport(
        handle, config.bpm, config.numerator, config.denominator, config.subdivision, config.countInBars,
    )

    override fun setClickVolume(volume: Float) = NativeAudio.setClickVolume(handle, volume)

    override fun startMetronome() {
        ensureStream()
        NativeAudio.metronomeStart(handle)
    }

    override fun stopMetronome() = NativeAudio.metronomeStop(handle)
    override val metronomeRunning: Boolean get() = NativeAudio.metronomeRunning(handle)
    override fun positionBeats(): Double? =
        if (NativeAudio.metronomeRunning(handle)) NativeAudio.positionBeats(handle) else null

    override fun loadSoundFont(bytes: ByteArray): Boolean {
        fontLoaded = NativeAudio.loadSoundFont(handle, bytes)
        return fontLoaded
    }
    override val soundFontLoaded: Boolean get() = fontLoaded
    override fun setPartPreset(part: Int, program: Int, drums: Boolean) = NativeAudio.setPartPreset(handle, part, program, drums)
    override fun setPartGain(part: Int, gain: Float) = NativeAudio.setPartGain(handle, part, gain)
    override fun setSequence(events: List<PlaybackEvent>) = NativeAudio.setSequence(
        handle,
        DoubleArray(events.size) { events[it].seconds },
        IntArray(events.size) { events[it].part },
        IntArray(events.size) { events[it].key },
        IntArray(events.size) { events[it].velocity },
    )

    override fun play(leadSeconds: Double, withMetronome: Boolean) {
        ensureStream()
        val startFrame = NativeAudio.currentFrame(handle) + (leadSeconds * NativeAudio.sampleRate(handle)).toLong()
        NativeAudio.play(handle, startFrame, withMetronome)
    }

    override fun stopPlayback() = NativeAudio.stopPlayback(handle)
    override val playing: Boolean get() = NativeAudio.isPlaying(handle)
    override fun positionSeconds(): Double? = NativeAudio.positionSeconds(handle).takeIf { it >= 0 }
    override fun noteOn(part: Int, key: Int, velocity: Int) { ensureStream(); NativeAudio.noteOn(handle, part, key, velocity) }
    override fun noteOff(part: Int, key: Int) = NativeAudio.noteOff(handle, part, key)

    override fun outputLatencyNanos(): Long = NativeAudio.outputLatencyNanos(handle)

    override fun suspendOutput() {
        NativeAudio.metronomeStop(handle)
        NativeAudio.stopPlayback(handle)
        NativeAudio.stop(handle)
        streamRunning = false
    }

    override fun release() {
        if (handle != 0L) NativeAudio.destroy(handle)
        handle = 0L
    }
}
