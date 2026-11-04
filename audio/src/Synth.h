// SoundFont synth (TinySoundFont) with one channel per part, driven from the audio thread.
#pragma once
#include <atomic>
#include <cstdint>

struct tsf;

namespace setlist {

class Synth {
public:
    ~Synth();
    // UI thread: parses the SoundFont; the audio thread switches to it at the next block.
    bool load(const void* data, int size);
    bool loaded() const { return current_ != nullptr || pending_.load() != nullptr; }

    // Audio thread
    void setSampleRate(int sampleRate);
    void beginBlock();  // picks up a pending SoundFont
    void setPreset(int part, int program, bool drums);
    void setGain(int part, float gain);
    void noteOn(int part, int key, int velocity);
    void noteOff(int part, int key);
    void allNotesOff();
    void render(float* stereo, int frames);  // mixes into the buffer

private:
    void applyChannelDefaults();
    tsf* current_ = nullptr;
    std::atomic<tsf*> pending_{nullptr};
    tsf* retired_ = nullptr;
    int sampleRate_ = 48000;
    static constexpr int kParts = 16;
    int program_[kParts] = {};
    bool drums_[kParts] = {};
    float gain_[kParts];
};

}  // namespace setlist
