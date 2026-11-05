#include "CoreAudioEngine.h"
#import <AVFoundation/AVFoundation.h>
#import <AudioToolbox/AudioToolbox.h>
#include <mach/mach_time.h>

namespace setlist {

static uint64_t hostTimeToNanos(uint64_t hostTime) {
    static mach_timebase_info_data_t info = {0, 0};
    if (info.denom == 0) mach_timebase_info(&info);
    return hostTime * info.numer / info.denom;
}

static OSStatus renderCallback(void* refCon, AudioUnitRenderActionFlags*, const AudioTimeStamp* timestamp,
                               UInt32, UInt32 frames, AudioBufferList* buffers) {
    auto* self = static_cast<CoreAudioEngine*>(refCon);
    float* out = static_cast<float*>(buffers->mBuffers[0].mData);
    int channels = int(buffers->mBuffers[0].mNumberChannels);
    int64_t frameAtStart = self->engine.currentFrame();
    self->engine.render(out, int(frames), channels);
    self->rendered(frameAtStart, (timestamp->mFlags & kAudioTimeStampHostTimeValid) ? timestamp->mHostTime : 0, int(frames));
    return noErr;
}

static OSStatus inputCallback(void* refCon, AudioUnitRenderActionFlags* flags, const AudioTimeStamp* timestamp,
                              UInt32 bus, UInt32 frames, AudioBufferList*) {
    auto* self = static_cast<CoreAudioEngine*>(refCon);
    if (self->inputBuffer.size() < frames) self->inputBuffer.resize(frames);
    AudioBufferList list;
    list.mNumberBuffers = 1;
    list.mBuffers[0].mNumberChannels = 1;
    list.mBuffers[0].mDataByteSize = frames * sizeof(float);
    list.mBuffers[0].mData = self->inputBuffer.data();
    if (AudioUnitRender(static_cast<AudioComponentInstance>(self->unit()), flags, timestamp, bus, frames, &list) != noErr) return noErr;
    self->captured(self->inputBuffer.data(), int(frames), (timestamp->mFlags & kAudioTimeStampHostTimeValid) ? timestamp->mHostTime : 0);
    return noErr;
}

bool CoreAudioEngine::open() {
    AVAudioSession* session = [AVAudioSession sharedInstance];
    if (wantInput_) {
        [session setCategory:AVAudioSessionCategoryPlayAndRecord
                        mode:AVAudioSessionModeMeasurement
                     options:AVAudioSessionCategoryOptionMixWithOthers | AVAudioSessionCategoryOptionDefaultToSpeaker | AVAudioSessionCategoryOptionAllowBluetooth
                       error:nil];
    } else {
        [session setCategory:AVAudioSessionCategoryPlayback withOptions:AVAudioSessionCategoryOptionMixWithOthers error:nil];
    }
    [session setPreferredIOBufferDuration:0.005 error:nil];
    [session setActive:YES error:nil];
    sampleRate_ = session.sampleRate > 0 ? session.sampleRate : 48000.0;

    AudioComponentDescription desc = {kAudioUnitType_Output, kAudioUnitSubType_RemoteIO, kAudioUnitManufacturer_Apple, 0, 0};
    AudioComponent component = AudioComponentFindNext(nullptr, &desc);
    AudioComponentInstance unit;
    if (AudioComponentInstanceNew(component, &unit) != noErr) return false;

    AudioStreamBasicDescription format = {};
    format.mSampleRate = sampleRate_;
    format.mFormatID = kAudioFormatLinearPCM;
    format.mFormatFlags = kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked;
    format.mChannelsPerFrame = 2;
    format.mBitsPerChannel = 32;
    format.mBytesPerFrame = 8;
    format.mFramesPerPacket = 1;
    format.mBytesPerPacket = 8;
    AudioUnitSetProperty(unit, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Input, 0, &format, sizeof(format));

    AURenderCallbackStruct callback = {renderCallback, this};
    AudioUnitSetProperty(unit, kAudioUnitProperty_SetRenderCallback, kAudioUnitScope_Input, 0, &callback, sizeof(callback));
    if (wantInput_) {
        UInt32 one = 1;
        AudioUnitSetProperty(unit, kAudioOutputUnitProperty_EnableIO, kAudioUnitScope_Input, 1, &one, sizeof(one));
        AudioStreamBasicDescription inFormat = format;
        inFormat.mChannelsPerFrame = 1; inFormat.mBytesPerFrame = 4; inFormat.mBytesPerPacket = 4;
        AudioUnitSetProperty(unit, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Output, 1, &inFormat, sizeof(inFormat));
        AURenderCallbackStruct inCallback = {inputCallback, this};
        AudioUnitSetProperty(unit, kAudioOutputUnitProperty_SetInputCallback, kAudioUnitScope_Global, 1, &inCallback, sizeof(inCallback));
        inputLatencyNanos_.store(int64_t((session.inputLatency + session.IOBufferDuration) * 1e9));
    }
    if (AudioUnitInitialize(unit) != noErr) { AudioComponentInstanceDispose(unit); return false; }

    engine.setSampleRate(int(sampleRate_));
    latencyNanos_.store(int64_t((session.outputLatency + session.IOBufferDuration) * 1e9));
    unit_ = unit;
    return true;
}

bool CoreAudioEngine::start() {
    std::lock_guard<std::mutex> guard(lock_);
    if (!unit_ && !open()) return false;
    if (!running_) running_ = AudioOutputUnitStart(static_cast<AudioComponentInstance>(unit_)) == noErr;
    return running_;
}

void CoreAudioEngine::stop() {
    std::lock_guard<std::mutex> guard(lock_);
    if (unit_) {
        AudioOutputUnitStop(static_cast<AudioComponentInstance>(unit_));
        AudioUnitUninitialize(static_cast<AudioComponentInstance>(unit_));
        AudioComponentInstanceDispose(static_cast<AudioComponentInstance>(unit_));
        unit_ = nullptr;
    }
    running_ = false;
}

void CoreAudioEngine::rendered(int64_t frameAtBlockStart, uint64_t hostTime, int) {
    if (hostTime == 0) return;
    // The timestamp says when this block's first frame is presented; output latency comes on top.
    lastFrame_.store(frameAtBlockStart);
    lastHostNanos_.store(int64_t(hostTimeToNanos(hostTime)));
}

int64_t CoreAudioEngine::outputLatencyNanos() { return latencyNanos_.load(); }
int64_t CoreAudioEngine::inputLatencyNanos() { return inputLatencyNanos_.load(); }

bool CoreAudioEngine::setListening(bool on) {
    {
        std::lock_guard<std::mutex> guard(lock_);
        if (wantInput_ == on && unit_) { engine.listener.setEnabled(on); return true; }
        wantInput_ = on;
        engine.listener.setEnabled(on);
    }
    // The audio unit has to be rebuilt with the input bus enabled (or disabled).
    stop();
    return start();
}

void CoreAudioEngine::captured(const float* mono, int frames, uint64_t hostTime) {
    // Put the captured block on the output clock: the input stamp says when the first sample hit the mic
    // (less the input latency); the last render stamp says when the engine's frame reached the speaker.
    int64_t outNanos = lastHostNanos_.load();
    int64_t engineFrame;
    if (hostTime != 0 && outNanos != 0) {
        int64_t captureNanos = int64_t(hostTimeToNanos(hostTime)) - inputLatencyNanos_.load();
        engineFrame = lastFrame_.load() + int64_t((captureNanos - (outNanos + latencyNanos_.load())) * sampleRate_ / 1e9);
    } else {
        engineFrame = engine.currentFrame() - int64_t((latencyNanos_.load() + inputLatencyNanos_.load()) * sampleRate_ / 1e9);
    }
    engine.listener.process(mono, frames, engineFrame);
}

int64_t CoreAudioEngine::nanosForFrame(int64_t frame) {
    int64_t t = lastHostNanos_.load();
    if (t == 0) return 0;
    return t + latencyNanos_.load() + int64_t((frame - lastFrame_.load()) * 1e9 / sampleRate_);
}

}  // namespace setlist
