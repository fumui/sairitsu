#pragma once

#include <cstdint>

#include "audio/decode.h"

namespace sr::audio {

// Converts to stereo at `out_rate` with speexdsp's resampler at quality 10 (mono is duplicated to both channels).
// The output has round(frames * out_rate / rate) frames and the filter latency is removed, so sample 0 of the output
// is at the same time as sample 0 of the input. The input rate is returned unchanged when it equals `out_rate`
// (only the channel layout changes).
Pcm resample(const Pcm& in, int32_t out_rate);

}  // namespace sr::audio
