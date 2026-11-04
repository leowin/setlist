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
