#define TSF_IMPLEMENTATION
#define TSF_NO_STDIO
#include "../third_party/tsf.h"
#include "Synth.h"

namespace setlist {

Synth::~Synth() {
    if (current_) tsf_close(current_);
    if (retired_) tsf_close(retired_);
    if (tsf* p = pending_.exchange(nullptr)) tsf_close(p);
}

bool Synth::load(const void* data, int size) {
    tsf* f = tsf_load_memory(data, size);
    if (!f) return false;
    tsf_set_output(f, TSF_STEREO_INTERLEAVED, sampleRate_, -6.0f);
    tsf_set_max_voices(f, 64);
    // Audio thread has consumed the previous pending one → its predecessor can go.
    if (retired_ && pending_.load() == nullptr) { tsf_close(retired_); retired_ = nullptr; }
    if (tsf* old = pending_.exchange(f)) tsf_close(old);  // never reached the audio thread
    return true;
}

void Synth::setSampleRate(int sampleRate) {
    sampleRate_ = sampleRate;
    if (current_) tsf_set_output(current_, TSF_STEREO_INTERLEAVED, sampleRate, -6.0f);
}

void Synth::beginBlock() {
    tsf* next = pending_.exchange(nullptr);
    if (!next) return;
    if (current_) { tsf_note_off_all(current_); retired_ = current_; }
    current_ = next;
    tsf_set_output(current_, TSF_STEREO_INTERLEAVED, sampleRate_, -6.0f);
    applyChannelDefaults();
}

void Synth::applyChannelDefaults() {
    for (int p = 0; p < kParts; ++p) {
        if (gain_[p] == 0.0f && program_[p] == 0 && !drums_[p]) gain_[p] = 1.0f;
        setPreset(p, program_[p], drums_[p]);
        setGain(p, gain_[p]);
    }
}

void Synth::setPreset(int part, int program, bool drums) {
    if (part < 0 || part >= kParts) return;
    program_[part] = program; drums_[part] = drums;
    if (!current_) return;
    if (drums) {
        if (!tsf_channel_set_bank_preset(current_, part, 128, 0)) tsf_channel_set_presetnumber(current_, part, 0, 1);
    } else if (!tsf_channel_set_bank_preset(current_, part, 0, program)) {
        // Program not in our trimmed SoundFont: fall back to the nearest family we ship.
        int fallback = program < 24 ? 0 : program >= 32 && program <= 39 ? 33 : program >= 80 ? 81 : program >= 48 ? 54 : 27;
        if (!tsf_channel_set_bank_preset(current_, part, 0, fallback)) tsf_channel_set_presetnumber(current_, part, 0, 0);
    }
}

void Synth::setGain(int part, float gain) {
    if (part < 0 || part >= kParts) return;
    gain_[part] = gain;
    if (current_) tsf_channel_set_volume(current_, part, gain);
}

void Synth::noteOn(int part, int key, int velocity) {
    if (current_ && part >= 0 && part < kParts) tsf_channel_note_on(current_, part, key, velocity / 127.0f);
}

void Synth::noteOff(int part, int key) {
    if (current_ && part >= 0 && part < kParts) tsf_channel_note_off(current_, part, key);
}

void Synth::allNotesOff() {
    if (current_) tsf_note_off_all(current_);
}

void Synth::render(float* stereo, int frames) {
    if (current_ && frames > 0) tsf_render_float(current_, stereo, frames, 1);
}

}  // namespace setlist
