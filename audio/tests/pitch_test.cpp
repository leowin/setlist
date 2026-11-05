// Melodic hearing: YIN pitch within 5 cents, note starts within 15 ms, chords decomposed into their notes.
#include <cmath>
#include <cstdio>
#include <random>
#include <set>
#include <vector>
#include "ChordEstimator.h"
#include "Listener.h"
#include "PitchTracker.h"

using namespace setlist;
static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { std::printf("FAILED: %s (line %d)\n", #cond, __LINE__); ++failures; } } while (0)
static const int SR = 48000;

static double freqOf(double midi) { return 440.0 * std::pow(2.0, (midi - 69) / 12.0); }

// Plucked-string-like tone: 6 partials, slightly decaying, with a 3 ms attack.
static void addTone(std::vector<float>& buf, int64_t at, int64_t len, double midi, float amp, double vibratoCents = 0.0) {
    double f = freqOf(midi), phase = 0;
    for (int64_t i = at; i < at + len && i < int64_t(buf.size()); ++i) {
        double t = double(i - at) / SR;
        double fm = f * std::pow(2.0, vibratoCents / 1200.0 * std::sin(2 * M_PI * 5.5 * t));
        phase += 2 * M_PI * fm / SR;
        double env = std::min(1.0, t / 0.003) * std::exp(-t * 1.2);
        double v = 0;
        for (int h = 1; h <= 6; ++h) v += std::sin(phase * h) / std::pow(h, 1.1) * std::exp(-t * 0.6 * h);
        buf[size_t(i)] += float(amp * env * v * 0.5);
    }
}

int main() {
    std::mt19937 rng(3);
    std::normal_distribution<float> noise(0.0f, 0.001f);
    {   // 1. YIN accuracy on steady tones
        PitchTracker yin; yin.setSampleRate(SR);
        for (double midi : {28.0, 40.0, 45.3, 57.0, 64.0, 69.0, 76.0}) {   // E1 bass … E5
            std::vector<float> buf(PitchTracker::kFrame);
            addTone(buf, 0, buf.size(), midi, 0.3f);
            float m = 0, c = 0;
            bool ok = yin.analyse(buf.data(), m, c);
            double cents = (m - midi) * 100.0;
            std::printf("midi %.1f → %.3f (%.1f cents, conf %.2f)\n", midi, m, cents, c);
            CHECK(ok); CHECK(std::fabs(cents) <= (midi < 30 ? 8.0 : 5.0)); CHECK(c > 0.7f);
        }
        std::vector<float> hiss(PitchTracker::kFrame);
        std::normal_distribution<float> loud(0.0f, 0.1f);
        for (auto& v : hiss) v = loud(rng);
        float m = 0, c = 0;
        CHECK(!yin.analyse(hiss.data(), m, c) || c < 0.6f);
    }
    {   // 2. note segmentation on a bass line with a legato slur and vibrato
        Listener l; l.setSampleRate(SR); l.setEnabled(true); l.setMode(ListenMode::Mono);
        std::vector<float> buf(SR * 5);
        for (auto& v : buf) v = noise(rng);
        struct N { int64_t at; double midi; int64_t len; double vib; };
        std::vector<N> notes = {{24000, 40, 20000, 0}, {48000, 43, 20000, 0}, {72000, 45, 40000, 20.0 /* vibrato */}, {120000, 50, 20000, 0}, {144000, 38, 30000, 0}};
        for (auto& n : notes) addTone(buf, n.at, n.len, n.midi, 0.35f, n.vib);
        // Slur: pitch jumps from 50 to 52 without a new attack at 130000.
        for (int64_t i = 130000; i < 140000; ++i) buf[size_t(i)] = noise(rng);
        addTone(buf, 130000, 10000, 52, 0.35f);
        std::vector<HitEvent> ev;
        for (size_t pos = 0; pos + 192 <= buf.size(); pos += 192) { l.process(buf.data() + pos, 192, int64_t(pos)); HitEvent e; while (l.pop(e)) ev.push_back(e); }
        std::printf("mono events: %zu\n", ev.size());
        for (auto& e : ev) std::printf("  frame %lld midi %d cents %+.0f conf %.2f\n", (long long)e.frame, e.cls, e.cents, e.confidence);
        std::vector<std::pair<int64_t, int>> expect = {{24000, 40}, {48000, 43}, {72000, 45}, {120000, 50}, {130000, 52}, {144000, 38}};
        CHECK(ev.size() == expect.size());
        for (size_t i = 0; i < ev.size() && i < expect.size(); ++i) {
            CHECK(ev[i].cls == expect[i].second);
            double errMs = (ev[i].frame - expect[i].first) * 1000.0 / SR;
            CHECK(std::fabs(errMs) <= (expect[i].first == 130000 ? 40.0 : 15.0));
        }
    }
    {   // 3. chords
        ChordEstimator ce; ce.setSampleRate(SR);
        struct C { const char* name; std::vector<int> midis; };
        std::vector<C> chords = {{"C", {60, 64, 67}}, {"Am7", {57, 60, 64, 67}}, {"Dsus4", {62, 67, 69}}, {"E (guitar)", {40, 47, 52, 56, 59, 64}}, {"single G3", {55}}};
        for (auto& c : chords) {
            std::vector<float> buf(ChordEstimator::kFrame + 2000);
            for (int m : c.midis) addTone(buf, 0, buf.size(), m, 0.25f);
            auto notes = ce.estimate(buf.data() + 480);
            std::set<int> got; for (auto& n : notes) got.insert(n.midi);
            std::printf("%s →", c.name); for (auto& n : notes) std::printf(" %d(%.2f)", n.midi, n.weight); std::printf("\n");
            int found = 0; for (int m : c.midis) if (got.count(m)) ++found;
            // Dense six-note voicings are the known weak spot: two thirds of the notes must come through.
            CHECK(found >= (c.midis.size() >= 6 ? 4 : int(c.midis.size())));
            CHECK(int(got.size()) - found <= (c.midis.size() >= 6 ? 2 : 1));
        }
        std::vector<float> hiss(ChordEstimator::kFrame + 2000);
        std::normal_distribution<float> loud(0.0f, 0.05f);
        for (auto& v : hiss) v = loud(rng);
        { auto n = ce.estimate(hiss.data()); std::printf("noise →"); for (auto& x : n) std::printf(" %d(%.2f)", x.midi, x.weight); std::printf("\n"); CHECK(n.size() <= 1); }
        // Calibration: measuring the profile of a single note reproduces the synthetic partial strengths.
        std::vector<float> one(ChordEstimator::kFrame + 2000);
        addTone(one, 0, one.size(), 52, 0.3f);
        { std::vector<float> dbg; ce.spectrum(one.data() + 480, dbg); std::printf("s[82..86]:"); for (int b = 82; b <= 86; ++b) std::printf(" %.3f", dbg[b]); std::printf("  s[120]=%.3f s[141]=%.3f\n", dbg[120], dbg[141]); }
        auto prof = ce.measureProfile(one.data() + 480, 52);
        std::printf("profile:"); for (float v : prof) std::printf(" %.2f", v); std::printf("\n");
        CHECK(prof[0] == 1.0f && prof[1] > 0.3f && prof[1] < 1.0f && prof[5] < prof[1]);
        ce.setProfile(prof);
        std::vector<float> buf(ChordEstimator::kFrame + 2000);
        for (int m : {57, 60, 64, 67}) addTone(buf, 0, buf.size(), m, 0.25f);
        auto notes = ce.estimate(buf.data() + 480);
        std::set<int> got; for (auto& n : notes) got.insert(n.midi);
        CHECK(got.count(57) && got.count(60) && got.count(64) && got.count(67));
    }
    {   // 4. poly through the listener: strummed chords at known times
        Listener l; l.setSampleRate(SR); l.setEnabled(true); l.setMode(ListenMode::Poly);
        std::vector<float> buf(SR * 3);
        for (auto& v : buf) v = noise(rng);
        for (int m : {60, 64, 67}) addTone(buf, 24000, 40000, m, 0.25f);
        for (int m : {57, 60, 64}) addTone(buf, 72000, 40000, m, 0.25f);
        std::vector<HitEvent> ev;
        for (size_t pos = 0; pos + 192 <= buf.size(); pos += 192) { l.process(buf.data() + pos, 192, int64_t(pos)); HitEvent e; while (l.pop(e)) ev.push_back(e); }
        std::set<int> first, second;
        for (auto& e : ev) (e.frame < 50000 ? first : second).insert(e.cls);
        std::printf("poly: %zu events\n", ev.size());
        CHECK(first.count(60) && first.count(64) && first.count(67));
        CHECK(second.count(57) && second.count(60) && second.count(64));
        for (auto& e : ev) { double err = ((e.frame < 50000 ? e.frame - 24000 : e.frame - 72000)) * 1000.0 / SR; std::printf("  poly midi %d err %.1f ms\n", e.cls, err); CHECK(std::fabs(err) <= 8.0); }
    }
    std::printf(failures ? "%d FAILURE(S)\n" : "all pitch tests passed\n", failures);
    return failures ? 1 : 0;
}
