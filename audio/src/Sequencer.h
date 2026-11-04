// Plays a list of timed note events sample-accurately, starting at a given engine frame.
#pragma once
#include <atomic>
#include <cstdint>
#include <vector>

namespace setlist {

struct SeqEvent {
    double seconds;  // relative to the start of playback
    int16_t part;
    uint8_t key;
    uint8_t velocity;  // 0 = note off
};

class Sequencer {
public:
    ~Sequencer() { delete current_; delete retired_; delete pending_.exchange(nullptr); }

    // UI thread. `events` must be sorted by seconds; ownership passes to the sequencer.
    void setSequence(std::vector<SeqEvent>* events) {
        if (retired_ && pending_.load() == nullptr) { delete retired_; retired_ = nullptr; }
        delete pending_.exchange(events);
    }
    void play(int64_t startFrame) { startFrame_.store(startFrame); playRequested_.store(true); }
    void stop() { stopRequested_.store(true); }
    bool playing() const { return playing_.load(); }
    double positionSeconds() const { return positionSeconds_.load(); }

    // Audio thread
    void beginBlock(int64_t blockStart, int frames, int sampleRate) {
        if (std::vector<SeqEvent>* next = pending_.exchange(nullptr)) {
            if (current_) retired_ = current_;
            current_ = next;
            index_ = 0;
        }
        if (stopRequested_.exchange(false)) { playing_.store(false); allOff_ = true; }
        if (playRequested_.exchange(false)) {
            index_ = 0;
            playing_.store(true);
        }
        sampleRate_ = sampleRate;
        if (playing_.load()) {
            int64_t start = startFrame_.load();
            positionSeconds_.store(double(blockStart + frames - start) / sampleRate);
            // Finished: all events sent and the last one is a second behind us.
            if (current_ && index_ >= int(current_->size())) {
                double last = current_->empty() ? 0.0 : current_->back().seconds;
                if (double(blockStart - start) / sampleRate > last + 1.0) playing_.store(false);
            }
        } else {
            positionSeconds_.store(-1.0);
        }
    }
    bool takeAllNotesOff() { bool v = allOff_; allOff_ = false; return v; }

    // Absolute frame of the next event, or INT64_MAX.
    int64_t nextEventFrame() const {
        if (!playing_.load() || !current_ || index_ >= int(current_->size())) return INT64_MAX;
        return startFrame_.load() + int64_t((*current_)[index_].seconds * sampleRate_ + 0.5);
    }
    // Pops every event at or before `frame`.
    template <class F> void dispatchUpTo(int64_t frame, F&& f) {
        while (nextEventFrame() <= frame) f((*current_)[index_++]);
    }

private:
    std::vector<SeqEvent>* current_ = nullptr;
    std::atomic<std::vector<SeqEvent>*> pending_{nullptr};
    std::vector<SeqEvent>* retired_ = nullptr;
    int index_ = 0;
    int sampleRate_ = 48000;
    bool allOff_ = false;
    std::atomic<int64_t> startFrame_{0};
    std::atomic<bool> playRequested_{false}, stopRequested_{false}, playing_{false};
    std::atomic<double> positionSeconds_{-1.0};
};

}  // namespace setlist
