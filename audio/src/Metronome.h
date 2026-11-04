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
    void start() { startRequested_.store(true); }
    void stop() { running_.store(false); }
    bool running() const { return running_.load(); }

    // Position in beats since bar 0 (negative during count-in). Only meaningful while running.
    double positionBeats() const { return positionBeats_.load(); }

    // Audio thread. Adds clicks to the mono buffer.
    void process(float* out, int frames);

private:
    void readConfig();
    void advance();

    std::atomic<double> bpm_{120.0};
    std::atomic<int> numerator_{4}, denominator_{4}, subdivision_{1}, countInBars_{0};
    std::atomic<float> volume_{0.8f};
    std::atomic<bool> startRequested_{false}, running_{false};
    std::atomic<double> positionBeats_{0.0};

    ClickSynth synth_;
    int sampleRate_ = 48000;
    TransportConfig current_;      // config in use for the current beat
    double nextClickFrame_ = 0;    // relative to start, fractional to avoid drift
    int64_t frameCounter_ = 0;
    int bar_ = 0, beat_ = 0, sub_ = 0;
};

}  // namespace setlist
