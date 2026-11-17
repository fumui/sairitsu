#pragma once

#include <cstddef>

#include "modes/stats.h"

namespace sr::modes {

// The logcat line of one counted judgment (debug builds, SR_JUDGMENT_LOG), for latency checks from a PC.
// Format (fields in this order, space separated):
//   J e=<entity> a=<archetype> j=<judgment 0-3> acc=<accuracy s> t=<judged time s> ft=<frame time s>
// `t` is the music time the input was counted at; `ft` is the frame's own time (equal in Play, kept for the check).
// Writes at most `size` bytes including the terminating NUL into a caller buffer (no allocation) and returns the
// length without the NUL, or 0 if `size` is 0.
size_t format_judgment(char* out, size_t size, const StatInput& input, double frame_time);

}  // namespace sr::modes
