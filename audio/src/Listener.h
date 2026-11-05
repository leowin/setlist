// Listens to the microphone: finds the exact moment of every hit (spectral flux + energy rise),
// describes its sound (band energies, brightness, decay) and hands the result to the UI thread.
#pragma once
#include <atomic>
#include <cstdint>
#include <vector>
#include "DrumClassifier.h"
#include "Fft.h"

namespace setlist {

struct HitEvent {
    int64_t frame;           // engine (output clock) frame at which the sound reached the microphone
    int cls;                 // recognised pad (GM key) or -1
    float confidence;        // 0..1
    float level;             // loudness in dB (0 = full scale)
    float features[kHitFeatures];
};

class Listener {
public:
    static constexpr int kWindow = 1024;
    static constexpr int kHop = 256;

    void setSampleRate(int sampleRate);
    // Notch out the metronome click so it never counts as a hit (speaker playback).
    void setClickSuppression(bool on) { suppressClick_.store(on); }
    void setEnabled(bool on) { enabled_.store(on); if (!on) reset(); }
    bool enabled() const { return enabled_.load(); }
    DrumClassifier& classifier() { return classifier_; }

    // Audio thread: mono input samples; `firstFrame` is the engine frame of in[0].
    void process(const float* in, int frames, int64_t firstFrame);

    // UI thread: next detected hit, false when there is none.
    bool pop(HitEvent& e);

    // Sensitivity: flux must exceed median × factor + floor.
    void setSensitivity(float factor, float floor) { thresholdFactor_ = factor; thresholdFloor_ = floor; }

private:
    struct Biquad {
        float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0, z1 = 0, z2 = 0;
        void notch(double freq, double q, int sampleRate);
        inline float run(float x) { float y = b0 * x + z1; z1 = b1 * x - a1 * y + z2; z2 = b2 * x - a2 * y; return y; }
    };
    struct Pending { int64_t frame; int64_t needUntil; };

    void reset();
    void analyseHop();
    void finish(const Pending& p);
    float ringAt(int64_t frame) const { return ring_[size_t(frame % ring_.size())]; }
    float rawAt(int64_t frame) const { return rawRing_[size_t(frame % rawRing_.size())]; }

    std::atomic<bool> enabled_{false}, suppressClick_{false};
    int sampleRate_ = 48000;
    Fft fft_{kWindow};
    std::vector<float> window_, frame_, mag_, prevMag_, ring_, rawRing_;
    std::vector<bool> clickBin_;
    std::vector<float> fluxHistory_;
    int fluxPos_ = 0;
    int64_t written_ = 0;        // total input frames seen
    int64_t ringOrigin_ = 0;     // engine frame of input frame 0
    int64_t nextHop_ = 0;        // input frame index where the next analysis window starts
    int64_t lastOnset_ = -1;
    float lastFlux_[2] = {0, 0};
    std::vector<Pending> pending_;
    Biquad notch_[3];
    float thresholdFactor_ = 2.0f, thresholdFloor_ = 0.6f;
    DrumClassifier classifier_;
    // SPSC queue of results
    static constexpr int kQueue = 256;
    HitEvent queue_[kQueue];
    std::atomic<int> head_{0}, tail_{0};
    std::vector<float> bandLo_, bandHi_;  // mel-ish band edges in bins
};

}  // namespace setlist
