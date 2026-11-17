#pragma once

#include <algorithm>
#include <cstdint>
#include <optional>
#include <string>

#include "audio/decode.h"

namespace sr::audio {

// Decodes an audio file with the platform's decoders, for the formats decode_audio() does not read (the
// app decodes every BGM with ffmpeg, so M4A / AAC BGMs exist). Android: MediaExtractor + MediaCodec, with the
// encoder delay and padding removed as ffmpeg removes them (decoder_trim) and the length capped at the track
// duration, which an MP4 edit list sets. Elsewhere: nullopt.
// nullopt also when the platform cannot decode the file; `error` then says why.
std::optional<Pcm> platform_decode(const std::string& path, std::string& error);

// Frames still to drop from the start and the end of a platform decode.
struct Trim {
    int64_t start = 0, end = 0;
};

// The decoder may already have dropped some or all of the encoder delay and padding: Android's AAC decoders apply
// the track's encoder-delay / encoder-padding themselves, other decoders do not, and samples the extractor dates
// before 0 (an applied edit list) are dropped as well. `untrimmed` is what the input would decode to in full
// (access units x frames per unit), `kept` what came out; the difference is taken as dropped delay first, then
// padding, and only the rest of `delay` + `padding` is left to drop.
inline Trim decoder_trim(int64_t untrimmed, int64_t kept, int64_t delay, int64_t padding) {
    const int64_t dropped = std::clamp<int64_t>(untrimmed - kept, 0, delay + padding);
    const int64_t from_delay = std::min(dropped, delay);
    return Trim{delay - from_delay, padding - (dropped - from_delay)};
}

}  // namespace sr::audio
