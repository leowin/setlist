// Which notes sound together: a log-frequency spectrum is explained as a non-negative mix of
// harmonic note templates (multiplicative updates); strong weights with a visible fundamental are notes.
#pragma once
#include <algorithm>
#include <cmath>
#include <vector>
#include "Fft.h"

namespace setlist {

class ChordEstimator {
public:
    static constexpr int kFrame = 4096;
    static constexpr int kBinsPerSemitone = 3;
    static constexpr int kLowMidi = 24;   // C1
    static constexpr int kNotes = 72;     // … B6
    static constexpr int kBins = kNotes * kBinsPerSemitone;
    static constexpr int kHarmonics = 6;

    struct NoteOut { int midi; float weight; };

    void setSampleRate(int sampleRate) {
        sampleRate_ = sampleRate;
        fft_.init(kFrame);
        window_.resize(kFrame);
        for (int i = 0; i < kFrame; ++i) window_[i] = float(0.5 - 0.5 * std::cos(2.0 * M_PI * i / kFrame));
        mag_.assign(kFrame / 2 + 1, 0.0f);
        // Map FFT bins onto log-frequency bins (Gaussian, σ = 0.6 bins); kept sparse per log bin.
        const double binHz = double(sampleRate) / kFrame;
        map_.assign(kBins, {});
        for (int b = 0; b < kBins; ++b) {
            double f = freqOfBin(b);
            int kLo = std::max(1, int(f * std::pow(2.0, -2.0 / 36) / binHz)), kHi = std::min(kFrame / 2, int(f * std::pow(2.0, 2.0 / 36) / binHz) + 1);
            for (int k = kLo; k <= kHi; ++k) {
                double dist = std::log2(k * binHz / f) * 12.0 * kBinsPerSemitone;
                map_[b].push_back({k, float(std::exp(-0.5 * dist * dist / 0.36))});
            }
        }
        std::vector<float> dflt(kHarmonics);
        for (int h = 0; h < kHarmonics; ++h) dflt[h] = float(1.0 / std::pow(h + 1, 0.9));
        setProfile(dflt);
    }

    // Relative strengths of the partials 1…6 of the instrument (from calibration; default = 1/h^0.9).
    void setProfile(const std::vector<float>& profile) {
        profile_ = profile; profile_.resize(kHarmonics, 0.0f);
        templates_.assign(kNotes * kBins, 0.0f);
        for (int n = 0; n < kNotes; ++n) {
            for (int h = 0; h < kHarmonics; ++h) {
                double pos = n * kBinsPerSemitone + 12.0 * kBinsPerSemitone * std::log2(h + 1.0);
                for (int b = std::max(0, int(pos) - 2); b <= std::min(kBins - 1, int(pos) + 2); ++b) {
                    double d = b - pos;
                    templates_[n * kBins + b] += profile_[h] * float(std::exp(-0.5 * d * d / 0.5));
                }
            }
        }
    }
    const std::vector<float>& profile() const { return profile_; }

    // Log-frequency spectrum of a 4096-sample frame.
    void spectrum(const float* x, std::vector<float>& out) {
        std::vector<float> frame(kFrame);
        for (int i = 0; i < kFrame; ++i) frame[i] = x[i] * window_[i];
        fft_.magnitudes(frame.data(), mag_.data());
        out.assign(kBins, 0.0f);
        for (int b = 0; b < kBins; ++b) {
            float sum = 0, norm = 0;
            for (auto& m : map_[b]) { sum += mag_[m.first] * m.second; norm += m.second; }
            out[b] = sum / std::max(norm, 1.0f);   // wide high bins don't collect more noise than narrow low ones
        }
    }

    // Notes in the frame, strongest first (at most `maxNotes`).
    std::vector<NoteOut> estimate(const float* x, int maxNotes = 6) {
        std::vector<float> s;
        spectrum(x, s);
        float peak = *std::max_element(s.begin(), s.end());
        std::vector<NoteOut> out;
        if (peak < 1e-3f) return out;
        // Noise has no peaks worth the name: the strongest bin must tower over the typical one.
        std::vector<float> sorted(s);
        std::nth_element(sorted.begin(), sorted.begin() + sorted.size() / 2, sorted.end());
        if (peak < 8.0f * sorted[sorted.size() / 2]) return out;
        std::vector<float> a(kNotes, 1.0f), recon(kBins);
        for (int it = 0; it < 40; ++it) {
            std::fill(recon.begin(), recon.end(), 1e-6f);
            for (int n = 0; n < kNotes; ++n) { const float* t = &templates_[n * kBins]; for (int b = 0; b < kBins; ++b) recon[b] += t[b] * a[n]; }
            for (int n = 0; n < kNotes; ++n) {
                const float* t = &templates_[n * kBins];
                double num = 0, den = 0;
                for (int b = 0; b < kBins; ++b) { num += t[b] * s[b]; den += t[b] * recon[b]; }
                a[n] = float(a[n] * (num / (den + 1e-9)));
            }
        }
        float aMax = *std::max_element(a.begin(), a.end());
        for (int n = 0; n < kNotes; ++n) {
            if (a[n] < 0.3f * aMax) continue;
            // Only local maxima across neighbouring semitones (the templates overlap a little).
            if ((n > 0 && a[n - 1] > a[n]) || (n + 1 < kNotes && a[n + 1] > a[n])) continue;
            // The fundamental itself must be visible, so a harmonic is not reported as its own note.
            int b0 = n * kBinsPerSemitone;
            float f0 = std::max({s[b0], b0 > 0 ? s[b0 - 1] : 0.0f, b0 + 1 < kBins ? s[b0 + 1] : 0.0f});
            if (f0 < 0.15f * peak) continue;
            out.push_back({kLowMidi + n, a[n] / aMax});
        }
        std::sort(out.begin(), out.end(), [](const NoteOut& p, const NoteOut& q) { return p.weight > q.weight; });
        if (int(out.size()) > maxNotes) out.resize(maxNotes);
        return out;
    }

    // Partial strengths of a single played note, for the instrument profile (normalised to the fundamental).
    std::vector<float> measureProfile(const float* x, int midi) {
        std::vector<float> s; spectrum(x, s);
        std::vector<float> w(kHarmonics, 0.0f);
        int n = midi - kLowMidi;
        if (n < 0 || n >= kNotes) return w;
        for (int h = 0; h < kHarmonics; ++h) {
            int pos = int(n * kBinsPerSemitone + 12.0 * kBinsPerSemitone * std::log2(h + 1.0) + 0.5);
            float v = 0;
            for (int b = std::max(0, pos - 1); b <= std::min(kBins - 1, pos + 1); ++b) v = std::max(v, s[b]);
            w[h] = v;
        }
        float f0 = w[0];
        if (f0 > 0) for (auto& v : w) v /= f0;
        return w;
    }

    static double freqOfBin(int b) { return 440.0 * std::pow(2.0, (kLowMidi - 69) / 12.0 + double(b) / (12.0 * kBinsPerSemitone)); }

private:
    int sampleRate_ = 48000;
    Fft fft_{kFrame};
    std::vector<float> window_, mag_, templates_, profile_;
    std::vector<std::vector<std::pair<int, float>>> map_;
};

}  // namespace setlist
