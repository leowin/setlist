// Which drum was hit: weighted k-nearest-neighbours over the hit features the user recorded per pad.
#pragma once
#include <algorithm>
#include <cmath>
#include <mutex>
#include <vector>

namespace setlist {

constexpr int kHitFeatures = 20;  // 16 log band energies (mean removed) + centroid + low/high ratio + decay + loudness

class DrumClassifier {
public:
    struct Sample { int cls; float f[kHitFeatures]; };

    void add(int cls, const float* features) {
        std::lock_guard<std::mutex> g(lock_);
        Sample s; s.cls = cls; std::copy(features, features + kHitFeatures, s.f);
        samples_.push_back(s);
        normalize();
    }
    void clear(int cls) {  // cls < 0 clears everything
        std::lock_guard<std::mutex> g(lock_);
        if (cls < 0) samples_.clear();
        else samples_.erase(std::remove_if(samples_.begin(), samples_.end(), [cls](const Sample& s) { return s.cls == cls; }), samples_.end());
        normalize();
    }
    int count(int cls) const {
        std::lock_guard<std::mutex> g(lock_);
        return int(std::count_if(samples_.begin(), samples_.end(), [cls](const Sample& s) { return s.cls == cls; }));
    }
    bool empty() const { std::lock_guard<std::mutex> g(lock_); return samples_.empty(); }

    // Returns the class or -1 when unsure; `confidence` in 0..1.
    int classify(const float* features, float& confidence) const {
        std::lock_guard<std::mutex> g(lock_);
        confidence = 0.0f;
        if (samples_.empty()) return -1;
        struct Near { float d; int cls; };
        std::vector<Near> near;
        near.reserve(samples_.size());
        for (const Sample& s : samples_) {
            float d = 0;
            for (int i = 0; i < kUsed; ++i) { float x = (features[i] - s.f[i]) * scale_[i]; d += x * x; }
            near.push_back({std::sqrt(d), s.cls});
        }
        std::sort(near.begin(), near.end(), [](const Near& a, const Near& b) { return a.d < b.d; });
        int k = std::min<int>(3, int(near.size()));
        std::vector<std::pair<int, float>> votes;
        float total = 0;
        for (int i = 0; i < k; ++i) {
            float w = 1.0f / (near[i].d + 0.05f);
            total += w;
            auto it = std::find_if(votes.begin(), votes.end(), [&](auto& v) { return v.first == near[i].cls; });
            if (it == votes.end()) votes.push_back({near[i].cls, w}); else it->second += w;
        }
        auto best = std::max_element(votes.begin(), votes.end(), [](auto& a, auto& b) { return a.second < b.second; });
        confidence = total > 0 ? best->second / total : 0.0f;
        // Far from everything we know → unknown (distance in z-scored units).
        if (near[0].d > 6.0f) confidence *= 0.5f;
        return confidence >= 0.5f ? best->first : -1;
    }

private:
    static constexpr int kUsed = kHitFeatures - 1;  // loudness does not identify the drum
    void normalize() {
        for (int i = 0; i < kHitFeatures; ++i) scale_[i] = 1.0f;
        if (samples_.size() < 2) return;
        for (int i = 0; i < kHitFeatures; ++i) {
            double mean = 0, var = 0;
            for (const Sample& s : samples_) mean += s.f[i];
            mean /= samples_.size();
            for (const Sample& s : samples_) var += (s.f[i] - mean) * (s.f[i] - mean);
            float sd = float(std::sqrt(var / samples_.size()));
            scale_[i] = 1.0f / std::max(sd, 0.05f);
        }
    }
    mutable std::mutex lock_;
    std::vector<Sample> samples_;
    float scale_[kHitFeatures] = {};
};

}  // namespace setlist
