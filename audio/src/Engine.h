// Platform-independent mixer: renders everything into an interleaved buffer.
#pragma once
#include <vector>
#include "Metronome.h"

namespace setlist {

class Engine {
public:
    Metronome metronome;

    void setSampleRate(int sampleRate) {
        metronome.setSampleRate(sampleRate);
        mono_.assign(8192, 0.0f);
    }

    void render(float* out, int frames, int channels) {
        if (int(mono_.size()) < frames) mono_.assign(frames, 0.0f);
        std::fill(mono_.begin(), mono_.begin() + frames, 0.0f);
        metronome.process(mono_.data(), frames);
        for (int i = 0; i < frames; ++i) {
            float s = mono_[i];
            if (s > 1.0f) s = 1.0f; else if (s < -1.0f) s = -1.0f;
            for (int c = 0; c < channels; ++c) out[i * channels + c] = s;
        }
    }

private:
    std::vector<float> mono_;
};

}  // namespace setlist
