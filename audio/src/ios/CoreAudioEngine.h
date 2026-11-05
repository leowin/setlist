// iOS backend: RemoteIO audio unit driving the shared Engine.
#pragma once
#include <atomic>
#include <cstdint>
#include <mutex>
#include <vector>
#include "Engine.h"

namespace setlist {

class CoreAudioEngine {
public:
    Engine engine;
    bool start();
    void stop();
    int64_t outputLatencyNanos();
    int64_t nanosForFrame(int64_t frame);

    // Microphone for the listener (AVAudioSession play-and-record, RemoteIO input bus).
    bool setListening(bool on);
    int64_t inputLatencyNanos();

    // Called from the render callback.
    void rendered(int64_t frameAtBlockStart, uint64_t hostTime, int frames);
    // Called from the input callback with the captured mono samples.
    void captured(const float* mono, int frames, uint64_t hostTime);
    void* unit() const { return unit_; }
    std::vector<float> inputBuffer;

private:
    bool open();
    std::mutex lock_;
    void* unit_ = nullptr;            // AudioComponentInstance
    bool running_ = false;
    double sampleRate_ = 48000.0;
    std::atomic<int64_t> lastFrame_{0};
    std::atomic<int64_t> lastHostNanos_{0};
    std::atomic<int64_t> latencyNanos_{0};
    std::atomic<int64_t> inputLatencyNanos_{0};
    bool wantInput_ = false;
    int64_t inputFrames_ = 0;
};

}  // namespace setlist
