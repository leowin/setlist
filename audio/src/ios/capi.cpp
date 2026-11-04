// C API over the iOS backend (see capi/setlist_audio.h).
#include "../capi/setlist_audio.h"
#include "CoreAudioEngine.h"

using setlist::CoreAudioEngine;
static CoreAudioEngine* E(sl_engine* e) { return reinterpret_cast<CoreAudioEngine*>(e); }

extern "C" {
sl_engine* sl_create(void) { return reinterpret_cast<sl_engine*>(new CoreAudioEngine()); }
void sl_destroy(sl_engine* e) { E(e)->stop(); delete E(e); }
int sl_start(sl_engine* e) { return E(e)->start() ? 1 : 0; }
void sl_stop(sl_engine* e) { E(e)->stop(); }

void sl_set_transport(sl_engine* e, double bpm, int num, int den, int sub, int countIn) {
    setlist::TransportConfig c; c.bpm = bpm; c.numerator = num; c.denominator = den; c.subdivision = sub; c.countInBars = countIn;
    E(e)->engine.metronome.setConfig(c);
}
void sl_set_click_volume(sl_engine* e, float v) { E(e)->engine.metronome.setVolume(v); }
void sl_metronome_start(sl_engine* e) { E(e)->engine.metronome.start(); }
void sl_metronome_start_at(sl_engine* e, int64_t frame) { E(e)->engine.metronome.startAt(frame); }
void sl_metronome_stop(sl_engine* e) { E(e)->engine.metronome.stop(); }
int sl_metronome_running(sl_engine* e) { return E(e)->engine.metronome.running() ? 1 : 0; }
double sl_position_beats(sl_engine* e) { return E(e)->engine.metronome.positionBeats(); }

int sl_load_soundfont(sl_engine* e, const void* data, int size) { return E(e)->engine.synth.load(data, size) ? 1 : 0; }
void sl_set_part_preset(sl_engine* e, int part, int program, int drums) { E(e)->engine.commands.push({setlist::Command::SetPreset, int16_t(part), program, drums ? 1.0f : 0.0f}); }
void sl_set_part_gain(sl_engine* e, int part, float gain) { E(e)->engine.commands.push({setlist::Command::SetGain, int16_t(part), 0, gain}); }
void sl_note_on(sl_engine* e, int part, int key, int velocity) { E(e)->engine.commands.push({setlist::Command::NoteOn, int16_t(part), key, float(velocity)}); }
void sl_note_off(sl_engine* e, int part, int key) { E(e)->engine.commands.push({setlist::Command::NoteOff, int16_t(part), key, 0.0f}); }
void sl_set_sequence(sl_engine* e, const double* seconds, const int32_t* parts, const int32_t* keys, const int32_t* velocities, int count) {
    auto* events = new std::vector<setlist::SeqEvent>(count);
    for (int i = 0; i < count; ++i) (*events)[i] = {seconds[i], int16_t(parts[i]), uint8_t(keys[i]), uint8_t(velocities[i])};
    E(e)->engine.sequencer.setSequence(events);
}
void sl_set_loop(sl_engine* e, double start, double end) { E(e)->engine.sequencer.setLoop(start, end); }
void sl_play(sl_engine* e, int64_t startFrame, int withMetronome) { E(e)->engine.play(startFrame, withMetronome != 0); }
void sl_stop_playback(sl_engine* e) { E(e)->engine.stopPlayback(); }
int sl_is_playing(sl_engine* e) { return E(e)->engine.sequencer.playing() ? 1 : 0; }
double sl_position_seconds(sl_engine* e) { return E(e)->engine.sequencer.positionSeconds(); }
int64_t sl_current_frame(sl_engine* e) { return E(e)->engine.currentFrame(); }
int sl_sample_rate(sl_engine* e) { return E(e)->engine.sampleRate(); }
int64_t sl_output_latency_nanos(sl_engine* e) { return E(e)->outputLatencyNanos(); }
int64_t sl_nanos_for_frame(sl_engine* e, int64_t frame) { return E(e)->nanosForFrame(frame); }
}
