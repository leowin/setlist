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
}
