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

bool OboeEngine::start() {
    std::lock_guard<std::mutex> guard(lock_);
    wantRunning_ = true;
    if (!stream_ && !open()) return false;
    return stream_->requestStart() == oboe::Result::OK;
}

void OboeEngine::stop() {
    std::lock_guard<std::mutex> guard(lock_);
    wantRunning_ = false;
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
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
