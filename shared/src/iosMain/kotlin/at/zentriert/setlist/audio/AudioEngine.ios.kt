package at.zentriert.setlist.audio

import at.zentriert.setlist.monotonicNanos
import at.zentriert.setlist.native.sl_create
import at.zentriert.setlist.native.sl_current_frame
import at.zentriert.setlist.native.sl_destroy
import at.zentriert.setlist.native.sl_is_playing
import at.zentriert.setlist.native.sl_load_soundfont
import at.zentriert.setlist.native.sl_metronome_running
import at.zentriert.setlist.native.sl_metronome_start
import at.zentriert.setlist.native.sl_metronome_start_at
import at.zentriert.setlist.native.sl_metronome_stop
import at.zentriert.setlist.native.sl_nanos_for_frame
import at.zentriert.setlist.native.sl_note_off
import at.zentriert.setlist.native.sl_note_on
import at.zentriert.setlist.native.sl_output_latency_nanos
import at.zentriert.setlist.native.sl_play
import at.zentriert.setlist.native.sl_position_beats
import at.zentriert.setlist.native.sl_position_seconds
import at.zentriert.setlist.native.sl_sample_rate
import at.zentriert.setlist.native.sl_set_click_volume
import at.zentriert.setlist.native.sl_set_loop
import at.zentriert.setlist.native.sl_set_part_gain
import at.zentriert.setlist.native.sl_set_part_preset
import at.zentriert.setlist.native.sl_set_sequence
import at.zentriert.setlist.native.sl_set_transport
import at.zentriert.setlist.native.sl_start
import at.zentriert.setlist.native.sl_stop
import at.zentriert.setlist.native.sl_stop_playback
import at.zentriert.setlist.native.sl_set_listening
import at.zentriert.setlist.native.sl_set_click_suppression
import at.zentriert.setlist.native.sl_input_latency_nanos
import at.zentriert.setlist.native.sl_poll_hits
import at.zentriert.setlist.native.sl_calibration_add
import at.zentriert.setlist.native.sl_calibration_clear
import at.zentriert.setlist.native.sl_sequence_start_frame
import at.zentriert.setlist.native.sl_beat_zero_frame
import at.zentriert.setlist.native.sl_beat_frames
import at.zentriert.setlist.native.sl_set_listen_mode
import at.zentriert.setlist.native.sl_load_track
import at.zentriert.setlist.native.sl_clear_track
import at.zentriert.setlist.native.sl_set_track_params
import at.zentriert.setlist.native.sl_set_track_gain
import at.zentriert.setlist.native.sl_set_input_gain
import at.zentriert.setlist.native.sl_set_sensitivity
import at.zentriert.setlist.native.sl_input_level_db
import at.zentriert.setlist.native.sl_input_gain
import at.zentriert.setlist.native.sl_learn_profile
import at.zentriert.setlist.native.sl_set_harmonic_profile
import at.zentriert.setlist.native.sl_harmonic_profile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned

actual fun createAudioEngine(): AudioEngine = IosAudioEngine()

/** CoreAudio (RemoteIO) backend through the C API in audio/src/capi; same engine as Android. */
@OptIn(ExperimentalForeignApi::class)
class IosAudioEngine : AudioEngine {
    private var handle = sl_create()
    private var streamRunning = false
    private var fontLoaded = false

    private fun ensureStream() { if (!streamRunning) streamRunning = sl_start(handle) != 0 }

    override fun setTransport(config: TransportConfig) =
        sl_set_transport(handle, config.bpm, config.numerator, config.denominator, config.subdivision, config.countInBars)
    override fun setClickVolume(volume: Float) = sl_set_click_volume(handle, volume)
    override fun startMetronome() { ensureStream(); sl_metronome_start(handle) }
    override fun stopMetronome() = sl_metronome_stop(handle)
    override val metronomeRunning: Boolean get() = sl_metronome_running(handle) != 0
    override fun positionBeats(): Double? = if (metronomeRunning) sl_position_beats(handle) else null

    override fun loadSoundFont(bytes: ByteArray): Boolean {
        fontLoaded = bytes.usePinned { sl_load_soundfont(handle, it.addressOf(0), bytes.size) != 0 }
        return fontLoaded
    }
    override val soundFontLoaded: Boolean get() = fontLoaded
    override fun setPartPreset(part: Int, program: Int, drums: Boolean) = sl_set_part_preset(handle, part, program, if (drums) 1 else 0)
    override fun setPartGain(part: Int, gain: Float) = sl_set_part_gain(handle, part, gain)
    override fun setSequence(events: List<PlaybackEvent>) {
        val seconds = DoubleArray(events.size) { events[it].seconds }
        val parts = IntArray(events.size) { events[it].part }
        val keys = IntArray(events.size) { events[it].key }
        val vels = IntArray(events.size) { events[it].velocity }
        if (events.isEmpty()) { sl_set_sequence(handle, null, null, null, null, 0); return }
        seconds.usePinned { s -> parts.usePinned { p -> keys.usePinned { k -> vels.usePinned { v ->
            sl_set_sequence(handle, s.addressOf(0), p.addressOf(0), k.addressOf(0), v.addressOf(0), events.size)
        } } } }
    }
    override fun setLoop(startSeconds: Double, endSeconds: Double) = sl_set_loop(handle, startSeconds, endSeconds)
    override fun play(leadSeconds: Double, withMetronome: Boolean, metronomePhaseBeats: Double) {
        ensureStream()
        val start = sl_current_frame(handle) + (leadSeconds * sl_sample_rate(handle)).toLong()
        sl_play(handle, start, if (withMetronome) 1 else 0, metronomePhaseBeats)
    }
    override fun playAt(atNanos: Long, withMetronome: Boolean, metronomeOnly: Boolean, metronomePhaseBeats: Double) {
        ensureStream()
        val frame0 = sl_current_frame(handle)
        val sr = sl_sample_rate(handle)
        val t0 = sl_nanos_for_frame(handle, frame0)
        val startFrame = if (t0 > 0) frame0 + (atNanos - t0) * sr / 1_000_000_000L
        else frame0 + (((atNanos - monotonicNanos() - sl_output_latency_nanos(handle)) / 1e9).coerceAtLeast(0.0) * sr).toLong()
        if (metronomeOnly) sl_metronome_start_at(handle, startFrame) else sl_play(handle, startFrame, if (withMetronome) 1 else 0, metronomePhaseBeats)
    }
    override fun stopPlayback() = sl_stop_playback(handle)
    override val playing: Boolean get() = sl_is_playing(handle) != 0
    override fun positionSeconds(): Double? = sl_position_seconds(handle).takeIf { it >= 0 }
    override fun noteOn(part: Int, key: Int, velocity: Int) { ensureStream(); sl_note_on(handle, part, key, velocity) }
    override fun noteOff(part: Int, key: Int) = sl_note_off(handle, part, key)
    override fun outputLatencyNanos(): Long = sl_output_latency_nanos(handle)

    override fun loadTrack(slot: Int, pcm: FloatArray, sampleRate: Int) { if (pcm.isNotEmpty()) pcm.usePinned { sl_load_track(handle, slot, it.addressOf(0), pcm.size, sampleRate) } }
    override fun clearTrack(slot: Int) = sl_clear_track(handle, slot)
    override fun setTrackParams(slot: Int, seqStart: Double, audioStart: Double, ratio: Double, gain: Float, active: Boolean) =
        sl_set_track_params(handle, slot, seqStart, audioStart, ratio, gain, if (active) 1 else 0)
    override fun setTrackGain(slot: Int, gain: Float) = sl_set_track_gain(handle, slot, gain)

    private var listeningOn = false
    override fun setListening(on: Boolean): Boolean {
        listeningOn = on && sl_set_listening(handle, 1) != 0
        if (!on) sl_set_listening(handle, 0)
        return !on || listeningOn
    }
    override fun setClickSuppression(on: Boolean) = sl_set_click_suppression(handle, if (on) 1 else 0)
    override fun pollHits(): List<DetectedHit> {
        val frames = LongArray(64); val classes = IntArray(64); val conf = FloatArray(64); val levels = FloatArray(64); val features = FloatArray(64 * HIT_FEATURES); val extra = FloatArray(128)
        val n = frames.usePinned { f -> classes.usePinned { c -> conf.usePinned { k -> levels.usePinned { l -> features.usePinned { ft -> extra.usePinned { ex ->
            sl_poll_hits(handle, f.addressOf(0), c.addressOf(0), k.addressOf(0), l.addressOf(0), ft.addressOf(0), ex.addressOf(0), 64)
        } } } } } }
        return List(n) { i -> DetectedHit(frames[i], classes[i], conf[i], levels[i], features.copyOfRange(i * HIT_FEATURES, (i + 1) * HIT_FEATURES), extra[i * 2], extra[i * 2 + 1].toInt()) }
    }
    override fun setListenMode(mode: ListenMode) = sl_set_listen_mode(handle, mode.ordinal)
    override fun setInputGain(gain: Float, automatic: Boolean) = sl_set_input_gain(handle, gain, if (automatic) 1 else 0)
    override fun setSensitivity(factor: Float, floor: Float) = sl_set_sensitivity(handle, factor, floor)
    override fun inputLevelDb(): Float = sl_input_level_db(handle)
    override fun inputGain(): Float = sl_input_gain(handle)
    override fun learnProfile(midi: Int) = sl_learn_profile(handle, midi)
    override fun setHarmonicProfile(profile: FloatArray) { if (profile.isNotEmpty()) profile.usePinned { sl_set_harmonic_profile(handle, it.addressOf(0), profile.size) } }
    override fun harmonicProfile(): FloatArray { val out = FloatArray(8); val n = out.usePinned { sl_harmonic_profile(handle, it.addressOf(0), 8) }; return out.copyOf(n) }
    override fun addCalibrationSample(cls: Int, features: FloatArray) { features.usePinned { sl_calibration_add(handle, cls, it.addressOf(0)) } }
    override fun clearCalibration(cls: Int) = sl_calibration_clear(handle, cls)
    override fun inputLatencyNanos(): Long = sl_input_latency_nanos(handle)
    override fun sequenceStartFrame(): Long = sl_sequence_start_frame(handle)
    override fun beatZeroFrame(): Long = sl_beat_zero_frame(handle)
    override fun beatFrames(): Double = sl_beat_frames(handle)
    override fun sampleRate(): Int = sl_sample_rate(handle)
    override fun suspendOutput() {
        sl_metronome_stop(handle); sl_stop_playback(handle)
        if (listeningOn) return   // the microphone keeps the unit alive
        sl_stop(handle); streamRunning = false
    }
    override fun release() { handle?.let { sl_destroy(it) }; handle = null }
}
