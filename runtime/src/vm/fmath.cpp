#include "vm/fmath.h"

#include <bit>
#include <cmath>
#include <cstdint>

extern "C" {
float sr_musl_expf(float);
float sr_musl_exp2f(float);
float sr_musl_log2f(float);
float sr_musl_floorf(float);
float sr_musl_ceilf(float);
float sr_musl_truncf(float);
float sr_musl_fmodf(float, float);
}

namespace sr::fm {

namespace {
bool is_nan(float x) { return x != x; }
}  // namespace

// sin, cos, tan, asin, acos, atan, atan2, sinh, cosh, tanh, log and pow: fmath_sleef.cpp.

float exp(float x) { return sr_musl_expf(x); }
float exp2(float x) { return sr_musl_exp2f(x); }
float log2(float x) { return sr_musl_log2f(x); }
float sqrt(float x) { return __builtin_sqrtf(x); }

float floor(float x) { return sr_musl_floorf(x); }
float ceil(float x) { return sr_musl_ceilf(x); }
float trunc(float x) { return sr_musl_truncf(x); }
float fmod(float x, float y) { return sr_musl_fmodf(x, y); }

float round(float x) {
    // Half to even, and a zero result keeps x's sign.
    // Beyond 2^23 every float is an integer; NaN and infinities pass through.
    if (is_nan(x) || __builtin_fabsf(x) >= 8388608.0f) return x;
    const float f = floor(x);
    const float d = x - f;  // exact for |x| < 2^23
    float r;
    if (d < 0.5f) {
        r = f;
    } else if (d > 0.5f) {
        r = f + 1.0f;
    } else {
        r = (fmod(f, 2.0f) == 0.0f) ? f : f + 1.0f;
    }
    return r == 0.0f ? __builtin_copysignf(0.0f, x) : r;
}

float rem(float x, float y) {
    // Not C fmod.
    // - a zero x over a non-zero y is returned as is: Rem(-0, -3) is -0, Rem(0, -1) is +0;
    //   Rem(0, 0) stays NaN;
    // - a finite x over an infinite y is x: Rem(5, inf) is 5;
    // - otherwise x - y * t in double, t = x / y in double truncated through int64 (Rem(-4, 2) is +0). When
    //   |x / y| >= 2^63 the quotient is used as it is instead (no int64 holds it; a saturating cast would give
    //   Rem(1e30, 7) = 1e30 instead of 0).
    if (x == 0.0f && y != 0.0f && !is_nan(y)) return x;
    if (__builtin_isinf(y) && !is_nan(x) && !__builtin_isinf(x)) return x;
    const double dx = x, dy = y;
    const double q = dx / dy;
    const double t = (q > -9.2233720368547758e18 && q < 9.2233720368547758e18) ? double(int64_t(q)) : q;
    return float(dx - dy * t);
}

float mod(float x, float y) {
    // Rem(Rem(x, y) + y, y), the usual floored-modulo idiom.
    return rem(rem(x, y) + y, y);
}

float frac(float x) {
    // Python `x % 1` (fmod folded toward the divisor's sign), then +1 if negative. Matches the expected results.
    float m = fmod(x, 1.0f);
    if (m != 0.0f) {
        if (m < 0.0f) m += 1.0f;
    } else {
        m = 0.0f;
    }
    return m >= 0.0f ? m : m + 1.0f;
}

float sign(float x) {
    // (x > 0) - (x < 0), so -0 and NaN give +0 (like math.sign in Unity.Mathematics
    // math.sign).
    return (x > 0.0f ? 1.0f : 0.0f) - (x < 0.0f ? 1.0f : 0.0f);
}

float max(float x, float y) {
    // IEEE maxNum (arm64 fmaxnm): a NaN operand is ignored and +0
    // is above -0.
    if (is_nan(x)) return y;
    if (is_nan(y)) return x;
    if (x == y) return __builtin_signbit(x) ? y : x;
    return x > y ? x : y;
}

float min(float x, float y) {
    // IEEE minNum (arm64 fminnm).
    if (is_nan(x)) return y;
    if (is_nan(y)) return x;
    if (x == y) return __builtin_signbit(x) ? x : y;
    return x < y ? x : y;
}

}  // namespace sr::fm
