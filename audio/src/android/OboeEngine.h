#pragma once
#include <memory>
#include <mutex>
#include <oboe/Oboe.h>
#include "Engine.h"

namespace setlist {

class OboeEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    Engine engine;

    bool start();
    void stop();
    int64_t outputLatencyNanos();
    // Monotonic time at which the given stream frame leaves the device (for Phase 5 sync).
    int64_t nanosForFrame(int64_t frame);

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    bool open();
    std::mutex lock_;
    std::shared_ptr<oboe::AudioStream> stream_;
    bool wantRunning_ = false;
};

}  // namespace setlist
