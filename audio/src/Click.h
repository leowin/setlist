// Tiny click synth: short decaying sine bursts, no samples needed.
#pragma once
#include <cmath>
#include <cstdint>
#include "Transport.h"

namespace setlist {

class ClickSynth {
public:
    static constexpr int kVoices = 4;

    void setSampleRate(int sampleRate) {
        sampleRate_ = sampleRate;
        // Decay to -60 dB within 30 ms.
        decay_ = std::exp(std::log(0.001) / (0.030 * sampleRate));
        attackFrames_ = sampleRate / 1000;  // 1 ms ramp avoids a hard click
    }

    // Starts a click at `offset` frames into the next render block.
    void trigger(ClickType type, int offset, float volume) {
        Voice& v = voices_[next_];
        next_ = (next_ + 1) % kVoices;
        double freq; float amp;
        switch (type) {
            case ClickType::Accent: freq = 1760.0; amp = 1.0f; break;
            case ClickType::Beat:   freq = 1320.0; amp = 0.8f; break;
            default:                freq = 880.0;  amp = 0.45f; break;
        }
        v.phase = 0.0;
        v.phaseInc = 2.0 * M_PI * freq / sampleRate_;
        v.env = 1.0f;
        v.amp = amp * volume;
        v.startOffset = offset;
        v.age = 0;
        v.active = true;
    }

    // Adds the voices to a mono buffer.
    void render(float* out, int frames) {
        for (Voice& v : voices_) {
            if (!v.active) continue;
            for (int i = v.startOffset; i < frames; ++i) {
                float attack = v.age < attackFrames_ ? float(v.age) / attackFrames_ : 1.0f;
                out[i] += float(std::sin(v.phase)) * v.env * v.amp * attack;
                v.phase += v.phaseInc;
                v.env *= decay_;
                ++v.age;
            }
            v.startOffset = 0;
            if (v.env < 0.0005f) v.active = false;
        }
    }

private:
    struct Voice {
        bool active = false;
        double phase = 0, phaseInc = 0;
        float env = 0, amp = 0;
        int startOffset = 0;
        int age = 0;
    };
    Voice voices_[kVoices];
    int next_ = 0;
    int sampleRate_ = 48000;
    float decay_ = 0.99f;
    int attackFrames_ = 48;
};

}  // namespace setlist
