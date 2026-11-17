/* Deterministic math for stb_vorbis's table setup (no platform libm). stb_vorbis calls:
   - sin / cos for the MDCT twiddles and the window: arguments within a few multiples of pi;
   - floor, and ldexp in float32_unpack (codebook values: |exponent| below about 800);
   - exp, log and pow in lookup1_values only, where floor(exp(log(entries) / dim)) is a first guess that the pow
     checks then correct by one, and pow's exponent is always the integer `dim`.
   Plain range reduction and series are exact enough for all of these; pow with an integer exponent multiplies. */
#include "sr_vorbis_compat.h"

#include <stdint.h>
#include <string.h>

#undef sin
#undef cos
#undef floor
#undef ldexp
#undef exp
#undef log
#undef pow

static const double kPi = 3.14159265358979311600, kPiLo = 1.2246467991473532e-16;
static const double kLn2Hi = 6.93147180369123816490e-01, kLn2Lo = 1.90821492927058770002e-10;

double sr_vorbis_floor(double x) {
    if (!(x > -4.0e15 && x < 4.0e15)) return x; /* huge or NaN: already integral / undefined */
    const double t = (double)(long long)x;
    return t > x ? t - 1.0 : t;
}

/* 2^e as a double, for e in the normal range. */
static double pow2(int e) {
    if (e < -1022) e = -1022;
    if (e > 1023) e = 1023;
    const uint64_t bits = (uint64_t)(e + 1023) << 52;
    double d;
    memcpy(&d, &bits, sizeof d);
    return d;
}

double sr_vorbis_ldexp(double x, int e) {
    /* Scale in steps that keep every intermediate normal for stb's ranges; each step is exact. */
    while (e > 1000) x *= pow2(1000), e -= 1000;
    while (e < -1000) x *= pow2(-1000), e += 1000;
    return x * pow2(e);
}

double sr_vorbis_sin(double x) {
    if (!(x > -4.0e15 && x < 4.0e15)) return 0.0;
    const long long k = (long long)(x / kPi + (x >= 0 ? 0.5 : -0.5));
    const double r = (x - (double)k * kPi) - (double)k * kPiLo; /* in [-pi/2, pi/2] */
    const double r2 = r * r;
    double term = r, sum = r;
    for (int n = 1; n <= 13; ++n) { /* r - r^3/3! + ... up to r^27/27! */
        term *= -r2 / (double)((2 * n) * (2 * n + 1));
        sum += term;
    }
    return (k & 1) ? -sum : sum;
}

double sr_vorbis_cos(double x) {
    if (!(x > -4.0e15 && x < 4.0e15)) return 0.0;
    /* cos(x) = sin(x + pi/2), reducing x + pi/2 by multiples of pi with the split constant. */
    const double half_hi = kPi / 2, half_lo = kPiLo / 2;
    const long long k = (long long)((x + half_hi) / kPi + (x + half_hi >= 0 ? 0.5 : -0.5));
    const double r = ((x - (double)k * kPi) + half_hi) - ((double)k * kPiLo - half_lo);
    const double r2 = r * r;
    double term = r, sum = r;
    for (int n = 1; n <= 13; ++n) {
        term *= -r2 / (double)((2 * n) * (2 * n + 1));
        sum += term;
    }
    return (k & 1) ? -sum : sum;
}

double sr_vorbis_exp(double x) {
    if (x != x) return x;
    if (x > 709.0) return pow2(1023) * 2.0; /* inf */
    if (x < -745.0) return 0.0;
    const long long k = (long long)(x / (kLn2Hi + kLn2Lo) + (x >= 0 ? 0.5 : -0.5));
    const double r = (x - (double)k * kLn2Hi) - (double)k * kLn2Lo; /* |r| <= ln2 / 2 */
    double term = 1.0, sum = 1.0;
    for (int n = 1; n <= 20; ++n) {
        term *= r / (double)n;
        sum += term;
    }
    return sr_vorbis_ldexp(sum, (int)k);
}

double sr_vorbis_log(double x) {
    if (x != x || x < 0) return 0.0 / 0.0;
    if (x == 0) return -1.0 / 0.0;
    /* x = m * 2^e with m in [sqrt(1/2), sqrt(2)); log(m) = 2 atanh((m - 1) / (m + 1)). */
    int e = 0;
    while (x >= 1.4142135623730951) x *= 0.5, ++e;
    while (x < 0.7071067811865476) x *= 2.0, --e;
    const double s = (x - 1.0) / (x + 1.0), s2 = s * s;
    double term = s, sum = s;
    for (int n = 1; n <= 30; ++n) {
        term *= s2;
        sum += term / (double)(2 * n + 1);
    }
    return 2.0 * sum + (double)e * kLn2Hi + (double)e * kLn2Lo;
}

double sr_vorbis_pow(double x, double y) {
    const double yi = sr_vorbis_floor(y);
    if (yi == y && y >= 0 && y <= 1024) { /* stb_vorbis: an integer `dim`; exact while the product fits 2^53 */
        double r = 1.0;
        for (int i = 0; i < (int)yi; ++i) r *= x;
        return r;
    }
    if (x <= 0) return 0.0 / 0.0;
    return sr_vorbis_exp(y * sr_vorbis_log(x));
}
