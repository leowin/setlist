#include "Listener.h"
#include <algorithm>
#include <cmath>
#include <cstdio>

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
    pitch_.setSampleRate(sampleRate);
    chords_.setSampleRate(sampleRate);
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
    track_.clear(); nextPitch_ = 0; noteStart_ = -1; noteMidi_ = -1; jumpCount_ = 0; lastMonoOnset_ = -1;
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
    if (mode() == ListenMode::Mono) while (written_ >= nextPitch_ + PitchTracker::kFrame) trackPitch();
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
    // Tonal attacks move many harmonic bins at once, so they clear a higher floor; beating partials don't.
    float floorForMode = mode() == ListenMode::Drums ? thresholdFloor_ : thresholdFloor_ * 15.0f;
    float threshold = sorted[sorted.size() / 2] * thresholdFactor_ + floorForMode;
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
#ifdef LISTENER_DEBUG
            std::fprintf(stderr, "onset %lld flux %.2f thr %.2f peakE %.4g base %.4g\n", (long long)onset, candidate, threshold, peakE, baseline);
#endif
            lastOnset_ = onset;
            onOnset(onset);
        }
    }
    fluxHistory_[fluxPos_] = flux;
    fluxPos_ = (fluxPos_ + 1) % kFluxHistory;
    lastFlux_[0] = lastFlux_[1];
    lastFlux_[1] = flux;
}

void Listener::onOnset(int64_t onset) {
    switch (mode()) {
        case ListenMode::Drums: pending_.push_back({onset, onset + kFeatureSpan}); break;
        case ListenMode::Poly: pending_.push_back({onset, onset + sampleRate_ / 100 + ChordEstimator::kFrame}); break;
        case ListenMode::Mono: lastMonoOnset_ = onset; break;   // the pitch track decides when a note is there
    }
}

void Listener::push(const HitEvent& e) {
    int head = head_.load(std::memory_order_relaxed);
    int next = (head + 1) % kQueue;
    if (next == tail_.load(std::memory_order_acquire)) return;  // UI isn't reading; drop
    queue_[head] = e;
    head_.store(next, std::memory_order_release);
}

// Mono: a note is reported once its pitch has held for ~40 ms; it starts at the last onset (if close) or
// where the pitch arrived/jumped. A pitch jump of more than half a semitone starts the next note.
void Listener::trackPitch() {
    const int64_t start = nextPitch_;
    nextPitch_ += 2 * kHop;
    std::vector<float> buf(PitchTracker::kFrame);
    for (int i = 0; i < PitchTracker::kFrame; ++i) buf[i] = ringAt(start + i);
    float midi = 0, conf = 0;
    bool voiced = pitch_.analyse(buf.data(), midi, conf) && conf >= 0.6f;
    PitchFrame f{start, midi, voiced ? conf : 0.0f};
    track_.push_back(f);
    if (track_.size() > 64) track_.erase(track_.begin());
    if (!voiced) {
        // Six unvoiced frames (~65 ms) end the note.
        int quiet = 0; for (auto it = track_.rbegin(); it != track_.rend() && it->conf == 0.0f; ++it) ++quiet;
        if (quiet >= 6) { noteStart_ = -1; noteMidi_ = -1; jumpCount_ = 0; }
        return;
    }
    if (noteStart_ < 0) {
        // New note begins with this frame (or at the onset the flux detector saw just before).
        int64_t begin = start + PitchTracker::kFrame / 4;
        if (lastMonoOnset_ >= 0 && start + PitchTracker::kFrame / 2 - lastMonoOnset_ < sampleRate_ * 120 / 1000) begin = lastMonoOnset_;
        noteStart_ = begin; noteMidi_ = -1; jumpCount_ = 0;
        return;
    }
    if (noteMidi_ >= 0) {
        // Sounding note: watch for a jump (3 frames in a row away from it) or a new attack.
        bool away = std::fabs(midi - noteMidi_) > 0.5f;
        jumpCount_ = away ? jumpCount_ + 1 : 0;
        bool attack = lastMonoOnset_ > noteStart_ && start - lastMonoOnset_ < sampleRate_ * 80 / 1000 && lastMonoOnset_ - noteStart_ > sampleRate_ * 60 / 1000;
        if (jumpCount_ >= 3 || attack) {
            // A pitch reading belongs to the middle of its window: the jump happened about there.
            noteStart_ = attack ? lastMonoOnset_ : track_[track_.size() - 3].frame + PitchTracker::kFrame / 2;
            noteMidi_ = -1; jumpCount_ = 0;
        }
        return;
    }
    // Forming: four voiced frames since the start → emit with the median pitch.
    std::vector<float> ms;
    for (const PitchFrame& t : track_) if (t.frame >= noteStart_ && t.conf > 0) ms.push_back(t.midi);
    if (ms.size() < 4) return;
    std::sort(ms.begin(), ms.end());
    float med = ms[ms.size() / 2];
    HitEvent e{};
    e.frame = ringOrigin_ + noteStart_;
    e.cls = int(std::lround(med));
    e.cents = (med - e.cls) * 100.0f;
    e.confidence = conf;
    e.voices = 1;
    double energy = 0; for (int i = 0; i < 1024; ++i) { float v = ringAt(noteStart_ + i); energy += double(v) * v; }
    e.level = float(10.0 * std::log10(energy / 1024 + 1e-12));
    noteMidi_ = e.cls;
    push(e);
}

void Listener::finishPoly(const Pending& p) {
    std::vector<float> buf(ChordEstimator::kFrame);
    const int64_t from = p.frame + sampleRate_ / 100;   // skip the first 10 ms (attack noise)
    for (int i = 0; i < ChordEstimator::kFrame; ++i) buf[i] = ringAt(from + i);
    int learn = learnMidi_.exchange(-1);
    if (learn >= 0) {
        // Calibration: this note is `learn`; average its partial profile into the instrument's.
        auto w = chords_.measureProfile(buf.data(), learn);
        if (w[0] > 0) {
            if (learnedProfile_.empty()) learnedProfile_.assign(w.size(), 0.0f);
            for (size_t i = 0; i < w.size(); ++i) learnedProfile_[i] = (learnedProfile_[i] * learnedCount_ + w[i]) / (learnedCount_ + 1);
            ++learnedCount_;
            chords_.setProfile(learnedProfile_);
        }
        HitEvent e{}; e.frame = ringOrigin_ + p.frame; e.cls = learn; e.confidence = 1.0f; e.voices = 0;   // voices 0 = "learned"
        push(e);
        return;
    }
    auto notes = chords_.estimate(buf.data());
    double energy = 0; for (int i = 0; i < 1024; ++i) { float v = ringAt(p.frame + i); energy += double(v) * v; }
    float level = float(10.0 * std::log10(energy / 1024 + 1e-12));
    for (const auto& n : notes) {
        HitEvent e{};
        e.frame = ringOrigin_ + p.frame; e.cls = n.midi; e.confidence = n.weight; e.voices = int(notes.size()); e.level = level;
        push(e);
    }
}

void Listener::finish(const Pending& p) {
    if (mode() == ListenMode::Poly) { finishPoly(p); return; }
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
    push(e);
}

bool Listener::pop(HitEvent& e) {
    int tail = tail_.load(std::memory_order_relaxed);
    if (tail == head_.load(std::memory_order_acquire)) return false;
    e = queue_[tail];
    tail_.store((tail + 1) % kQueue, std::memory_order_release);
    return true;
}

}  // namespace setlist
