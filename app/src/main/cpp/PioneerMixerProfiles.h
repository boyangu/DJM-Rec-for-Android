#pragma once

#include <array>

namespace djmrec {

enum class PioneerRouteReadMode {
    None,
    SingleOutputZeroBased,
    SingleOutputOneBased,
    AllOutputs
};

struct PioneerMixerProfile {
    const char* name;
    int productIdFirst;
    int productIdLast;
    int outputCount;
    int defaultOutput;
    // Vendor route source that carries the mixer's REC OUT / MIX bus *including* the mic
    // channel. Linux snd-usb-audio (sound/usb/mixer_quirks.c) labels 0x0a "Rec Out".
    std::array<int, 6> mixWithMicSources;
    // Same bus without the mic ("Rec Out without Mic", 0x0e in the kernel label table). -1 when
    // the model's option list does not offer it; callers fall back to mixWithMicSources.
    std::array<int, 6> mixWithoutMicSources;
    PioneerRouteReadMode routeReadMode;
    bool usesEndpointSampleRate;
    bool requiresPlaybackTraffic;
    int playbackInterface;
    int playbackAlternateSetting;
    int playbackOutChannels;
    int playbackOutSubframeBytes;
    int captureInChannels;
    int captureInSubframeBytes;
    int captureInBitResolution;
    int fixedCaptureInSampleRate;
};

constexpr int kAlphaThetaVendorId = 0x2B73;

// Vendor route register shared by every DJM model (Linux mixer_quirks.c, snd_djm_controls_update):
//   bmRequestType 0x40 (vendor | device | OUT), bRequest 0x03 (SET_FEATURE),
//   wIndex 0x8002, wValue = ((output + 1) << 8) | source.
// Source codes (kernel label table): 0x00 LINE, 0x01 CD/LINE, 0x02 DIGITAL, 0x03 PHONO,
//   0x06 post fader, 0x07/0x08 crossfader A/B, 0x09 MIC ONLY, 0x0a REC OUT (with mic),
//   0x0d AUX, 0x0e REC OUT WITHOUT MIC, 0x0f NONE, 0x10 FX SEND.
constexpr int kPioneerSourceRecOut = 0x0A;
constexpr int kPioneerSourceRecOutNoMic = 0x0E;

// Capture level register (kernel: SND_DJM_WINDEX_CAPLVL). Same request type/request as the
// route register. DJM-A9 and DJM-V10 use a six-step scale: wValue 0x0000 = +15 dB, 0x0100 =
// +12 dB, 0x0200 = +9 dB, 0x0300 = +6 dB, 0x0400 = +3 dB, 0x0500 = 0 dB.
constexpr int kPioneerCaptureLevelIndex = 0x8003;

// Values derived from installed Pioneer/AlphaTheta setup DLLs and kernel drivers.
// Channel counts cross-verified against Linux kernel snd-usb-audio quirks-table.h
// (torvalds/linux master, 2026-09-21): DJM-A9 2b73:003c 10 out / 12 in, DJM-V10 2b73:0034
// 12 out / 12 in, DJM-900NXS2 2b73:000a 10 out / 12 in, DJM-750MK2 2b73:001b 10 out / 12 in,
// DJM-450 2b73:0013 8 out / 8 in, DJM-S11 2b73:0037 14 out / 10 in; all S24_3LE.
// Route option lists cross-verified against mixer_quirks.c snd_djm_opts_* tables: every model
// below accepts 0x0a (REC OUT) on the outputs listed; only the A9 additionally lists 0x0e.
// DJM-A9 capture uses standard UAC AudioStreaming (captureInChannels=0 -- read from descriptor).
constexpr PioneerMixerProfile kDjmA9Profile{
    "DJM-A9", 0x003C, 0x003C, 5, 4,
    {0x0A, 0x0A, 0x0A, 0x0A, 0x0A, -1},
    {0x0E, 0x0E, 0x0E, 0x0E, 0x0E, -1},
    PioneerRouteReadMode::SingleOutputZeroBased, true, true, 1, 1, 10, 3, 0, 0, 0, 0
};

// DJM-V10_Setup.dll exposes six USB input pairs. Its raw route table maps MIX(REC OUT) to
// source 0x0A for every pair; the kernel option lists (snd_djm_opts_v10_cap1..6) agree and do
// not offer a without-mic variant. The kernel quirk confirms the vendor-class if0/alt1 wire
// format. Route GET semantics were not established, so the SET is write-only (no readback).
constexpr PioneerMixerProfile kDjmV10Profile{
    "DJM-V10", 0x0034, 0x0034, 6, 0,
    {0x0A, 0x0A, 0x0A, 0x0A, 0x0A, 0x0A},
    {-1, -1, -1, -1, -1, -1},
    PioneerRouteReadMode::None, true, true, 0, 1,
    12, 3, 12, 3, 24, 0
};

// DJM-V5 (released 2026-01): product IDs are UNVERIFIED guesses and there is no public
// descriptor dump or kernel quirk yet. The wire format is read from the device's own UAC
// descriptors when present; UsbAudioManager falls back to the generic AlphaTheta vendor-class
// scan otherwise. Source codes mirror the A9 (with mic 0x0a / without 0x0e).
constexpr PioneerMixerProfile kDjmV5Profile{
    "DJM-V5", 0x0058, 0x005B, 4, 0,
    {0x0A, 0x0A, 0x0A, 0x0A, -1, -1},
    {0x0E, 0x0E, 0x0E, 0x0E, -1, -1},
    PioneerRouteReadMode::SingleOutputOneBased, true, false, -1, -1, 0, 0, 0, 0, 0, 0
};

// requiresPlaybackTraffic added 2026-07-20: a raw hex dump of the capture endpoint's untouched
// wire bytes (taken before any channel/format demux) confirmed genuine all-zero payload across
// every MIX-routed output pair, with music confirmed audibly playing on the mixer at the time --
// ruling out both "no signal at the source" and "wrong channel/bit-depth guess" (a format error
// would misplace real nonzero bytes, not zero them). The remaining hypothesis, mirrored from
// DJM-A9's confirmed-working requiresPlaybackTraffic mechanism: this endpoint may only emit real
// audio once the host is also driving its OUT direction. Unlike DJM-A9 (separate playback
// interface/alt-setting from its capture interface), DJM-900NXS2's OUT endpoint (0x01) lives on
// the SAME vendor-class interface+alt-setting (if0/alt1) as its IN capture endpoint (0x82).
// ALSA quirk (Linux kernel quirks-table.h) confirms: 10 playback channels, 12 capture channels.
constexpr PioneerMixerProfile kDjm900Nxs2Profile{
    "DJM-900NXS2", 0x000A, 0x000A, 5, 0,
    {0x0A, 0x0A, 0x0A, 0x0A, 0x0A, -1},
    {-1, -1, -1, -1, -1, -1},
    PioneerRouteReadMode::AllOutputs, true, true, 0, 1,
    10, 3,  // playback OUT keepalive (10ch per ALSA snd-usb-audio quirk)
    12, 3, 24, 96000 // capture IN PCM
};

// Same vendor-class interface topology as DJM-900NXS2: isochronous IN endpoint (0x82) lives on
// if0/alt1 declared as USB_CLASS_VENDOR_SPEC (255), OUT endpoint (0x01) on the same interface.
// requiresPlaybackTraffic and vendor-capture override applied per the same evidence chain.
// DJM-750MK2 uses 10-channel playback OUT (keepalive) and 12-channel capture IN per ALSA quirk.
// Kernel snd_djm_opts_750mk2_cap1..5 list 0x0a (REC OUT) on all five pairs; the previous 0x0f
// ("None") entries were not in the mixer's option list at all. The factory REC OUT pair is
// USB 9/10 (kernel default index 3 of cap5 = 0x050a), hence defaultOutput 4.
constexpr PioneerMixerProfile kDjm750Mk2Profile{
    "DJM-750MK2", 0x001B, 0x001B, 5, 4,
    {0x0A, 0x0A, 0x0A, 0x0A, 0x0A, -1},
    {-1, -1, -1, -1, -1, -1},
    PioneerRouteReadMode::AllOutputs, true, true, 0, 1,
    10, 3,  // playback OUT keepalive (10ch per ALSA snd-usb-audio quirk)
    12, 3, 24, 96000 // capture IN PCM
};

// Kernel snd_djm_opts_450_cap1..3 list 0x0a on all three pairs (USB 7/8 is fixed).
constexpr PioneerMixerProfile kDjm450Profile{
    "DJM-450", 0x0013, 0x0013, 3, 0,
    {0x0A, 0x0A, 0x0A, -1, -1, -1},
    {-1, -1, -1, -1, -1, -1},
    PioneerRouteReadMode::None, true, true, 0, 1,
    8, 3,  // playback OUT PCM
    8, 3, 24, 48000 // capture IN PCM
};

// Returns the vendor source code for output `output` honoring the mic preference; falls back to
// the with-mic source when the model has no separate without-mic route. -1 if not configurable.
constexpr int pioneerMixSource(const PioneerMixerProfile& profile, int output, bool includeMic) {
    if (output < 0 || output >= profile.outputCount) return -1;
    const int withMic = profile.mixWithMicSources[output];
    const int withoutMic = profile.mixWithoutMicSources[output];
    if (includeMic) return withMic;
    return withoutMic >= 0 ? withoutMic : withMic;
}

// -1 preserves a fixed/unconfigurable pair; AUTO uses the profile's default MIX pair.
constexpr int pioneerMixRouteValue(const PioneerMixerProfile& profile, int channelOffset,
                                   bool includeMic = true) {
    if (channelOffset < -1 || (channelOffset >= 0 && channelOffset % 2 != 0)) return -1;
    const int output = channelOffset < 0 ? profile.defaultOutput : channelOffset / 2;
    if (output < 0 || output >= profile.outputCount) return -1;
    const int source = pioneerMixSource(profile, output, includeMic);
    return source < 0 ? -1 : ((output + 1) << 8) | source;
}

// True when the model's capture-level register uses the six-step +15..0 dB scale (A9, V10).
constexpr bool pioneerSupportsCaptureLevel(const PioneerMixerProfile& profile) {
    return profile.productIdFirst == 0x003C || profile.productIdFirst == 0x0034;
}

// Linux ALSA quirks-table.h and mixer_quirks.c: separate UAC2 interfaces,
// fixed 48 kHz, and MIX REC OUT available on capture pair 5/6 only.
constexpr PioneerMixerProfile kDjmS11Profile{
    "DJM-S11", 0x0037, 0x0037, 3, 2,
    {-1, -1, 0x0A, -1, -1, -1},
    {-1, -1, -1, -1, -1, -1},
    PioneerRouteReadMode::None, false, true, 1, 1,
    14, 3, 10, 3, 24, 48000
};

inline const PioneerMixerProfile* findPioneerMixerProfile(int vendorId, int productId) {
    if (vendorId != kAlphaThetaVendorId) return nullptr;
    constexpr const PioneerMixerProfile* profiles[] = {
        &kDjmA9Profile, &kDjmV10Profile, &kDjmV5Profile, &kDjm900Nxs2Profile,
        &kDjm750Mk2Profile, &kDjm450Profile, &kDjmS11Profile
    };
    for (const auto* profile : profiles) {
        if (productId >= profile->productIdFirst && productId <= profile->productIdLast) {
            return profile;
        }
    }
    return nullptr;
}

} // namespace djmrec
