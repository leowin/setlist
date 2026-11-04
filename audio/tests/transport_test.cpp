// Same cases as shared/src/commonTest/.../MetronomeTest.kt, run through the real-time metronome.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>
#include "Metronome.h"

using namespace setlist;

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { std::printf("FAILED: %s (line %d)\n", #cond, __LINE__); ++failures; } } while (0)

// Runs the metronome offline and returns the frame of every click. A click is a fresh attack after
// the envelope (peak follower) has died down, so the zero crossings inside a click don't count.
static std::vector<double> clickFrames(const TransportConfig& c, int sampleRate, double seconds, int block = 256) {
    Metronome m;
    m.setSampleRate(sampleRate);
    m.setConfig(c);
    m.setVolume(1.0f);
    m.start();
    std::vector<float> buf(block);
    std::vector<double> clicks;
    int64_t total = int64_t(seconds * sampleRate);
    bool quiet = true;
    float peak = 0.0f;
    for (int64_t pos = 0; pos < total; pos += block) {
        std::fill(buf.begin(), buf.end(), 0.0f);
        m.process(buf.data(), block, pos);
        for (int i = 0; i < block; ++i) {
            float a = std::fabs(buf[i]);
            peak = std::max(a, peak * 0.997f);
            if (quiet && a > 0.02f && pos + i < total) { clicks.push_back(double(pos + i)); quiet = false; }
            if (!quiet && peak < 0.001f) quiet = true;
        }
    }
    return clicks;
}

static bool near(double a, double b, double tol) { return std::fabs(a - b) <= tol; }

int main() {
    const int sr = 48000;
    const double tol = 64;  // frames (~1.3 ms, attack detection threshold)

    {   // 4/4 at 120: clicks every 0.5 s
        auto c = clickFrames(TransportConfig{120.0, 4, 4, 1, 0}, sr, 2.0);
        std::printf("4/4 clicks:");
        for (double f : c) std::printf(" %.0f", f);
        std::printf("\n");
        CHECK(c.size() == 4);
        for (size_t i = 0; i < c.size(); ++i) CHECK(near(c[i], i * 0.5 * sr, tol));
    }
    {   // 6/8 at 120: eighths of 0.25 s
        auto c = clickFrames(TransportConfig{120.0, 6, 8, 1, 0}, sr, 1.5);
        CHECK(c.size() == 6);
        CHECK(near(c[1], 0.25 * sr, tol));
        CHECK(near(c[5], 1.25 * sr, tol));
    }
    {   // 7/8 at 140 with eighth subdivision: 14 clicks per bar
        TransportConfig cfg{140.0, 7, 8, 2, 0};
        auto c = clickFrames(cfg, sr, cfg.beatSeconds() * 7);
        CHECK(c.size() == 14);
        CHECK(near(c[1], cfg.subSeconds() * sr, tol));
    }
    {   // count-in: one bar of 3/4 before bar 0, position is negative meanwhile
        Metronome m;
        m.setSampleRate(sr);
        m.setConfig(TransportConfig{120.0, 3, 4, 1, 1});
        m.start();
        std::vector<float> buf(256);
        int64_t frame = 0;
        m.process(buf.data(), 256, frame); frame += 256;
        CHECK(m.positionBeats() < 0);
        for (int i = 0; i < (int)(1.6 * sr / 256); ++i) { m.process(buf.data(), 256, frame); frame += 256; }
        std::printf("position after 1.6 s with 1 bar count-in: %.3f\n", m.positionBeats());
        CHECK(m.positionBeats() >= 0 && m.positionBeats() < 1);
    }
    {   // long run: 10 minutes at 120 bpm stays within one frame of the grid (no drift)
        auto c = clickFrames(TransportConfig{120.0, 4, 4, 1, 0}, sr, 600.0, 192);
        CHECK(c.size() == 1200);
        CHECK(near(c.back(), 1199 * 0.5 * sr, tol));
    }
    {   // mid-bar start: phase 2.5 beats in 4/4 at 120 → first click (beat 3 "and" is a sub-step only with
        // subdivision; with quarters the next click is beat 4 at +0.25 s) and the position reads 2.5 at start
        Metronome m;
        m.setSampleRate(sr);
        m.setConfig(TransportConfig{120.0, 4, 4, 1, 2});  // count-in must be ignored
        m.startAt(0, 2.5);
        std::vector<float> buf(256);
        m.process(buf.data(), 256, 0);
        CHECK(near(m.positionBeats(), 2.5 + 256.0 / (0.5 * sr), 0.01));
        int64_t firstClick = -1;
        for (int64_t pos = 0; pos < sr && firstClick < 0; pos += 256) {
            if (pos > 0) { std::fill(buf.begin(), buf.end(), 0.0f); m.process(buf.data(), 256, pos); }
            for (int i = 0; i < 256; ++i) if (std::fabs(buf[i]) > 0.02f) { firstClick = pos + i; break; }
        }
        CHECK(near(firstClick, 0.25 * sr, tol));
    }
    {   // pattern helper matches the Kotlin reference
        CHECK(clickTypeFor(0, 0) == ClickType::Accent);
        CHECK(clickTypeFor(2, 0) == ClickType::Beat);
        CHECK(clickTypeFor(0, 1) == ClickType::Sub);
    }
    std::printf(failures ? "%d check(s) failed\n" : "all checks passed\n", failures);
    return failures ? 1 : 0;
}
