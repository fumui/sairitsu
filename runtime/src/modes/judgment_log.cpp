#include "modes/judgment_log.h"

#include <algorithm>
#include <cstdio>

namespace sr::modes {

size_t format_judgment(char* out, size_t size, const StatInput& input, double frame_time) {
    if (size == 0) return 0;
    const int n = std::snprintf(out, size, "J e=%d a=%d j=%d acc=%.6f t=%.4f ft=%.4f", int(input.entity),
                                int(input.archetype), int(input.judgment), double(input.accuracy),
                                double(input.time), frame_time);
    if (n < 0) {
        out[0] = '\0';
        return 0;
    }
    return std::min(size_t(n), size - 1);
}

}  // namespace sr::modes
