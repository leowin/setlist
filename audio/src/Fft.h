// Small iterative radix-2 FFT for the listener (real input, power-of-two sizes).
#pragma once
#include <cmath>
#include <complex>
#include <vector>

namespace setlist {

class Fft {
public:
    explicit Fft(int n = 1024) { init(n); }

    void init(int n) {
        n_ = n;
        twiddle_.resize(n / 2);
        for (int i = 0; i < n / 2; ++i) twiddle_[i] = std::polar(1.0f, float(-2.0 * M_PI * i / n));
        rev_.resize(n);
        int bits = 0; while ((1 << bits) < n) ++bits;
        for (int i = 0; i < n; ++i) {
            int r = 0;
            for (int b = 0; b < bits; ++b) if (i & (1 << b)) r |= 1 << (bits - 1 - b);
            rev_[i] = r;
        }
        work_.resize(n);
    }
    int size() const { return n_; }

    // Magnitudes of bins 0..n/2 of the (already windowed) real input.
    void magnitudes(const float* in, float* mag) {
        for (int i = 0; i < n_; ++i) work_[rev_[i]] = std::complex<float>(in[i], 0.0f);
        for (int len = 2; len <= n_; len <<= 1) {
            int half = len / 2, step = n_ / len;
            for (int i = 0; i < n_; i += len) {
                for (int j = 0; j < half; ++j) {
                    std::complex<float> w = twiddle_[j * step];
                    std::complex<float> a = work_[i + j], b = work_[i + j + half] * w;
                    work_[i + j] = a + b;
                    work_[i + j + half] = a - b;
                }
            }
        }
        for (int k = 0; k <= n_ / 2; ++k) mag[k] = std::abs(work_[k]);
    }

private:
    int n_ = 0;
    std::vector<std::complex<float>> twiddle_, work_;
    std::vector<int> rev_;
};

}  // namespace setlist
