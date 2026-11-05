#include "OboeEngine.h"
#include <android/log.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "SetlistAudio", __VA_ARGS__)

namespace setlist {

bool OboeEngine::open() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        LOG("openStream failed: %s", oboe::convertToText(result));
        stream_.reset();
        return false;
    }
    engine.setSampleRate(stream_->getSampleRate());
    // Two bursts: low latency, still safe on most devices.
    stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 2);
    LOG("stream opened: %d Hz, burst %d", stream_->getSampleRate(), stream_->getFramesPerBurst());
    return true;
}

bool OboeEngine::openInput() {
    if (!stream_) return false;
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setSampleRate(stream_->getSampleRate())
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        // No automatic gain or noise suppression on the drums; VoiceRecognition gives healthy levels on
        // most phones, Unprocessed is the fallback (some devices deliver almost nothing with it).
        ->setInputPreset(oboe::InputPreset::VoiceRecognition)
        ->setDataCallback(&inputCallback_)
        ->setErrorCallback(&inputCallback_);
    oboe::Result result = builder.openStream(input_);
    if (result != oboe::Result::OK) {
        builder.setInputPreset(oboe::InputPreset::Unprocessed);
        result = builder.openStream(input_);
    }
    if (result != oboe::Result::OK) {
        LOG("input openStream failed: %s", oboe::convertToText(result));
        input_.reset();
        return false;
    }
    LOG("input opened: %d Hz, %d ch, burst %d", input_->getSampleRate(), input_->getChannelCount(), input_->getFramesPerBurst());
    return input_->requestStart() == oboe::Result::OK;
}

bool OboeEngine::start() {
    std::lock_guard<std::mutex> guard(lock_);
    wantRunning_ = true;
    if (!stream_ && !open()) return false;
    bool ok = stream_->requestStart() == oboe::Result::OK;
    if (ok && wantInput_ && !input_) openInput();
    return ok;
}

void OboeEngine::stop() {
    std::lock_guard<std::mutex> guard(lock_);
    wantRunning_ = false;
    if (input_) { input_->stop(); input_->close(); input_.reset(); }
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
}

bool OboeEngine::setListening(bool on) {
    std::lock_guard<std::mutex> guard(lock_);
    wantInput_ = on;
    engine.listener.setEnabled(on);
    if (!on) {
        if (input_) { input_->stop(); input_->close(); input_.reset(); }
        return true;
    }
    if (!stream_) { if (!open()) return false; stream_->requestStart(); wantRunning_ = true; }
    return input_ != nullptr || openInput();
}

int64_t OboeEngine::inputLatencyNanos() {
    std::lock_guard<std::mutex> guard(lock_);
    if (!input_) return 0;
    auto latency = input_->calculateLatencyMillis();
    return latency ? int64_t(latency.value() * 1e6) : 0;
}

int64_t OboeEngine::outputLatencyNanos() {
    std::lock_guard<std::mutex> guard(lock_);
    if (!stream_) return 0;
    auto latency = stream_->calculateLatencyMillis();
    return latency ? int64_t(latency.value() * 1e6) : 0;
}

int64_t OboeEngine::nanosForFrame(int64_t frame) {
    std::lock_guard<std::mutex> guard(lock_);
    if (!stream_) return 0;
    int64_t position = 0, timeNanos = 0;
    if (stream_->getTimestamp(CLOCK_MONOTONIC, &position, &timeNanos) != oboe::Result::OK) return 0;
    return timeNanos + (frame - position) * 1'000'000'000LL / stream_->getSampleRate();
}

int64_t OboeEngine::engineFrameForInput(oboe::AudioStream* input, int64_t inputFrame) {
    // Both streams stamp "frame X was at the transducer at time T"; put the input frame on the output clock.
    int64_t inPos = 0, inTime = 0, outPos = 0, outTime = 0;
    std::shared_ptr<oboe::AudioStream> out;
    { std::lock_guard<std::mutex> guard(lock_); out = stream_; }
    if (out && input->getTimestamp(CLOCK_MONOTONIC, &inPos, &inTime) == oboe::Result::OK &&
        out->getTimestamp(CLOCK_MONOTONIC, &outPos, &outTime) == oboe::Result::OK) {
        const int64_t sr = out->getSampleRate();
        int64_t captured = inTime + (inputFrame - inPos) * 1'000'000'000LL / sr;
        return outPos + (captured - outTime) * sr / 1'000'000'000LL;
    }
    // No timestamps yet: assume the input buffer lags the engine by its latency.
    auto latency = input->calculateLatencyMillis();
    int64_t lag = latency ? int64_t(latency.value() * input->getSampleRate() / 1000.0) : input->getBufferSizeInFrames();
    return engine.currentFrame() - lag - (input->getFramesRead() - inputFrame);
}

oboe::DataCallbackResult OboeEngine::InputCallback::onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) {
    const float* in = static_cast<const float*>(audioData);
    const int channels = stream->getChannelCount();
    const int64_t firstInputFrame = stream->getFramesRead();
    int64_t engineFrame = owner_->engineFrameForInput(stream, firstInputFrame);
    if (channels == 1) {
        owner_->engine.listener.process(in, numFrames, engineFrame);
    } else {
        auto& mono = owner_->inputMono_;
        if (int(mono.size()) < numFrames) mono.resize(numFrames);
        for (int i = 0; i < numFrames; ++i) mono[i] = in[i * channels];
        owner_->engine.listener.process(mono.data(), numFrames, engineFrame);
    }
    return oboe::DataCallbackResult::Continue;
}

void OboeEngine::InputCallback::onErrorAfterClose(oboe::AudioStream*, oboe::Result error) {
    LOG("input stream closed: %s, reopening", oboe::convertToText(error));
    std::lock_guard<std::mutex> guard(owner_->lock_);
    owner_->input_.reset();
    if (owner_->wantInput_ && owner_->stream_) owner_->openInput();
}

oboe::DataCallbackResult OboeEngine::onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) {
    engine.render(static_cast<float*>(audioData), numFrames, stream->getChannelCount());
    return oboe::DataCallbackResult::Continue;
}

void OboeEngine::onErrorAfterClose(oboe::AudioStream*, oboe::Result error) {
    // Typical cause: headphones plugged in or out. Reopen on the new device.
    LOG("stream closed: %s, reopening", oboe::convertToText(error));
    std::lock_guard<std::mutex> guard(lock_);
    stream_.reset();
    if (wantRunning_ && open()) stream_->requestStart();
}

}  // namespace setlist
