// Click pattern of the metronome. Mirrored by shared/.../audio/Metronome.kt (MetronomePattern).
#pragma once
#include <cstdint>

namespace setlist {

struct TransportConfig {
    double bpm = 120.0;
    int numerator = 4;
    int denominator = 4;
    int subdivision = 1;  // clicks per beat: 1 = beats only, 2 = eighths, 3 = triplets, 4 = sixteenths
    int countInBars = 0;

    // bpm always refers to quarter notes, so 6/8 at 120 has eighths of 0.25 s.
    double beatSeconds() const { return 60.0 / bpm * 4.0 / denominator; }
    double subSeconds() const { return beatSeconds() / subdivision; }
};

enum class ClickType : uint8_t { Accent, Beat, Sub };

inline ClickType clickTypeFor(int beat, int sub) {
    if (sub > 0) return ClickType::Sub;
    return beat == 0 ? ClickType::Accent : ClickType::Beat;
}

}  // namespace setlist
