#include "Metronome.h"
#include <algorithm>
#include <cmath>

namespace setlist {

void Metronome::setSampleRate(int sampleRate) {
    sampleRate_ = sampleRate;
    synth_.setSampleRate(sampleRate);
}

void Metronome::setConfig(const TransportConfig& c) {
    bpm_.store(c.bpm);
    numerator_.store(c.numerator);
    denominator_.store(c.denominator);
    subdivision_.store(std::clamp(c.subdivision, 1, 8));
    countInBars_.store(std::max(0, c.countInBars));
}

void Metronome::readConfig() {
    current_.bpm = bpm_.load();
    current_.numerator = numerator_.load();
    current_.denominator = denominator_.load();
    current_.subdivision = subdivision_.load();
    current_.countInBars = countInBars_.load();
}

void Metronome::advance() {
    if (++sub_ >= current_.subdivision) {
        sub_ = 0;
        if (++beat_ >= current_.numerator) {
            beat_ = 0;
            ++bar_;
        }
        readConfig();  // tempo/meter changes apply on the beat
    }
    nextClickFrame_ += current_.subSeconds() * sampleRate_;
}

void Metronome::process(float* out, int frames, int64_t blockStartFrame) {
    if (startRequested_.load()) {
        int64_t startFrame = startFrame_.load();
        if (startFrame < 0 || startFrame < blockStartFrame + frames) {
            startRequested_.store(false);
            readConfig();
            frameCounter_ = 0;
            double offset = startFrame < 0 ? 0 : double(std::max<int64_t>(0, startFrame - blockStartFrame));
            double phase = phase_.load();
            if (phase > 0.0) {
                // Mid-bar start: the grid is anchored so that `offset` corresponds to `phase` beats
                // into bar 0; the first click is the next sub-step at or after that point.
                double subPos = phase * current_.subdivision;
                double subFrames = current_.subSeconds() * sampleRate_;
                int idx = int(std::ceil(subPos - 1e-6));
                nextClickFrame_ = offset + (idx - subPos) * subFrames;
                beat_ = (idx / current_.subdivision) % current_.numerator;
                sub_ = idx % current_.subdivision;
                bar_ = (idx / current_.subdivision) / current_.numerator;
            } else {
                nextClickFrame_ = offset;
                bar_ = -current_.countInBars;
                beat_ = sub_ = 0;
            }
            {
                double beatFrames = current_.beatSeconds() * sampleRate_;
                int64_t absStart = blockStartFrame + int64_t(offset);
                beatFrames_.store(beatFrames);
                beatZeroFrame_.store(phase > 0.0 ? absStart - int64_t(phase * beatFrames)
                                                  : absStart + int64_t(current_.countInBars * current_.numerator * beatFrames));
            }
            running_.store(true);
        }
    }
    if (running_.load()) {
        const int64_t blockEnd = frameCounter_ + frames;
        while (nextClickFrame_ < blockEnd) {
            int offset = int(std::max<double>(0.0, nextClickFrame_ - frameCounter_));
            synth_.trigger(clickTypeFor(beat_, sub_), std::min(offset, frames - 1), volume_.load());
            advance();
        }
        frameCounter_ = blockEnd;
        // bar_/beat_/sub_ already point at the *next* click; the position is one sub-step before
        // that plus the fraction of the way we have travelled towards it.
        double subFrames = current_.subSeconds() * sampleRate_;
        double fraction = 1.0 - std::clamp((nextClickFrame_ - frameCounter_) / subFrames, 0.0, 1.0);
        double beats = bar_ * double(current_.numerator) + beat_ + (sub_ + fraction - 1.0) / current_.subdivision;
        positionBeats_.store(beats);
    }
    synth_.render(out, frames);  // let the tail of the last click ring out after stop
}

}  // namespace setlist
