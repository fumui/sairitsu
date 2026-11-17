#pragma once

#include <string>

#include "modes/presentation.h"

namespace sr::tools {

// The exact text of a float in dumps: the C99 `%a` form of its value as glibc prints it ("0x1.8p+1", "-0x0p+0",
// "0x1p-149"), "inf" / "-inf", and "nan" for every NaN (x86_64 and arm64 make NaNs with different sign
// bits). Formatted here rather than by printf because C libraries differ (Windows UCRT pads `%a` to 13 digits).
std::string hex_float(float v);

// Appends one Watch frame as a JSON line, '\n' included. Floats are JSON strings in hex_float form (Python reads
// them with float.fromhex); ids, ops and counts are JSON integers.
//   {"t": time,
//    "draws": [[sprite, z, a, x0, y0, x1, y1, x2, y2, x3, y3, z2, z3, z4, entity (, curve, segments, p1, q1, p2, q2
//              for a curved draw)], ...],
//    "audio": [[op, clip, time, distance, handle], ...],
//    "particles": [[op, handle, effect, x0, y0, x1, y1, x2, y2, x3, y3, duration, looped], ...],
//    "skin": [16], "particle": [16], "background": [8], "ui": [100],
//    "stats": {"combo", "max_combo", "perfect", "great", "good", "miss", "arcade", "accuracy", "life"},
//    "ended": bool}
// Corners are bl, tl, tr, br; ops and curves are the presentation.h enum values; `entity` is DrawCmd::entity.
void append_frame(std::string& out, float time, const modes::FrameOutput& frame);

}  // namespace sr::tools
