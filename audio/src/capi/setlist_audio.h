// C interface of the audio engine for platforms without JNI (iOS via Kotlin/Native cinterop).
#pragma once
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct sl_engine sl_engine;

sl_engine* sl_create(void);
void sl_destroy(sl_engine* e);
int sl_start(sl_engine* e);
void sl_stop(sl_engine* e);

void sl_set_transport(sl_engine* e, double bpm, int numerator, int denominator, int subdivision, int count_in_bars);
void sl_set_click_volume(sl_engine* e, float volume);
void sl_metronome_start(sl_engine* e);
void sl_metronome_start_at(sl_engine* e, int64_t frame);
void sl_metronome_stop(sl_engine* e);
int sl_metronome_running(sl_engine* e);
double sl_position_beats(sl_engine* e);

int sl_load_soundfont(sl_engine* e, const void* data, int size);
void sl_set_part_preset(sl_engine* e, int part, int program, int drums);
void sl_set_part_gain(sl_engine* e, int part, float gain);
void sl_note_on(sl_engine* e, int part, int key, int velocity);
void sl_note_off(sl_engine* e, int part, int key);
void sl_set_sequence(sl_engine* e, const double* seconds, const int32_t* parts, const int32_t* keys, const int32_t* velocities, int count);
void sl_set_loop(sl_engine* e, double start_seconds, double end_seconds);
void sl_play(sl_engine* e, int64_t start_frame, int with_metronome, double metronome_phase_beats);
void sl_stop_playback(sl_engine* e);
int sl_is_playing(sl_engine* e);
double sl_position_seconds(sl_engine* e);
int64_t sl_current_frame(sl_engine* e);
int sl_sample_rate(sl_engine* e);

int64_t sl_output_latency_nanos(sl_engine* e);
// Time (CLOCK_UPTIME_RAW ns on Apple platforms) at which the given stream frame leaves the device; 0 if unknown.
int64_t sl_nanos_for_frame(sl_engine* e, int64_t frame);

// --- listening (microphone) ---
#define SL_HIT_FEATURES 20
int sl_set_listening(sl_engine* e, int on);
void sl_set_click_suppression(sl_engine* e, int on);
int64_t sl_input_latency_nanos(sl_engine* e);
// Fills up to `capacity` hits (features: SL_HIT_FEATURES floats per hit; extra: cents, voices per hit); returns the count.
int sl_poll_hits(sl_engine* e, int64_t* frames, int32_t* classes, float* confidences, float* levels, float* features, float* extra, int capacity);
// 0 = drums, 1 = monophonic (bass, voice), 2 = polyphonic (guitar, keys)
void sl_set_listen_mode(sl_engine* e, int mode);
void sl_learn_profile(sl_engine* e, int midi);
void sl_set_harmonic_profile(sl_engine* e, const float* profile, int count);
// Writes up to `capacity` partial strengths; returns how many there are.
int sl_harmonic_profile(sl_engine* e, float* out, int capacity);
void sl_calibration_add(sl_engine* e, int cls, const float* features);
void sl_calibration_clear(sl_engine* e, int cls);
int64_t sl_sequence_start_frame(sl_engine* e);
int64_t sl_beat_zero_frame(sl_engine* e);
double sl_beat_frames(sl_engine* e);

#ifdef __cplusplus
}
#endif
