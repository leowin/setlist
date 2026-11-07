// Time stretching keeps the pitch and changes the length; the track player lines audio up with the sequence clock.
#include <cmath>
#include <cstdio>
#include <vector>
#include "Engine.h"
#include "Stretcher.h"

using namespace setlist;
static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { std::printf("FAILED: %s (line %d)\n", #cond, __LINE__); ++failures; } } while (0)
static const int SR = 48000;

static double zeroCrossingHz(const std::vector<float>& x, size_t from, size_t to) {
    int crossings = 0;
    for (size_t i = from + 1; i < to; ++i) if ((x[i - 1] < 0) != (x[i] < 0)) ++crossings;
    return crossings / 2.0 * SR / double(to - from);
}

int main() {
    // 2 s of a 440 Hz tone with a 2 Hz amplitude envelope (so timing is measurable).
    std::vector<float> in(SR * 2);
    for (size_t i = 0; i < in.size(); ++i) in[i] = float(std::sin(2 * M_PI * 440.0 * i / SR) * (0.6 + 0.4 * std::sin(2 * M_PI * 2.0 * i / SR)));
    for (double ratio : {0.8, 1.0, 1.25}) {
        Stretcher st; st.seek(0);
        std::vector<float> out;
        std::vector<float> block(256);
        while (true) {
            std::fill(block.begin(), block.end(), 0.0f);
            if (!st.render(in.data(), int(in.size()), ratio, block.data(), 256, 1.0f)) break;
            out.insert(out.end(), block.begin(), block.end());
        }
        double expectLen = in.size() / ratio;
        double hz = zeroCrossingHz(out, SR / 4, out.size() - SR / 4);
        std::printf("ratio %.2f: %zu frames (expected ≈ %.0f), pitch %.1f Hz\n", ratio, out.size(), expectLen, hz);
        CHECK(std::fabs(out.size() - expectLen) < SR * 0.1);
        CHECK(std::fabs(hz - 440.0) < 8.0);
        // No big discontinuities: neighbouring samples differ by less than the tone's own slope allows.
        double maxStep = 0; for (size_t i = 1; i < out.size(); ++i) maxStep = std::max(maxStep, double(std::fabs(out[i] - out[i - 1])));
        CHECK(maxStep < 0.12);
    }
    {   // TrackPlayer: audio starts at sequence second 1 with ratio 1, so the tone appears after 1 s of silence.
        TrackPlayer tp;
        tp.load(new std::vector<float>(in), SR);
        TrackPlayer::Params p; p.seqStartSeconds = 1.0; p.audioStartSeconds = 0.0; p.ratio = 1.0; p.gain = 1.0f; p.active = true;
        tp.setParams(p);
        std::vector<float> out(SR * 3, 0.0f);
        for (int b = 0; b < SR * 3 / 256; ++b) tp.render(out.data() + b * 256, 256, b * 256 / double(SR), SR);
        double firstEnergy = 0; for (int i = 0; i < SR; ++i) firstEnergy += std::fabs(out[i]);
        double laterEnergy = 0; for (int i = SR + SR / 10; i < 2 * SR; ++i) laterEnergy += std::fabs(out[i]);
        std::printf("track: energy before start %.3f, after %.1f\n", firstEnergy, laterEnergy);
        CHECK(firstEnergy < 1e-3);
        CHECK(laterEnergy > 1000);
        int first = -1; for (size_t i = 0; i < out.size(); ++i) if (std::fabs(out[i]) > 0.01f) { first = int(i); break; }
        std::printf("first sound at %.1f ms (expected 1000)\n", first * 1000.0 / SR);
        CHECK(std::fabs(first - SR) < SR * 0.01);
    }
    {   // Through the engine: a track plus a far-away dummy event (cue preview) must sound.
        Engine engine; engine.setSampleRate(SR);
        engine.tracks[0].load(new std::vector<float>(in), SR);
        TrackPlayer::Params p; p.seqStartSeconds = 0; p.audioStartSeconds = 0.5; p.ratio = 1.0; p.gain = 1.0f; p.active = true;
        engine.tracks[0].setParams(p);
        engine.sequencer.setSequence(new std::vector<SeqEvent>{{3600.0, 0, 0, 0}});
        engine.play(4800, false);
        std::vector<float> out(192 * 2);
        double energyBefore = 0, energyAfter = 0;
        for (int b = 0; b < SR / 192; ++b) {
            engine.render(out.data(), 192, 2);
            double e = 0; for (float v : out) e += std::fabs(v);
            if (b * 192 < 4800) energyBefore += e; else if (b * 192 > 9600) energyAfter += e;
        }
        std::printf("engine track: before start %.3f, after %.1f\n", energyBefore, energyAfter);
        CHECK(energyBefore < 1e-3);
        CHECK(energyAfter > 100);
        CHECK(engine.sequencer.playing());
    }
    std::printf(failures ? "%d FAILURE(S)\n" : "all stretch tests passed\n", failures);
    return failures ? 1 : 0;
}
