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
    // Loop [start, end) seconds of the sequence; end <= start disables looping. Takes effect at the next wrap check.
    void setLoop(double startSeconds, double endSeconds) { loopStart_.store(startSeconds); loopEnd_.store(endSeconds); }
    void stop() { stopRequested_.store(true); }
    bool playing() const { return playing_.load(); }
    double positionSeconds() const { return positionSeconds_.load(); }
    // Engine frame of sequence time 0 (moves forward on each loop wrap).
    int64_t startFrame() const { return startFrame_.load(); }

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
            if (current_ && index_ >= int(current_->size()) && loopEnd_.load() <= loopStart_.load()) {
                double last = current_->empty() ? 0.0 : current_->back().seconds;
                if (double(blockStart - start) / sampleRate > last + 1.0) playing_.store(false);
            }
        } else {
            positionSeconds_.store(-1.0);
        }
    }
    bool takeAllNotesOff() { bool v = allOff_; allOff_ = false; return v; }

    // Absolute frame of the next event or loop wrap, or INT64_MAX.
    int64_t nextEventFrame() const {
        if (!playing_.load()) return INT64_MAX;
        int64_t next = (current_ && index_ < int(current_->size()))
            ? startFrame_.load() + int64_t((*current_)[index_].seconds * sampleRate_ + 0.5) : INT64_MAX;
        return std::min(next, loopWrapFrame());
    }
    // Pops every event at or before `frame`; `onWrap` is called when the loop jumps back (all notes off).
    template <class F, class W> void dispatchUpTo(int64_t frame, F&& f, W&& onWrap) {
        while (true) {
            int64_t wrap = loopWrapFrame();
            int64_t next = nextEventFrame();
            if (next > frame) return;
            if (next == wrap) {
                double start = loopStart_.load(), end = loopEnd_.load();
                startFrame_.store(startFrame_.load() + int64_t((end - start) * sampleRate_ + 0.5));
                index_ = 0;
                if (current_) while (index_ < int(current_->size()) && (*current_)[index_].seconds < start) ++index_;
                onWrap();
            } else {
                f((*current_)[index_++]);
            }
        }
    }

private:
    int64_t loopWrapFrame() const {
        double start = loopStart_.load(), end = loopEnd_.load();
        if (end <= start) return INT64_MAX;
        return startFrame_.load() + int64_t(end * sampleRate_ + 0.5);
    }
    std::atomic<double> loopStart_{0.0}, loopEnd_{0.0};
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
