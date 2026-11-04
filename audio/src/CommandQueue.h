// Single-producer/single-consumer ring for small control messages from the UI to the audio thread.
#pragma once
#include <atomic>
#include <cstdint>

namespace setlist {

struct Command {
    enum Type : uint8_t { SetPreset, SetGain, NoteOn, NoteOff, AllNotesOff } type;
    int16_t part;
    int32_t a;
    float b;
};

class CommandQueue {
public:
    bool push(const Command& c) {
        int head = head_.load(std::memory_order_relaxed);
        int next = (head + 1) % kSize;
        if (next == tail_.load(std::memory_order_acquire)) return false;
        buffer_[head] = c;
        head_.store(next, std::memory_order_release);
        return true;
    }
    bool pop(Command& c) {
        int tail = tail_.load(std::memory_order_relaxed);
        if (tail == head_.load(std::memory_order_acquire)) return false;
        c = buffer_[tail];
        tail_.store((tail + 1) % kSize, std::memory_order_release);
        return true;
    }

private:
    static constexpr int kSize = 512;
    Command buffer_[kSize];
    std::atomic<int> head_{0}, tail_{0};
};

}  // namespace setlist
