// Real-time metronome: runs in the audio callback, controlled from the UI thread via atomics.
#pragma once
#include <atomic>
#include <cstdint>
#include "Click.h"
#include "Transport.h"

namespace setlist {

class Metronome {
public:
    void setSampleRate(int sampleRate);

    // UI thread. Tempo/meter changes take effect at the next beat.
    void setConfig(const TransportConfig& c);
    void setVolume(float v) { volume_.store(v); }
    void start() { startAt(-1); }
    // Starts with the first click exactly on `engineFrame` (-1 = as soon as possible).
    void startAt(int64_t engineFrame) { startFrame_.store(engineFrame); startRequested_.store(true); }
    void stop() { running_.store(false); }
    bool running() const { return running_.load(); }

    // Position in beats since bar 0 (negative during count-in). Only meaningful while running.
    double positionBeats() const { return positionBeats_.load(); }

    // Audio thread. Adds clicks to the mono buffer; `blockStartFrame` is the engine frame of out[0].
    void process(float* out, int frames, int64_t blockStartFrame);

private:
    void readConfig();
    void advance();

    std::atomic<double> bpm_{120.0};
    std::atomic<int> numerator_{4}, denominator_{4}, subdivision_{1}, countInBars_{0};
    std::atomic<float> volume_{0.8f};
    std::atomic<bool> startRequested_{false}, running_{false};
    std::atomic<int64_t> startFrame_{-1};
    std::atomic<double> positionBeats_{0.0};

    ClickSynth synth_;
    int sampleRate_ = 48000;
    TransportConfig current_;      // config in use for the current beat
    double nextClickFrame_ = 0;    // relative to start, fractional to avoid drift
    int64_t frameCounter_ = 0;
    int bar_ = 0, beat_ = 0, sub_ = 0;
};

}  // namespace setlist
