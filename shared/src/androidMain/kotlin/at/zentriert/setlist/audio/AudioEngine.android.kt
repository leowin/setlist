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

    override fun setLoop(startSeconds: Double, endSeconds: Double) = NativeAudio.setLoop(handle, startSeconds, endSeconds)

    override fun play(leadSeconds: Double, withMetronome: Boolean, metronomePhaseBeats: Double) {
        ensureStream()
        val startFrame = NativeAudio.currentFrame(handle) + (leadSeconds * NativeAudio.sampleRate(handle)).toLong()
        NativeAudio.play(handle, startFrame, withMetronome, metronomePhaseBeats)
    }

    override fun playAt(atNanos: Long, withMetronome: Boolean, metronomeOnly: Boolean, metronomePhaseBeats: Double) {
        ensureStream()
        val frame0 = NativeAudio.currentFrame(handle)
        val sr = NativeAudio.sampleRate(handle)
        val t0 = NativeAudio.nanosForFrame(handle, frame0)
        val startFrame = if (t0 > 0) {
            // The stream timestamp tells when frame0 reaches the speaker → exact frame for atNanos.
            frame0 + (atNanos - t0) * sr / 1_000_000_000L
        } else {
            val lead = (atNanos - System.nanoTime() - NativeAudio.outputLatencyNanos(handle)) / 1e9
            frame0 + (lead.coerceAtLeast(0.0) * sr).toLong()
        }
        if (metronomeOnly) NativeAudio.metronomeStartAt(handle, startFrame) else NativeAudio.play(handle, startFrame, withMetronome, metronomePhaseBeats)
    }

    override fun stopPlayback() = NativeAudio.stopPlayback(handle)
    override val playing: Boolean get() = NativeAudio.isPlaying(handle)
    override fun positionSeconds(): Double? = NativeAudio.positionSeconds(handle).takeIf { it >= 0 }
    override fun noteOn(part: Int, key: Int, velocity: Int) { ensureStream(); NativeAudio.noteOn(handle, part, key, velocity) }
    override fun noteOff(part: Int, key: Int) = NativeAudio.noteOff(handle, part, key)

    override fun outputLatencyNanos(): Long = NativeAudio.outputLatencyNanos(handle)

    private val hitFrames = LongArray(64); private val hitClasses = IntArray(64)
    private val hitConf = FloatArray(64); private val hitLevels = FloatArray(64); private val hitFeatures = FloatArray(64 * HIT_FEATURES)
    private val hitExtra = FloatArray(128)

    private var listeningOn = false
    override fun setListening(on: Boolean): Boolean {
        if (on) ensureStream()
        listeningOn = on && NativeAudio.setListening(handle, true)
        if (!on) NativeAudio.setListening(handle, false)
        return !on || listeningOn
    }
    override fun setClickSuppression(on: Boolean) = NativeAudio.setClickSuppression(handle, on)
    override fun pollHits(): List<DetectedHit> {
        val n = NativeAudio.pollHits(handle, hitFrames, hitClasses, hitConf, hitLevels, hitFeatures, hitExtra)
        return List(n) { i -> DetectedHit(hitFrames[i], hitClasses[i], hitConf[i], hitLevels[i], hitFeatures.copyOfRange(i * HIT_FEATURES, (i + 1) * HIT_FEATURES), hitExtra[i * 2], hitExtra[i * 2 + 1].toInt()) }
    }
    override fun setListenMode(mode: ListenMode) = NativeAudio.setListenMode(handle, mode.ordinal)
    override fun setInputGain(gain: Float, automatic: Boolean) = NativeAudio.setInputGain(handle, gain, automatic)
    override fun setSensitivity(factor: Float, floor: Float) = NativeAudio.setSensitivity(handle, factor, floor)
    override fun inputLevelDb(): Float = NativeAudio.inputLevelDb(handle)
    override fun inputGain(): Float = NativeAudio.inputGain(handle)
    override fun learnProfile(midi: Int) = NativeAudio.learnProfile(handle, midi)
    override fun setHarmonicProfile(profile: FloatArray) = NativeAudio.setHarmonicProfile(handle, profile)
    override fun harmonicProfile(): FloatArray = NativeAudio.harmonicProfile(handle)
    override fun addCalibrationSample(cls: Int, features: FloatArray) = NativeAudio.calibrationAdd(handle, cls, features)
    override fun clearCalibration(cls: Int) = NativeAudio.calibrationClear(handle, cls)
    override fun inputLatencyNanos(): Long = NativeAudio.inputLatencyNanos(handle)
    override fun sequenceStartFrame(): Long = NativeAudio.sequenceStartFrame(handle)
    override fun beatZeroFrame(): Long = NativeAudio.beatZeroFrame(handle)
    override fun beatFrames(): Double = NativeAudio.beatFrames(handle)
    override fun sampleRate(): Int = NativeAudio.sampleRate(handle)

    override fun suspendOutput() {
        NativeAudio.metronomeStop(handle)
        NativeAudio.stopPlayback(handle)
        // While the microphone listens the streams stay open: closing them would silence the listener.
        if (listeningOn) return
        NativeAudio.stop(handle)
        streamRunning = false
    }

    override fun release() {
        if (handle != 0L) NativeAudio.destroy(handle)
        handle = 0L
    }
}
