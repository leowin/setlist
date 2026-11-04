package at.zentriert.setlist.audio

actual fun createAudioEngine(): AudioEngine = AndroidAudioEngine()

/** Oboe-backed engine; the output stream is opened lazily on the first start. */
class AndroidAudioEngine : AudioEngine {
    private var handle = NativeAudio.create()
    private var streamRunning = false

    override fun setTransport(config: TransportConfig) = NativeAudio.setTransport(
        handle, config.bpm, config.numerator, config.denominator, config.subdivision, config.countInBars,
    )

    override fun setClickVolume(volume: Float) = NativeAudio.setClickVolume(handle, volume)

    override fun startMetronome() {
        if (!streamRunning) streamRunning = NativeAudio.start(handle)
        NativeAudio.metronomeStart(handle)
    }

    override fun stopMetronome() = NativeAudio.metronomeStop(handle)

    override val metronomeRunning: Boolean get() = NativeAudio.metronomeRunning(handle)

    override fun positionBeats(): Double? =
        if (NativeAudio.metronomeRunning(handle)) NativeAudio.positionBeats(handle) else null

    override fun outputLatencyNanos(): Long = NativeAudio.outputLatencyNanos(handle)

    override fun suspendOutput() {
        NativeAudio.metronomeStop(handle)
        NativeAudio.stop(handle)
        streamRunning = false
    }

    override fun release() {
        if (handle != 0L) NativeAudio.destroy(handle)
        handle = 0L
    }
}
