#pragma once

// Portable float32 math, never the platform libm, so host and device agree bit for bit.
// Sin, cos, tan, asin, acos, atan, atan2, sinh, cosh, tanh, log and pow follow SLEEF
// operation by operation (fmath_sleef.cpp). exp, exp2, log2, floor, ceil, trunc and fmod come from
// vendored musl (third_party/musl-math, symbols renamed sr_musl_*).

namespace sr::fm {

float sin(float x);
float cos(float x);
float tan(float x);
float asin(float x);
float acos(float x);
float atan(float x);
float atan2(float y, float x);
float sinh(float x);
float cosh(float x);
float tanh(float x);
float exp(float x);
float exp2(float x);
float log(float x);
float log2(float x);
float pow(float base, float exponent);
float sqrt(float x);  // correctly rounded (IEEE)

float floor(float x);
float ceil(float x);
float trunc(float x);
float fmod(float x, float y);  // C fmod: truncated, dividend's sign

// Round half to even; a zero result keeps x's sign.
float round(float x);
// x - y * (int64)(x / y), evaluated in double (the unconverted quotient when |x / y| >= 2^63); a zero x over a
// non-zero y, and a finite x over an infinite y, give x. Divisor 0, an infinite x or NaN give NaN.
float rem(float x, float y);
// rem(rem(x, y) + y, y).
float mod(float x, float y);
// Python `x % 1`, then +1 if negative.
float frac(float x);
// (x > 0) - (x < 0): +0 for both zeros and NaN.
float sign(float x);
// IEEE maxNum / minNum: a NaN operand is ignored; max of the two zeros is +0 and min is -0, in either order.
float max(float x, float y);
float min(float x, float y);

}  // namespace sr::fm
