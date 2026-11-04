#include "Metronome.h"
#include <algorithm>

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

void Metronome::process(float* out, int frames) {
    if (startRequested_.exchange(false)) {
        readConfig();
        frameCounter_ = 0;
        nextClickFrame_ = 0;
        bar_ = -current_.countInBars;
        beat_ = sub_ = 0;
        running_.store(true);
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
