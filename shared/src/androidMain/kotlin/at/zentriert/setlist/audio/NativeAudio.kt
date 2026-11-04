package at.zentriert.setlist.audio

/** JNI surface of audio/src/android/jni.cpp. */
internal object NativeAudio {
    init {
        System.loadLibrary("setlistaudio")
    }

    external fun create(): Long
    external fun destroy(handle: Long)
    external fun start(handle: Long): Boolean
    external fun stop(handle: Long)
    external fun setTransport(handle: Long, bpm: Double, numerator: Int, denominator: Int, subdivision: Int, countInBars: Int)
    external fun setClickVolume(handle: Long, volume: Float)
    external fun metronomeStart(handle: Long)
    external fun metronomeStop(handle: Long)
    external fun metronomeRunning(handle: Long): Boolean
    external fun positionBeats(handle: Long): Double
    external fun outputLatencyNanos(handle: Long): Long
    external fun nanosForFrame(handle: Long, frame: Long): Long

    external fun loadSoundFont(handle: Long, data: ByteArray): Boolean
    external fun setPartPreset(handle: Long, part: Int, program: Int, drums: Boolean)
    external fun setPartGain(handle: Long, part: Int, gain: Float)
    external fun noteOn(handle: Long, part: Int, key: Int, velocity: Int)
    external fun noteOff(handle: Long, part: Int, key: Int)
    external fun setSequence(handle: Long, seconds: DoubleArray, parts: IntArray, keys: IntArray, velocities: IntArray)
    external fun setLoop(handle: Long, startSeconds: Double, endSeconds: Double)
    external fun play(handle: Long, startFrame: Long, withMetronome: Boolean)
    external fun stopPlayback(handle: Long)
    external fun isPlaying(handle: Long): Boolean
    external fun positionSeconds(handle: Long): Double
    external fun currentFrame(handle: Long): Long
    external fun sampleRate(handle: Long): Int
}
