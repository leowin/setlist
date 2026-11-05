// Monophonic pitch (YIN) on 4096-sample frames (85 ms): bass to voice, 40 Hz … 1.2 kHz, a few cents accurate.
#pragma once
#include <cmath>
#include <vector>

namespace setlist {

class PitchTracker {
public:
    static constexpr int kFrame = 4096;

    void setSampleRate(int sampleRate) {
        sampleRate_ = sampleRate;
        // Coarse search on the half-rate signal: lags 20 … 600 ⇒ 1200 … 40 Hz at 48 kHz.
        tauMin_ = std::max(2, int(sampleRate / 2 / 1200.0));
        tauMax_ = std::min(kFrame / 2 - 1, int(sampleRate / 2 / 40.0));
        half_.resize(kFrame / 2);
        d_.resize(tauMax_ + 1);
    }

    // Pitch of one frame (full sample rate). Returns false when there is no clear tone.
    bool analyse(const float* x, float& midi, float& confidence) {
        const int n = kFrame / 2;
        double energy = 0;
        for (int i = 0; i < n; ++i) { half_[i] = 0.5f * (x[2 * i] + x[2 * i + 1]); energy += double(half_[i]) * half_[i]; }
        if (energy / n < 1e-7) return false;   // silence
        // Difference function, normalised by the number of terms so long lags aren't favoured.
        for (int tau = tauMin_; tau <= tauMax_; ++tau) {
            double s = 0;
            const int count = n - tau;
            for (int j = 0; j < count; ++j) { float v = half_[j] - half_[j + tau]; s += double(v) * v; }
            d_[tau] = float(s / count);
        }
        // Cumulative mean normalised difference.
        double running = 0;
        for (int tau = tauMin_; tau <= tauMax_; ++tau) {
            running += d_[tau];
            float mean = float(running / (tau - tauMin_ + 1));
            d_[tau] = mean > 0 ? d_[tau] / mean : 1.0f;
        }
        int best = -1;
        for (int tau = tauMin_ + 1; tau < tauMax_; ++tau) {
            if (d_[tau] < kThreshold && d_[tau] <= d_[tau - 1] && d_[tau] <= d_[tau + 1]) { best = tau; break; }
        }
        if (best < 0) {
            float minV = 1e9f;
            for (int tau = tauMin_ + 1; tau < tauMax_; ++tau) if (d_[tau] < minV) { minV = d_[tau]; best = tau; }
            if (best < 0 || minV > 0.5f) return false;
        }
        confidence = std::max(0.0f, 1.0f - d_[best]);
        // Refine at the full rate: raw difference around 2·best over the whole frame, parabolic dip.
        int centre = 2 * best, lo = std::max(1, centre - 3), hi = std::min(kFrame - 2, centre + 3);
        float fine[8]; int fineBest = lo;
        for (int tau = lo; tau <= hi; ++tau) {
            double s = 0; const int count = kFrame - tau;
            for (int j = 0; j < count; ++j) { float v = x[j] - x[j + tau]; s += double(v) * v; }
            fine[tau - lo] = float(s / count);
            if (fine[tau - lo] < fine[fineBest - lo]) fineBest = tau;
        }
        double tau = fineBest;
        if (fineBest > lo && fineBest < hi) {
            float y0 = fine[fineBest - lo - 1], y1 = fine[fineBest - lo], y2 = fine[fineBest - lo + 1];
            float denom = y0 - 2 * y1 + y2;
            if (denom != 0) tau += std::max(-1.0f, std::min(1.0f, 0.5f * (y0 - y2) / denom));
        }
        double freq = double(sampleRate_) / tau;
        midi = float(69.0 + 12.0 * std::log2(freq / 440.0));
        return true;
    }

private:
    static constexpr float kThreshold = 0.15f;
    int sampleRate_ = 48000;
    int tauMin_ = 20, tauMax_ = 600;
    std::vector<float> half_, d_;
};

}  // namespace setlist
