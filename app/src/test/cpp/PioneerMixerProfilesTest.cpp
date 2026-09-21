#include "PioneerMixerProfiles.h"
#include <cassert>
#include <iostream>

int main() {
    using namespace djmrec;
    // A manually selected USB5/6 pair must receive 0x030a, never the default 0x010a.
    assert(pioneerMixRouteValue(kDjm450Profile, -1) == 0x010a);
    assert(pioneerMixRouteValue(kDjm450Profile, 0) == 0x010a);
    assert(pioneerMixRouteValue(kDjm450Profile, 2) == 0x020a);
    assert(pioneerMixRouteValue(kDjm450Profile, 4) == 0x030a);
    assert(pioneerMixRouteValue(kDjm450Profile, 6) == -1); // USB7/8 has no configurable MIX route.
    assert(pioneerMixRouteValue(kDjm450Profile, 3) == -1);
    assert(pioneerMixRouteValue(kDjm450Profile, -2) == -1);
    assert(kDjm450Profile.requiresPlaybackTraffic);
    assert(kDjm450Profile.playbackInterface == 0);
    assert(kDjm450Profile.playbackAlternateSetting == 1);
    assert(kDjm450Profile.playbackOutChannels == 8);
    assert(kDjm450Profile.playbackOutSubframeBytes == 3);
    assert(kDjm450Profile.fixedCaptureInSampleRate == 48000);

    // DJM-V10: six configurable pairs, REC OUT (0x0a) on every one of them (kernel
    // snd_djm_opts_v10_cap1..6); no without-mic variant, so the mic preference is ignored.
    for (int output = 0; output < 6; ++output) {
        const int expected = ((output + 1) << 8) | 0x0a;
        assert(pioneerMixRouteValue(kDjmV10Profile, output * 2, true) == expected);
        assert(pioneerMixRouteValue(kDjmV10Profile, output * 2, false) == expected);
    }
    assert(pioneerMixRouteValue(kDjmV10Profile, -1) == 0x010a);
    assert(pioneerMixRouteValue(kDjmV10Profile, 12) == -1);
    assert(kDjmV10Profile.routeReadMode == PioneerRouteReadMode::None);
    assert(kDjmV10Profile.captureInChannels == 12 && kDjmV10Profile.playbackOutChannels == 12);

    // DJM-A9: default pair USB 9/10, REC OUT with mic 0x0a, without mic 0x0e (kernel
    // snd_djm_opts_a9_cap1..5 list both). 0x09 is the kernel's "Mic" (mic-only) source and
    // must never be treated as a MIX route.
    assert(pioneerMixRouteValue(kDjmA9Profile, -1) == 0x050a);
    assert(pioneerMixRouteValue(kDjmA9Profile, -1, false) == 0x050e);
    assert(pioneerMixRouteValue(kDjmA9Profile, 0) == 0x010a);
    assert(pioneerMixRouteValue(kDjmA9Profile, 0, false) == 0x010e);
    for (int output = 0; output < 5; ++output) {
        assert(kDjmA9Profile.mixWithMicSources[output] != 0x09);
    }
    assert(pioneerSupportsCaptureLevel(kDjmA9Profile));
    assert(pioneerSupportsCaptureLevel(kDjmV10Profile));
    assert(!pioneerSupportsCaptureLevel(kDjm900Nxs2Profile));

    // DJM-V5: unverified profile mirrors the A9 source codes on four pairs.
    assert(pioneerMixRouteValue(kDjmV5Profile, 0) == 0x010a);
    assert(pioneerMixRouteValue(kDjmV5Profile, 6, false) == 0x040e);
    assert(pioneerMixRouteValue(kDjmV5Profile, 8) == -1);
    assert(!kDjmV5Profile.requiresPlaybackTraffic);

    // Existing default MIX routes remain model-specific. The 750MK2 factory REC OUT pair is
    // USB 9/10; 0x0f ("None") is not in its kernel option list and is no longer written.
    assert(pioneerMixRouteValue(kDjm750Mk2Profile, -1) == 0x050a);
    assert(pioneerMixRouteValue(kDjm750Mk2Profile, 0) == 0x010a);
    assert(pioneerMixRouteValue(kDjmS11Profile, 0) == -1);
    assert(pioneerMixRouteValue(kDjmS11Profile, -1) == 0x030a);

    // Lookup by USB ID.
    assert(findPioneerMixerProfile(kAlphaThetaVendorId, 0x003C) == &kDjmA9Profile);
    assert(findPioneerMixerProfile(kAlphaThetaVendorId, 0x0034) == &kDjmV10Profile);
    assert(findPioneerMixerProfile(kAlphaThetaVendorId, 0x0059) == &kDjmV5Profile);
    assert(findPioneerMixerProfile(0x08E4, 0x003C) == nullptr);
    assert(findPioneerMixerProfile(kAlphaThetaVendorId, 0x7777) == nullptr);
    std::cout << "Pioneer selected-pair routing and DJM-450 duplex checks passed\n";
}
