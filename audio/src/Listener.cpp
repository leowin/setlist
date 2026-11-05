#include "Listener.h"
#include <algorithm>
#include <cmath>

namespace setlist {

static constexpr int kRing = 1 << 16;      // 1.37 s at 48 kHz
static constexpr int kFluxHistory = 48;    // ≈ 0.26 s of hops for the adaptive threshold
static constexpr int kSub = 64;            // energy resolution for the exact onset (1.3 ms)
static constexpr int kFeatureSpan = 2048;  // samples after the onset needed for the features

void Listener::Biquad::notch(double freq, double q, int sampleRate) {
    double w0 = 2.0 * M_PI * freq / sampleRate, alpha = std::sin(w0) / (2.0 * q), c = std::cos(w0);
    double a0 = 1.0 + alpha;
    b0 = float(1.0 / a0); b1 = float(-2.0 * c / a0); b2 = float(1.0 / a0);
    a1 = float(-2.0 * c / a0); a2 = float((1.0 - alpha) / a0);
    z1 = z2 = 0;
}

void Listener::setSampleRate(int sampleRate) {
    sampleRate_ = sampleRate;
    window_.resize(kWindow);
    for (int i = 0; i < kWindow; ++i) window_[i] = float(0.5 - 0.5 * std::cos(2.0 * M_PI * i / kWindow));
    frame_.assign(kWindow, 0.0f);
    mag_.assign(kWindow / 2 + 1, 0.0f);
    prevMag_.assign(kWindow / 2 + 1, 0.0f);
    ring_.assign(kRing, 0.0f);
    rawRing_.assign(kRing, 0.0f);
    fluxHistory_.assign(kFluxHistory, 0.0f);
    // The metronome's click tones (Click.h): accent, beat, subdivision.
    const double clicks[3] = {1760.0, 1320.0, 880.0};
    for (int i = 0; i < 3; ++i) notch_[i].notch(clicks[i], 25.0, sampleRate);
    // Bins a click lives in (its spectrum is narrow apart from the attack's splatter).
    clickBin_.assign(kWindow / 2 + 1, false);
    for (double f : clicks) {
        int centre = int(f / (double(sampleRate) / kWindow) + 0.5);
        for (int k = std::max(0, centre - 6); k <= std::min(kWindow / 2, centre + 6); ++k) clickBin_[k] = true;
    }
    // 16 bands, geometrically spaced 60 Hz … 12 kHz, as FFT bin edges.
    bandLo_.resize(16); bandHi_.resize(16);
    double binHz = double(sampleRate) / kWindow;
    for (int b = 0; b < 16; ++b) {
        double lo = 60.0 * std::pow(200.0, b / 16.0), hi = 60.0 * std::pow(200.0, (b + 1) / 16.0);
        bandLo_[b] = float(lo / binHz); bandHi_[b] = float(std::min(hi / binHz, double(kWindow / 2)));
    }
    reset();
}

void Listener::reset() {
    std::fill(ring_.begin(), ring_.end(), 0.0f);
    std::fill(rawRing_.begin(), rawRing_.end(), 0.0f);
    std::fill(prevMag_.begin(), prevMag_.end(), 0.0f);
    std::fill(fluxHistory_.begin(), fluxHistory_.end(), 0.0f);
    lastFlux_[0] = lastFlux_[1] = 0;
    written_ = 0; nextHop_ = 0; lastOnset_ = -1; fluxPos_ = 0;
    pending_.clear();
    for (auto& n : notch_) n.z1 = n.z2 = 0;
}

void Listener::process(const float* in, int frames, int64_t firstFrame) {
    if (!enabled_.load() || ring_.empty()) return;
    // Keep the mapping input frame → engine frame; re-anchor after a gap (stream restart).
    int64_t expected = ringOrigin_ + written_;
    if (written_ == 0 || std::llabs(expected - firstFrame) > kHop) ringOrigin_ = firstFrame - written_;
    const bool suppress = suppressClick_.load();
    for (int i = 0; i < frames; ++i) {
        float x = in[i];
        rawRing_[size_t((written_ + i) % kRing)] = x;
        if (suppress) for (auto& n : notch_) x = n.run(x);
        ring_[size_t((written_ + i) % kRing)] = x;
    }
    written_ += frames;
    while (written_ >= nextHop_ + kWindow) analyseHop();
    // Features need some sound after the onset; finish hits once it has arrived.
    while (!pending_.empty() && written_ >= pending_.front().needUntil) {
        finish(pending_.front());
        pending_.erase(pending_.begin());
    }
}

void Listener::analyseHop() {
    const int64_t start = nextHop_;
    nextHop_ += kHop;
    // Flux is measured on the raw signal so a click shows up as a narrow-band rise and can be told apart.
    for (int i = 0; i < kWindow; ++i) frame_[i] = rawAt(start + i) * window_[i];
    fft_.magnitudes(frame_.data(), mag_.data());
    const float floor = 1e-3f * kWindow / 4;
    float fluxTotal = 0, fluxClick = 0;
    for (int k = 1; k < kWindow / 2; ++k) {
        float a = std::log(std::max(mag_[k], floor)), b = std::log(std::max(prevMag_[k], floor));
        if (a > b) { fluxTotal += a - b; if (clickBin_[k]) fluxClick += a - b; }
    }
    std::swap(mag_, prevMag_);
    const bool suppress = suppressClick_.load();
    float flux = suppress ? fluxTotal - fluxClick : fluxTotal;
    bool clickLike = suppress && fluxTotal > 0 && fluxClick / fluxTotal > 0.45f;
    if (clickLike) flux = 0;   // a rise that is mostly click: not a candidate, and no threshold inflation

    // Peak picking on the previous hop: a local maximum above the adaptive threshold.
    std::vector<float> sorted(fluxHistory_);
    std::nth_element(sorted.begin(), sorted.begin() + sorted.size() / 2, sorted.end());
    float threshold = sorted[sorted.size() / 2] * thresholdFactor_ + thresholdFloor_;
    float candidate = lastFlux_[1];
    bool peak = candidate > threshold && candidate >= lastFlux_[0] && candidate >= flux;
    if (peak) {
        // The candidate window started at `start - kHop`; the attack lies somewhere in it. Follow a
        // short-term energy envelope (5 ms, stepped 1.3 ms): the onset is where it first clears a
        // fifth of its peak in the window and clearly stands above what came before.
        const int64_t from = std::max<int64_t>(0, start - kHop - 4 * kSub), to = start - kHop + kWindow;
        std::vector<double> env;
        for (int64_t s = from; s + 4 * kSub <= to; s += kSub) {
            double e = 0;
            for (int i = 0; i < 4 * kSub; ++i) { float v = ringAt(s + i); e += double(v) * v; }
            env.push_back(e);
        }
        double peakE = 0; for (double e : env) peakE = std::max(peakE, e);
        double baseline = env.empty() ? 0 : env.front();
        int64_t onset = -1;
        for (size_t i = 1; i < env.size(); ++i) {
            if (env[i] >= 0.2 * peakE && env[i] > 3.0 * baseline + 1e-9) {
                // Back up to the 1.3 ms block where the rise actually began.
                onset = from + int64_t(i) * kSub + 3 * kSub;
                int64_t s = onset;
                double prevE = -1;
                while (s - kSub >= from) {
                    double e = 0;
                    for (int k = 0; k < kSub; ++k) { float v = ringAt(s - kSub + k); e += double(v) * v; }
                    if (prevE >= 0 && e > prevE) break;
                    if (e < 0.05 * peakE / 4) break;
                    prevE = e; s -= kSub;
                }
                onset = s;
                break;
            }
        }
        if (onset >= 0 && (lastOnset_ < 0 || onset - lastOnset_ >= sampleRate_ * 50 / 1000)) {
            lastOnset_ = onset;
            pending_.push_back({onset, onset + kFeatureSpan});
        }
    }
    fluxHistory_[fluxPos_] = flux;
    fluxPos_ = (fluxPos_ + 1) % kFluxHistory;
    lastFlux_[0] = lastFlux_[1];
    lastFlux_[1] = flux;
}

void Listener::finish(const Pending& p) {
    HitEvent e{};
    e.frame = ringOrigin_ + p.frame;
    // Spectrum of the first 21 ms after the attack.
    for (int i = 0; i < kWindow; ++i) frame_[i] = ringAt(p.frame + i) * window_[i];
    std::vector<float> mag(kWindow / 2 + 1);
    fft_.magnitudes(frame_.data(), mag.data());
    double total = 0, centroid = 0, low = 0, high = 0;
    const double binHz = double(sampleRate_) / kWindow;
    for (int k = 1; k < kWindow / 2; ++k) {
        double m = mag[k];
        total += m; centroid += m * std::log2(k * binHz);
        if (k * binHz < 300.0) low += m * m; else if (k * binHz > 2000.0) high += m * m;
    }
    double meanBand = 0;
    for (int b = 0; b < 16; ++b) {
        double energy = 0;
        for (int k = int(bandLo_[b]); k < int(bandHi_[b]) && k <= kWindow / 2; ++k) energy += double(mag[k]) * mag[k];
        e.features[b] = float(std::log10(energy + 1e-9));
        meanBand += e.features[b];
    }
    meanBand /= 16;
    for (int b = 0; b < 16; ++b) e.features[b] -= float(meanBand);   // level independent
    e.features[16] = total > 0 ? float(centroid / total) : 0.0f;          // brightness (log2 Hz)
    e.features[17] = float(std::log10((low + 1e-9) / (high + 1e-9)));     // boom vs. sizzle
    double e1 = 0, e2 = 0;
    for (int i = 0; i < kWindow; ++i) { float a = ringAt(p.frame + i), b = ringAt(p.frame + kWindow + i); e1 += double(a) * a; e2 += double(b) * b; }
    e.features[18] = float(10.0 * std::log10((e2 + 1e-9) / (e1 + 1e-9)));  // decay over the next 21 ms
    e.level = float(10.0 * std::log10(e1 / kWindow + 1e-12));
    e.features[19] = e.level;
    e.cls = classifier_.classify(e.features, e.confidence);

    int head = head_.load(std::memory_order_relaxed);
    int next = (head + 1) % kQueue;
    if (next == tail_.load(std::memory_order_acquire)) return;  // UI isn't reading; drop
    queue_[head] = e;
    head_.store(next, std::memory_order_release);
}

bool Listener::pop(HitEvent& e) {
    int tail = tail_.load(std::memory_order_relaxed);
    if (tail == head_.load(std::memory_order_acquire)) return false;
    e = queue_[tail];
    tail_.store((tail + 1) % kQueue, std::memory_order_release);
    return true;
}

}  // namespace setlist
