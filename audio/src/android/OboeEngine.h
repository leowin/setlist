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

    // Microphone for the listener; false when it can't be opened (no permission, no mic).
    bool setListening(bool on);
    bool listening() const { return input_ != nullptr; }
    int64_t inputLatencyNanos();

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    class InputCallback : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
    public:
        explicit InputCallback(OboeEngine* owner) : owner_(owner) {}
        oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
        void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;
    private:
        OboeEngine* owner_;
    };
    bool open();
    bool openInput();
    // Engine (output) frame that was in the air when input frame `inputFrame` was captured.
    int64_t engineFrameForInput(oboe::AudioStream* input, int64_t inputFrame);
    std::mutex lock_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::shared_ptr<oboe::AudioStream> input_;
    InputCallback inputCallback_{this};
    bool wantRunning_ = false;
    bool wantInput_ = false;
    std::vector<float> inputMono_;
};

}  // namespace setlist
