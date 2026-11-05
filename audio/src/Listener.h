// Listens to the microphone: finds the exact moment of every hit (spectral flux + energy rise),
// describes its sound (band energies, brightness, decay) and hands the result to the UI thread.
#pragma once
#include <atomic>
#include <cstdint>
#include <vector>
#include "ChordEstimator.h"
#include "DrumClassifier.h"
#include "Fft.h"
#include "PitchTracker.h"

namespace setlist {

struct HitEvent {
    int64_t frame;           // engine (output clock) frame at which the sound reached the microphone
    int cls;                 // drums: recognised pad (GM key) or -1; melodic: MIDI note
    float confidence;        // 0..1
    float level;             // loudness in dB (0 = full scale)
    float cents;             // melodic: deviation from the MIDI note, -50..50
    int voices;              // melodic: how many notes were heard at this moment (chords)
    float features[kHitFeatures];
};

enum class ListenMode { Drums, Mono, Poly };

class Listener {
public:
    static constexpr int kWindow = 1024;
    static constexpr int kHop = 256;

    void setSampleRate(int sampleRate);
    // Notch out the metronome click so it never counts as a hit (speaker playback).
    void setClickSuppression(bool on) { suppressClick_.store(on); }
    void setEnabled(bool on) { enabled_.store(on); if (!on) reset(); }
    bool enabled() const { return enabled_.load(); }
    void setMode(ListenMode m) { mode_.store(int(m)); }
    ListenMode mode() const { return ListenMode(mode_.load()); }
    DrumClassifier& classifier() { return classifier_; }
    ChordEstimator& chords() { return chords_; }
    // Calibration of a melodic instrument: the next note heard (Poly mode) is taken as `midi` and its
    // partial strengths are folded into the instrument profile used by the chord estimator.
    void learnProfile(int midi) { learnMidi_.store(midi); }
    void setProfile(const std::vector<float>& profile) { learnedProfile_ = profile; learnedCount_ = profile.empty() ? 0 : 1; if (!profile.empty()) chords_.setProfile(profile); }
    bool learning() const { return learnMidi_.load() >= 0; }

    // Audio thread: mono input samples; `firstFrame` is the engine frame of in[0].
    void process(const float* in, int frames, int64_t firstFrame);

    // UI thread: next detected hit, false when there is none.
    bool pop(HitEvent& e);

    // Sensitivity: flux must exceed median × factor + floor.
    void setSensitivity(float factor, float floor) { thresholdFactor_ = factor; thresholdFloor_ = floor; }
    // Gain of the level meter (linear) or automatic (recent peak shown at about -6 dBFS); the hit detection
    // itself adapts to the level and is not affected.
    void setInputGain(float gain, bool automatic) { inputGain_.store(gain); autoGain_.store(automatic); }
    float inputGain() const { return autoGain_.load() ? autoGainValue_.load() : inputGain_.load(); }
    // Recent peak level after gain, in dBFS (-120 when silent), for a level meter.
    float inputLevelDb() const { return levelDb_.load(); }

private:
    struct Biquad {
        float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0, z1 = 0, z2 = 0;
        void notch(double freq, double q, int sampleRate);
        inline float run(float x) { float y = b0 * x + z1; z1 = b1 * x - a1 * y + z2; z2 = b2 * x - a2 * y; return y; }
    };
    struct Pending { int64_t frame; int64_t needUntil; };

    void reset();
    void analyseHop();
    void onOnset(int64_t onset);
    void finish(const Pending& p);
    void finishMono(const Pending& p);
    void finishPoly(const Pending& p);
    void trackPitch();
    void push(const HitEvent& e);
    float ringAt(int64_t frame) const { return ring_[size_t(frame % ring_.size())]; }
    float rawAt(int64_t frame) const { return rawRing_[size_t(frame % rawRing_.size())]; }

    std::atomic<bool> enabled_{false}, suppressClick_{false};
    std::atomic<int> mode_{0};
    std::atomic<float> inputGain_{1.0f}, autoGainValue_{1.0f}, levelDb_{-120.0f};
    std::atomic<bool> autoGain_{true};
    float rawPeak_ = 0.0f, peakHold_ = 0.0f;
    std::atomic<int> learnMidi_{-1};
    int learnedCount_ = 0;
    std::vector<float> learnedProfile_;
    PitchTracker pitch_;
    ChordEstimator chords_;
    // Mono: pitch track (10.7 ms steps) and the note being formed.
    struct PitchFrame { int64_t frame; float midi; float conf; };
    std::vector<PitchFrame> track_;
    int64_t nextPitch_ = 0;
    int64_t noteStart_ = -1;       // start of the note currently sounding (-1 none)
    int noteMidi_ = -1;            // its rounded pitch once emitted
    int jumpCount_ = 0;
    int64_t lastMonoOnset_ = -1;
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
