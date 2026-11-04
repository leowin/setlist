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

#ifdef __cplusplus
}
#endif
