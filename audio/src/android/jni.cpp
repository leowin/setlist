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

}  // extern "C"
