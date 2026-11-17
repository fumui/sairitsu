#include "vm/convert.h"

#include <cstdint>

namespace sr::vm {

int32_t to_index(float x) {
    // Truncate toward zero, saturating, NaN -> 0.
    if (x != x) return 0;
    if (x >= 2147483648.0f) return INT32_MAX;
    if (x <= -2147483648.0f) return INT32_MIN;
    return static_cast<int32_t>(x);
}

int32_t to_id(float x) {
    // Exact integers only.
    if (!(x >= -2147483648.0f && x < 2147483648.0f)) return kNoId;
    const int32_t i = static_cast<int32_t>(x);
    return static_cast<float>(i) == x ? i : kNoId;
}

}  // namespace sr::vm
