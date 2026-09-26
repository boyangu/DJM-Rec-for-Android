#include "DemoSignalGenerator.h"
#include <cassert>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <vector>

int main() {
    const int rate = 48000;
    djmrec::DemoSignalGenerator generator(rate);
    std::vector<int32_t> buffer(static_cast<size_t>(rate) * 2 * 4); // 4 s stereo
    generator.render(buffer.data(), buffer.size() / 2);

    double peak = 0.0, sumSquares = 0.0;
    bool stereoDiffers = false;
    for (size_t i = 0; i < buffer.size(); i += 2) {
        const double l = buffer[i] / 2147483648.0;
        const double r = buffer[i + 1] / 2147483648.0;
        peak = std::max(peak, std::max(std::fabs(l), std::fabs(r)));
        sumSquares += l * l;
        if (std::fabs(l - r) > 1e-4) stereoDiffers = true;
    }
    const double rmsDb = 10.0 * std::log10(sumSquares / (buffer.size() / 2));
    // Loud enough to read as signal, well clear of clipping.
    assert(peak > 0.3 && peak < 0.95);
    assert(rmsDb > -30.0 && rmsDb < -8.0);
    assert(stereoDiffers);

    // Rendering in small blocks must give exactly the same audio as one big block.
    djmrec::DemoSignalGenerator a(rate), b(rate);
    std::vector<int32_t> whole(2000 * 2), parts(2000 * 2);
    a.render(whole.data(), 2000);
    for (size_t f = 0; f < 2000; f += 441) b.render(parts.data() + f * 2, std::min<size_t>(441, 2000 - f));
    for (size_t i = 0; i < whole.size(); ++i) assert(std::abs(static_cast<long>(whole[i]) - parts[i]) <= 256);

    std::cout << "Demo signal checks passed: level " << rmsDb << " dB RMS, peak " << peak
              << ", stereo, block-size independent\n";
}
