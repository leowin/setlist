// Synthetic drum hits at known positions: every hit found within 3 ms, no doubles, the metronome click
// is ignored when suppression is on, and three different "drums" are told apart after calibration.
#include <cmath>
#include <cstdio>
#include <random>
#include <vector>
#include "Click.h"
#include "Listener.h"

using namespace setlist;
static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { std::printf("FAILED: %s (line %d)\n", #cond, __LINE__); ++failures; } } while (0)

static const int SR = 48000;

// A percussive burst: filtered noise with a fast attack and exponential decay. kind 0 = low (kick), 1 = mid (snare), 2 = high (hat).
static void addHit(std::vector<float>& buf, int64_t at, int kind, float amp, std::mt19937& rng) {
    std::normal_distribution<float> noise(0.0f, 1.0f);
    float lp = 0, decayMs = kind == 0 ? 120.f : kind == 1 ? 80.f : 40.f;
    float decay = std::exp(std::log(0.001f) / (decayMs * 0.001f * SR));
    float env = 1.0f, phase = 0;
    float alpha = kind == 0 ? 0.03f : kind == 1 ? 0.25f : 0.9f;  // one-pole low-pass amount
    for (int64_t i = at; i < at + int64_t(decayMs * 0.001f * SR) && i < int64_t(buf.size()); ++i) {
        float n = noise(rng);
        lp += alpha * (n - lp);
        float tone = kind == 0 ? std::sin(phase += 2.0f * float(M_PI) * 60.0f / SR) * 0.8f : 0.0f;  // kick body
        buf[size_t(i)] += amp * env * (lp + tone);
        env *= decay;
    }
}

static std::vector<HitEvent> run(Listener& l, const std::vector<float>& buf, int block = 192) {
    std::vector<HitEvent> hits;
    for (size_t pos = 0; pos + block <= buf.size(); pos += block) {
        l.process(buf.data() + pos, block, int64_t(pos));
        HitEvent e; while (l.pop(e)) hits.push_back(e);
    }
    return hits;
}

int main() {
    std::mt19937 rng(7);
    std::normal_distribution<float> floor(0.0f, 0.002f);   // room noise

    {   // 1. timing accuracy: eight hits of mixed kinds
        Listener l; l.setSampleRate(SR); l.setEnabled(true);
        std::vector<float> buf(SR * 4);
        for (auto& v : buf) v = floor(rng);
        std::vector<int64_t> at = {24000, 48000 + 123, 72000 + 777, 96000, 120000 + 5000, 150000, 160000, 180000 + 31};
        for (size_t i = 0; i < at.size(); ++i) addHit(buf, at[i], int(i % 3), 0.5f, rng);
        auto hits = run(l, buf);
        std::printf("hits: %zu (expected %zu)\n", hits.size(), at.size());
        CHECK(hits.size() == at.size());
        for (size_t i = 0; i < hits.size() && i < at.size(); ++i) {
            double errMs = (hits[i].frame - at[i]) * 1000.0 / SR;
            std::printf("  hit %zu: frame %lld, expected %lld, error %.2f ms, level %.1f dB\n", i, (long long)hits[i].frame, (long long)at[i], errMs, hits[i].level);
            CHECK(std::fabs(errMs) <= 3.0);
        }
    }
    {   // 2. the click alone is no hit when suppressed; a hit beside it still is
        Listener l; l.setSampleRate(SR); l.setEnabled(true); l.setClickSuppression(true);
        ClickSynth click; click.setSampleRate(SR);
        std::vector<float> buf(SR * 4);
        for (auto& v : buf) v = floor(rng);
        for (int b = 0; b < 6; ++b) {
            std::vector<float> seg(SR / 2, 0.0f);
            click.trigger(b % 4 == 0 ? ClickType::Accent : ClickType::Beat, 0, 0.9f);
            click.render(seg.data(), int(seg.size()));
            for (size_t i = 0; i < seg.size(); ++i) buf[SR / 4 + size_t(b) * SR / 2 + i] += seg[i];   // clicks from 0.25 s on
        }
        addHit(buf, SR / 4 + SR + 3400, 1, 0.5f, rng);   // 71 ms after the third click
        auto hits = run(l, buf);
        std::printf("with click suppression: %zu hit(s):", hits.size());
        for (auto& h : hits) std::printf(" %lld", (long long)h.frame);
        std::printf("\n");
        CHECK(hits.size() == 1);
        if (!hits.empty()) CHECK(std::fabs((hits[0].frame - (SR / 4 + SR + 3400)) * 1000.0 / SR) <= 3.0);
        // Without suppression the clicks are onsets too (that is what the loopback calibration uses).
        Listener l2; l2.setSampleRate(SR); l2.setEnabled(true);
        auto hits2 = run(l2, buf);
        std::printf("without suppression: %zu onsets:", hits2.size());
        for (auto& h : hits2) std::printf(" %lld", (long long)h.frame);
        std::printf("\n");
        CHECK(hits2.size() == 7);
    }
    {   // 3. classification after five calibration hits per drum
        Listener l; l.setSampleRate(SR); l.setEnabled(true);
        std::vector<float> cal(SR * 6);
        for (auto& v : cal) v = floor(rng);
        std::vector<int> kinds;
        for (int k = 0; k < 3; ++k) for (int i = 0; i < 5; ++i) { addHit(cal, int64_t(kinds.size() + 1) * 16000, k, 0.3f + 0.05f * i, rng); kinds.push_back(k); }
        auto hits = run(l, cal);
        std::printf("calibration hits: %zu of %zu\n", hits.size(), kinds.size());
        CHECK(hits.size() == kinds.size());
        for (size_t i = 0; i < hits.size() && i < kinds.size(); ++i) l.classifier().add(36 + kinds[i] * 2, hits[i].features);
        CHECK(l.classifier().count(36) == 5);
        // Fresh hits with other amplitudes and random order.
        Listener t; t.setSampleRate(SR); t.setEnabled(true);
        for (size_t i = 0; i < hits.size(); ++i) t.classifier().add(36 + kinds[i] * 2, hits[i].features);
        std::vector<float> test(SR * 8);
        for (auto& v : test) v = floor(rng);
        std::vector<int> expect;
        for (int i = 0; i < 30; ++i) { int k = (i * 7 + 2) % 3; addHit(test, int64_t(i + 1) * 12000, k, 0.15f + 0.03f * (i % 9), rng); expect.push_back(36 + k * 2); }
        auto got = run(t, test);
        std::printf("test hits: %zu of %zu\n", got.size(), expect.size());
        CHECK(got.size() == expect.size());
        int right = 0;
        for (size_t i = 0; i < got.size() && i < expect.size(); ++i) if (got[i].cls == expect[i]) ++right;
        std::printf("classified %d of %zu correctly\n", right, expect.size());
        CHECK(right >= int(expect.size() * 0.95));
    }
    std::printf(failures ? "%d FAILURE(S)\n" : "all listener tests passed\n", failures);
    return failures ? 1 : 0;
}
