#include "ZeroPacketFilter.h"
#include <cassert>
#include <iostream>
#include <vector>

namespace {
// Runs packets through the filter exactly as UsbIsoAudioSource does and returns the byte stream
// that would reach the demuxer.
std::vector<uint8_t> run(djmrec::ZeroPacketFilter& filter, const std::vector<std::vector<uint8_t>>& packets) {
    std::vector<uint8_t> out;
    for (const auto& packet : packets) {
        if (packet.empty()) {
            out.insert(out.end(), filter.interrupt(), 0);
            continue;
        }
        const auto step = filter.push(packet.data(), packet.size(), true);
        out.insert(out.end(), step.releaseZeroBytes, 0);
        if (step.emitCurrent) out.insert(out.end(), packet.begin(), packet.end());
    }
    return out;
}
}

int main() {
    const std::vector<uint8_t> a{1, 2, 3}, b{4, 5, 6}, z(6, 0), lost{};

    // Padding between two signal packets is removed and the audio closes up around it.
    djmrec::ZeroPacketFilter filter;
    assert((run(filter, {a, z, b}) == std::vector<uint8_t>{1, 2, 3, 4, 5, 6}));
    assert(filter.droppedPackets() == 1);

    // Two zero packets in a row are real silence and come out complete and in order.
    filter.reset();
    auto out = run(filter, {a, z, z, b});
    assert((out == std::vector<uint8_t>{1, 2, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4, 5, 6}));
    assert(filter.droppedPackets() == 0);

    // Silence from the start (no signal before it) is never withheld.
    filter.reset();
    assert(run(filter, {z, b}).size() == 9);
    assert(filter.droppedPackets() == 0);

    // A packet lost after a withheld zero packet releases it rather than dropping it.
    filter.reset();
    assert(run(filter, {a, z, lost, b}).size() == 12);
    assert(filter.droppedPackets() == 0);

    // A packet that does not hold whole frames is passed through even between signal.
    filter.reset();
    auto step = filter.push(a.data(), a.size(), true);
    step = filter.push(z.data(), z.size(), false);
    assert(step.emitCurrent && step.releaseZeroBytes == 0);
    filter.push(b.data(), b.size(), true);
    assert(filter.droppedPackets() == 0);

    // The FLX10 pattern: padding every few packets through continuous signal.
    filter.reset();
    out = run(filter, {a, b, z, a, b, a, z, b, z, a});
    assert((out == std::vector<uint8_t>{1, 2, 3, 4, 5, 6, 1, 2, 3, 4, 5, 6, 1, 2, 3, 4, 5, 6, 1, 2, 3}));
    assert(filter.droppedPackets() == 3);

    std::cout << "Zero packet filter checks passed: padding dropped, silence kept, gaps and unaligned packets respected\n";
}
