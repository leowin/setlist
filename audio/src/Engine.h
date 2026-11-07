// Platform-independent mixer: metronome + SoundFont synth driven by the sequencer.
#pragma once
#include <algorithm>
#include <cstdint>
#include <vector>
#include "CommandQueue.h"
#include "Listener.h"
#include "Stretcher.h"
#include <mutex>
#include "Metronome.h"
#include "Sequencer.h"
#include "Synth.h"

namespace setlist {

class Engine {
public:
    Metronome metronome;
    Sequencer sequencer;
    Synth synth;
    CommandQueue commands;
    Listener listener;
    static constexpr int kTracks = 4;
    TrackPlayer tracks[kTracks];   // backing tracks (MP3 etc.), time-stretched to the sequence tempo

    void setSampleRate(int sampleRate) {
        sampleRate_ = sampleRate;
        metronome.setSampleRate(sampleRate);
        synth.setSampleRate(sampleRate);
        listener.setSampleRate(sampleRate);
        mono_.assign(8192, 0.0f);
    }
    int sampleRate() const { return sampleRate_; }
    // Frames rendered so far; the UI schedules starts relative to this.
    int64_t currentFrame() const { return frame_.load(); }

    // UI thread: start the sequence (and optionally the metronome) at an engine frame.
    void play(int64_t startFrame, bool withMetronome, double metronomePhaseBeats = 0.0) {
        sequencer.play(startFrame);
        if (withMetronome) metronome.startAt(startFrame, metronomePhaseBeats);
        metronomeFollows_ = withMetronome;
    }
    void stopPlayback() {
        sequencer.stop();
        if (metronomeFollows_) metronome.stop();
        tracksPlaying_ = false;
    }

    void render(float* out, int frames, int channels) {
        const int64_t blockStart = frame_.load();
        std::fill(out, out + frames * channels, 0.0f);
        if (int(mono_.size()) < frames) mono_.assign(frames, 0.0f);
        std::fill(mono_.begin(), mono_.begin() + frames, 0.0f);

        synth.beginBlock();
        Command c;
        while (commands.pop(c)) {
            switch (c.type) {
                case Command::SetPreset: synth.setPreset(c.part, c.a, c.b != 0.0f); break;
                case Command::SetGain: synth.setGain(c.part, c.b); break;
                case Command::NoteOn: synth.noteOn(c.part, c.a, int(c.b)); break;
                case Command::NoteOff: synth.noteOff(c.part, c.a); break;
                case Command::AllNotesOff: synth.allNotesOff(); break;
            }
        }
        sequencer.beginBlock(blockStart, frames, sampleRate_);
        if (sequencer.takeAllNotesOff()) synth.allNotesOff();
        tracksPlaying_ = sequencer.playing();

        // Render in chunks between events so every note starts on its exact frame.
        int pos = 0;
        while (pos < frames) {
            int64_t next = sequencer.nextEventFrame();
            int chunkEnd = next >= blockStart + frames ? frames : int(std::max<int64_t>(next - blockStart, pos));
            if (chunkEnd > pos) renderChunk(out, pos, chunkEnd, channels);
            if (chunkEnd < frames) {
                sequencer.dispatchUpTo(blockStart + chunkEnd, [this](const SeqEvent& e) {
                    if (e.velocity) synth.noteOn(e.part, e.key, e.velocity); else synth.noteOff(e.part, e.key);
                }, [this] { synth.allNotesOff(); });
            }
            pos = chunkEnd;
        }
        frame_.store(blockStart + frames);
    }

private:
    void renderChunk(float* out, int from, int to, int channels) {
        int n = to - from;
        float* mono = mono_.data() + from;
        metronome.process(mono, n, frame_.load() + from);
        if (tracksPlaying_) {
            // Backing tracks follow the sequence clock (so loops and synced starts carry them along).
            double seqSeconds = double(frame_.load() + from - sequencer.startFrame()) / sampleRate_;
            for (auto& t : tracks) t.render(mono, n, seqSeconds, sampleRate_);
        }
        if (channels == 2) {
            float* dst = out + from * 2;
            synth.render(dst, n);
            for (int i = 0; i < n; ++i) { dst[i * 2] += mono[i]; dst[i * 2 + 1] += mono[i]; }
            for (int i = 0; i < n * 2; ++i) dst[i] = std::clamp(dst[i], -1.0f, 1.0f);
        } else {
            stereo_.assign(n * 2, 0.0f);
            synth.render(stereo_.data(), n);
            for (int i = 0; i < n; ++i) {
                float s = std::clamp(mono[i] + 0.5f * (stereo_[i * 2] + stereo_[i * 2 + 1]), -1.0f, 1.0f);
                for (int ch = 0; ch < channels; ++ch) out[(from + i) * channels + ch] = s;
            }
        }
    }

    int sampleRate_ = 48000;
    std::atomic<int64_t> frame_{0};
    bool metronomeFollows_ = false;
    bool tracksPlaying_ = false;
    std::vector<float> mono_, stereo_;
};

}  // namespace setlist
