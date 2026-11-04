// Loads the shipped SoundFont, plays two notes through the engine and checks their exact start frames.
#include <cmath>
#include <cstdio>
#include <fstream>
#include <vector>
#include "Engine.h"

using namespace setlist;
static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { std::printf("FAILED: %s (line %d)\n", #cond, __LINE__); ++failures; } } while (0)

int main(int argc, char** argv) {
    if (argc < 2) { std::printf("usage: engine_test setlist.sf2\n"); return 1; }
    std::ifstream in(argv[1], std::ios::binary);
    std::vector<char> sf((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    CHECK(sf.size() > 100000);

    const int sr = 48000, block = 192;
    Engine engine;
    engine.setSampleRate(sr);
    CHECK(engine.synth.load(sf.data(), int(sf.size())));
    engine.commands.push({Command::SetPreset, 0, 33, 0.0f});   // bass
    engine.commands.push({Command::SetPreset, 1, 0, 1.0f});    // drums
    engine.commands.push({Command::SetGain, 1, 0, 0.0f});      // muted drums must stay silent

    auto* seq = new std::vector<SeqEvent>{
        {0.25, 0, 40, 100}, {0.75, 0, 40, 0},   // bass note from 0.25 s to 0.75 s
        {0.50, 1, 36, 120}, {0.60, 1, 36, 0},   // kick, but muted
    };
    engine.sequencer.setSequence(seq);
    const int64_t startFrame = 4 * block;  // start a little in the future, mid-block alignment on purpose
    engine.play(startFrame + 7, false);

    std::vector<float> out(block * 2);
    int64_t firstSound = -1;
    int64_t rendered = 0;
    double positionAtOneSecond = -1;
    double peak = 0;
    for (int b = 0; b < sr / block * 2; ++b) {
        engine.render(out.data(), block, 2);
        for (int i = 0; i < block; ++i) {
            float a = std::fabs(out[i * 2]) + std::fabs(out[i * 2 + 1]);
            if (firstSound < 0 && a > 0.0f) firstSound = rendered + i;  // silence before note-on is exactly 0
            peak = std::max<double>(peak, a);
        }
        rendered += block;
        if (positionAtOneSecond < 0 && rendered >= sr) positionAtOneSecond = engine.sequencer.positionSeconds();
    }
    CHECK(firstSound >= 0);
    CHECK(peak > 0.01);
    const int64_t expected = startFrame + 7 + int64_t(0.25 * sr + 0.5);
    std::printf("first sound at frame %lld, expected %lld\n", (long long)firstSound, (long long)expected);
    CHECK(firstSound == expected);
    // One second of audio rendered → position ≈ 1 s minus the start offset.
    double expectedPos = double(sr - (startFrame + 7)) / sr;
    std::printf("position after 1 s: %.4f (expected %.4f)\n", positionAtOneSecond, expectedPos);
    CHECK(std::fabs(positionAtOneSecond - expectedPos) < double(block) / sr);
    CHECK(!engine.sequencer.playing());  // finished: last event + 1 s has passed
    std::printf(failures ? "%d check(s) failed\n" : "all checks passed\n", failures);
    return failures ? 1 : 0;
}
