// Time stretching without pitch change (WSOLA): plays mono PCM at another tempo, e.g. a backing track
// at the song's current BPM. Cheap enough for the audio thread; quality is fine for ±30 %.
#pragma once
#include <algorithm>
#include <atomic>
#include <cmath>
#include <memory>
#include <mutex>
#include <vector>

namespace setlist {

class Stretcher {
public:
    static constexpr int kWindow = 1024;   // synthesis frame (21 ms at 48 kHz)
    static constexpr int kHop = kWindow / 2;
    static constexpr int kSearch = 256;    // ± range for the best-matching analysis position

    Stretcher() {
        window_.resize(kWindow);
        for (int i = 0; i < kWindow; ++i) window_[i] = float(0.5 - 0.5 * std::cos(2.0 * M_PI * (i + 0.5) / kWindow));
        overlap_.assign(kWindow, 0.0f);
    }

    // Start producing output that corresponds to input position `inputPos` (frames, fractional ok).
    void seek(double inputPos) {
        inputPos_ = inputPos;
        std::fill(overlap_.begin(), overlap_.end(), 0.0f);
        filled_ = 0;
        primed_ = false;
    }

    // `ratio` = input frames consumed per output frame (1.25 = play 25 % faster).
    // Produces `frames` output samples (added into `out` with `gain`) from `in`; returns false past the end.
    bool render(const float* in, int inLen, double ratio, float* out, int frames, float gain) {
        bool any = false;
        for (int i = 0; i < frames; ++i) {
            if (filled_ == 0 && !produceHop(in, inLen, ratio)) return any;
            out[i] += gain * hopBuf_[kHop - filled_];
            --filled_;
            any = true;
        }
        return any;
    }

    double inputPos() const { return inputPos_; }

private:
    // Creates the next kHop output samples by overlap-adding one analysis frame.
    bool produceHop(const float* in, int inLen, double ratio) {
        int target = int(inputPos_ + 0.5);
        if (target + kWindow >= inLen) return false;
        int best = target;
        if (primed_) {
            // Choose the frame near `target` that continues the previous output most smoothly.
            int lo = std::max(0, target - kSearch), hi = std::min(inLen - kWindow - 1, target + kSearch);
            double bestScore = -1e30;
            for (int c = lo; c <= hi; c += 4) {
                double s = 0;
                for (int k = 0; k < kHop; k += 2) s += double(in[c + k]) * overlap_[k];
                if (s > bestScore) { bestScore = s; best = c; }
            }
            // Refine around the coarse best.
            int fineLo = std::max(lo, best - 3), fineHi = std::min(hi, best + 3);
            for (int c = fineLo; c <= fineHi; ++c) {
                double s = 0;
                for (int k = 0; k < kHop; ++k) s += double(in[c + k]) * overlap_[k];
                if (s > bestScore) { bestScore = s; best = c; }
            }
        }
        // Overlap-add: first half joins the tail of the previous frame, second half waits for the next.
        for (int k = 0; k < kHop; ++k) hopBuf_[k] = overlap_[k] + in[best + k] * window_[k];
        for (int k = 0; k < kHop; ++k) overlap_[k] = in[best + kHop + k] * window_[kHop + k];
        if (!primed_) {
            // Nothing to fade in from: use the first half as-is (windowed up) to avoid a quiet start.
            for (int k = 0; k < kHop; ++k) hopBuf_[k] = in[best + k] * window_[k] * 2.0f * 0.5f + in[best + k] * (1.0f - window_[k]);
            primed_ = true;
        }
        inputPos_ += kHop * ratio;
        filled_ = kHop;
        return true;
    }

    std::vector<float> window_, overlap_;
    float hopBuf_[kHop] = {};
    int filled_ = 0;
    double inputPos_ = 0;
    bool primed_ = false;
};

/** A backing track slot: decoded mono PCM plus how it maps onto the sequence time. */
class TrackPlayer {
public:
    struct Params {
        double seqStartSeconds = 0;    // sequence time at which the audio position `audioStartSeconds` plays
        double audioStartSeconds = 0;
        double ratio = 1.0;            // audio seconds per sequence second (originalBpm / currentBpm)
        float gain = 1.0f;
        bool active = false;
    };

    // UI thread: hand over PCM (ownership) and parameters; the audio thread picks them up.
    void load(std::vector<float>* pcm, int sampleRate) { delete pendingPcm_.exchange(pcm); pcmRate_.store(sampleRate); }
    void setParams(const Params& p) { std::lock_guard<std::mutex> g(lock_); pendingParams_ = p; hasPending_ = true; }
    void setGain(float g) { gain_.store(g); }

    // Audio thread: mixes `frames` mono samples at sequence time `seqSeconds` (of out[0]) into out.
    void render(float* out, int frames, double seqSeconds, int sampleRate) {
        if (std::vector<float>* next = pendingPcm_.exchange(nullptr)) { delete retired_; retired_ = pcm_; pcm_ = next; needSeek_ = true; }
        { std::lock_guard<std::mutex> g(lock_); if (hasPending_) { params_ = pendingParams_; hasPending_ = false; needSeek_ = true; } }
        if (!pcm_ || !params_.active || pcm_->empty()) return;
        // Silence until the sequence reaches the cue (count-in, lead time); start exactly on it.
        if (seqSeconds < params_.seqStartSeconds) {
            int skip = int((params_.seqStartSeconds - seqSeconds) * sampleRate);
            if (skip >= frames) return;
            out += skip; frames -= skip; seqSeconds = params_.seqStartSeconds; needSeek_ = true;
        }
        const double rate = pcmRate_.load() > 0 ? pcmRate_.load() : sampleRate;
        const double ratioFrames = params_.ratio * rate / sampleRate;   // input frames per output frame
        double wantInput = (params_.audioStartSeconds + (seqSeconds - params_.seqStartSeconds) * params_.ratio) * rate;
        if (wantInput < 0) {
            // Not yet: skip the silent lead-in, then start exactly on time.
            int skip = int(-wantInput / ratioFrames);
            if (skip >= frames) return;
            out += skip; frames -= skip; wantInput = 0; needSeek_ = true;
        }
        // Re-seek when the engine jumped (loop wrap, new start) or we drifted by more than a hop.
        if (needSeek_ || std::fabs(stretcher_.inputPos() - wantInput) > Stretcher::kHop * ratioFrames * 2) { stretcher_.seek(wantInput); needSeek_ = false; }
        stretcher_.render(pcm_->data(), int(pcm_->size()), ratioFrames, out, frames, gain_.load() * params_.gain);
    }

    ~TrackPlayer() { delete pcm_; delete retired_; delete pendingPcm_.exchange(nullptr); }

private:
    std::vector<float>* pcm_ = nullptr;
    std::vector<float>* retired_ = nullptr;
    std::atomic<std::vector<float>*> pendingPcm_{nullptr};
    std::atomic<int> pcmRate_{0};
    std::atomic<float> gain_{1.0f};
    std::mutex lock_;
    Params params_, pendingParams_;
    bool hasPending_ = false, needSeek_ = true;
    Stretcher stretcher_;
};

}  // namespace setlist
