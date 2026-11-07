// JNI bridge for at.zentriert.setlist.audio.NativeAudio
#include <jni.h>
#include "OboeEngine.h"

using setlist::OboeEngine;

static OboeEngine* engine(jlong handle) { return reinterpret_cast<OboeEngine*>(handle); }

extern "C" {

JNIEXPORT jlong JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_create(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new OboeEngine());
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_destroy(JNIEnv*, jobject, jlong h) {
    engine(h)->stop();
    delete engine(h);
}

JNIEXPORT jboolean JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_start(JNIEnv*, jobject, jlong h) {
    return engine(h)->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_stop(JNIEnv*, jobject, jlong h) {
    engine(h)->stop();
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_setTransport(JNIEnv*, jobject, jlong h, jdouble bpm, jint num,
                                                         jint den, jint subdivision, jint countInBars) {
    setlist::TransportConfig c;
    c.bpm = bpm; c.numerator = num; c.denominator = den; c.subdivision = subdivision; c.countInBars = countInBars;
    engine(h)->engine.metronome.setConfig(c);
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_setClickVolume(JNIEnv*, jobject, jlong h, jfloat v) {
    engine(h)->engine.metronome.setVolume(v);
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_metronomeStart(JNIEnv*, jobject, jlong h) {
    engine(h)->engine.metronome.start();
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_metronomeStartAt(JNIEnv*, jobject, jlong h, jlong frame) {
    engine(h)->engine.metronome.startAt(frame);
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_metronomeStop(JNIEnv*, jobject, jlong h) {
    engine(h)->engine.metronome.stop();
}

JNIEXPORT jboolean JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_metronomeRunning(JNIEnv*, jobject, jlong h) {
    return engine(h)->engine.metronome.running() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jdouble JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_positionBeats(JNIEnv*, jobject, jlong h) {
    return engine(h)->engine.metronome.positionBeats();
}

JNIEXPORT jlong JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_outputLatencyNanos(JNIEnv*, jobject, jlong h) {
    return engine(h)->outputLatencyNanos();
}

JNIEXPORT jlong JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_nanosForFrame(JNIEnv*, jobject, jlong h, jlong frame) {
    return engine(h)->nanosForFrame(frame);
}

JNIEXPORT jboolean JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_loadSoundFont(JNIEnv* env, jobject, jlong h, jbyteArray data) {
    jsize size = env->GetArrayLength(data);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    bool ok = engine(h)->engine.synth.load(bytes, int(size));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_setPartPreset(JNIEnv*, jobject, jlong h, jint part, jint program, jboolean drums) {
    engine(h)->engine.commands.push({setlist::Command::SetPreset, int16_t(part), program, drums ? 1.0f : 0.0f});
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_setPartGain(JNIEnv*, jobject, jlong h, jint part, jfloat gain) {
    engine(h)->engine.commands.push({setlist::Command::SetGain, int16_t(part), 0, gain});
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_noteOn(JNIEnv*, jobject, jlong h, jint part, jint key, jint velocity) {
    engine(h)->engine.commands.push({setlist::Command::NoteOn, int16_t(part), key, float(velocity)});
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_noteOff(JNIEnv*, jobject, jlong h, jint part, jint key) {
    engine(h)->engine.commands.push({setlist::Command::NoteOff, int16_t(part), key, 0.0f});
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_setSequence(JNIEnv* env, jobject, jlong h, jdoubleArray seconds,
                                                        jintArray parts, jintArray keys, jintArray velocities) {
    jsize n = env->GetArrayLength(seconds);
    auto* events = new std::vector<setlist::SeqEvent>(n);
    jdouble* s = env->GetDoubleArrayElements(seconds, nullptr);
    jint* p = env->GetIntArrayElements(parts, nullptr);
    jint* k = env->GetIntArrayElements(keys, nullptr);
    jint* v = env->GetIntArrayElements(velocities, nullptr);
    for (jsize i = 0; i < n; ++i) (*events)[i] = {s[i], int16_t(p[i]), uint8_t(k[i]), uint8_t(v[i])};
    env->ReleaseDoubleArrayElements(seconds, s, JNI_ABORT);
    env->ReleaseIntArrayElements(parts, p, JNI_ABORT);
    env->ReleaseIntArrayElements(keys, k, JNI_ABORT);
    env->ReleaseIntArrayElements(velocities, v, JNI_ABORT);
    engine(h)->engine.sequencer.setSequence(events);
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_play(JNIEnv*, jobject, jlong h, jlong startFrame, jboolean withMetronome, jdouble phaseBeats) {
    engine(h)->engine.play(startFrame, withMetronome == JNI_TRUE, phaseBeats);
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_setLoop(JNIEnv*, jobject, jlong h, jdouble start, jdouble end) {
    engine(h)->engine.sequencer.setLoop(start, end);
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_stopPlayback(JNIEnv*, jobject, jlong h) {
    engine(h)->engine.stopPlayback();
}

JNIEXPORT jboolean JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_isPlaying(JNIEnv*, jobject, jlong h) {
    return engine(h)->engine.sequencer.playing() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jdouble JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_positionSeconds(JNIEnv*, jobject, jlong h) {
    return engine(h)->engine.sequencer.positionSeconds();
}

JNIEXPORT jlong JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_currentFrame(JNIEnv*, jobject, jlong h) {
    return engine(h)->engine.currentFrame();
}

JNIEXPORT jint JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_sampleRate(JNIEnv*, jobject, jlong h) {
    return engine(h)->engine.sampleRate();
}

}  // extern "C"

// --- listening (microphone) ---
extern "C" {

JNIEXPORT jboolean JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_setListening(JNIEnv*, jobject, jlong h, jboolean on) {
    return engine(h)->setListening(on == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_setClickSuppression(JNIEnv*, jobject, jlong h, jboolean on) {
    engine(h)->engine.listener.setClickSuppression(on == JNI_TRUE);
}

JNIEXPORT jlong JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_inputLatencyNanos(JNIEnv*, jobject, jlong h) {
    return engine(h)->inputLatencyNanos();
}

// Fills up to `capacity` hits; features are kHitFeatures floats per hit; extra = cents, voices per hit. Returns the count.
JNIEXPORT jint JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_pollHits(JNIEnv* env, jobject, jlong h, jlongArray frames, jintArray classes,
                                                     jfloatArray confidences, jfloatArray levels, jfloatArray features, jfloatArray extra) {
    jsize capacity = env->GetArrayLength(frames);
    std::vector<jlong> f; std::vector<jint> c; std::vector<jfloat> conf, lev, feat, ex;
    setlist::HitEvent e;
    while (jsize(f.size()) < capacity && engine(h)->engine.listener.pop(e)) {
        f.push_back(e.frame); c.push_back(e.cls); conf.push_back(e.confidence); lev.push_back(e.level);
        feat.insert(feat.end(), e.features, e.features + setlist::kHitFeatures);
        ex.push_back(e.cents); ex.push_back(float(e.voices));
    }
    jsize n = jsize(f.size());
    if (n == 0) return 0;
    env->SetLongArrayRegion(frames, 0, n, f.data());
    env->SetIntArrayRegion(classes, 0, n, c.data());
    env->SetFloatArrayRegion(confidences, 0, n, conf.data());
    env->SetFloatArrayRegion(levels, 0, n, lev.data());
    env->SetFloatArrayRegion(features, 0, n * setlist::kHitFeatures, feat.data());
    env->SetFloatArrayRegion(extra, 0, n * 2, ex.data());
    return n;
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_setSensitivity(JNIEnv*, jobject, jlong h, jfloat factor, jfloat floor) {
    engine(h)->engine.listener.setSensitivity(factor, floor);
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_setInputGain(JNIEnv*, jobject, jlong h, jfloat gain, jboolean automatic) {
    engine(h)->engine.listener.setInputGain(gain, automatic == JNI_TRUE);
}

JNIEXPORT jfloat JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_inputLevelDb(JNIEnv*, jobject, jlong h) {
    return engine(h)->engine.listener.inputLevelDb();
}

JNIEXPORT jfloat JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_inputGain(JNIEnv*, jobject, jlong h) {
    return engine(h)->engine.listener.inputGain();
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_setListenMode(JNIEnv*, jobject, jlong h, jint mode) {
    engine(h)->engine.listener.setMode(setlist::ListenMode(mode));
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_learnProfile(JNIEnv*, jobject, jlong h, jint midi) {
    engine(h)->engine.listener.learnProfile(midi);
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_setHarmonicProfile(JNIEnv* env, jobject, jlong h, jfloatArray profile) {
    jsize n = env->GetArrayLength(profile);
    std::vector<float> p(n);
    env->GetFloatArrayRegion(profile, 0, n, p.data());
    engine(h)->engine.listener.setProfile(p);
}

JNIEXPORT jfloatArray JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_harmonicProfile(JNIEnv* env, jobject, jlong h) {
    const auto& p = engine(h)->engine.listener.chords().profile();
    jfloatArray out = env->NewFloatArray(jsize(p.size()));
    env->SetFloatArrayRegion(out, 0, jsize(p.size()), p.data());
    return out;
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_calibrationAdd(JNIEnv* env, jobject, jlong h, jint cls, jfloatArray features) {
    if (env->GetArrayLength(features) < setlist::kHitFeatures) return;
    jfloat* f = env->GetFloatArrayElements(features, nullptr);
    engine(h)->engine.listener.classifier().add(cls, f);
    env->ReleaseFloatArrayElements(features, f, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_calibrationClear(JNIEnv*, jobject, jlong h, jint cls) {
    engine(h)->engine.listener.classifier().clear(cls);
}

JNIEXPORT jlong JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_sequenceStartFrame(JNIEnv*, jobject, jlong h) {
    return engine(h)->engine.sequencer.startFrame();
}

JNIEXPORT jlong JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_beatZeroFrame(JNIEnv*, jobject, jlong h) {
    return engine(h)->engine.metronome.beatZeroFrame();
}

JNIEXPORT jdouble JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_beatFrames(JNIEnv*, jobject, jlong h) {
    return engine(h)->engine.metronome.beatFrames();
}

}  // extern "C"

// --- backing tracks ---
extern "C" {

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_loadTrack(JNIEnv* env, jobject, jlong h, jint slot, jfloatArray pcm, jint sampleRate) {
    if (slot < 0 || slot >= setlist::Engine::kTracks) return;
    jsize n = env->GetArrayLength(pcm);
    auto* data = new std::vector<float>(n);
    env->GetFloatArrayRegion(pcm, 0, n, data->data());
    engine(h)->engine.tracks[slot].load(data, sampleRate);
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_clearTrack(JNIEnv*, jobject, jlong h, jint slot) {
    if (slot < 0 || slot >= setlist::Engine::kTracks) return;
    engine(h)->engine.tracks[slot].load(new std::vector<float>(), 0);
    setlist::TrackPlayer::Params p; p.active = false;
    engine(h)->engine.tracks[slot].setParams(p);
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_setTrackParams(JNIEnv*, jobject, jlong h, jint slot, jdouble seqStart, jdouble audioStart, jdouble ratio, jfloat gain, jboolean active) {
    if (slot < 0 || slot >= setlist::Engine::kTracks) return;
    setlist::TrackPlayer::Params p; p.seqStartSeconds = seqStart; p.audioStartSeconds = audioStart; p.ratio = ratio; p.gain = gain; p.active = active == JNI_TRUE;
    engine(h)->engine.tracks[slot].setParams(p);
}

JNIEXPORT void JNICALL
Java_at_zentriert_setlist_audio_NativeAudio_setTrackGain(JNIEnv*, jobject, jlong h, jint slot, jfloat gain) {
    if (slot < 0 || slot >= setlist::Engine::kTracks) return;
    engine(h)->engine.tracks[slot].setGain(gain);
}

}  // extern "C"
