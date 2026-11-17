/* Deterministic sin and floor for speexdsp's resampler filter table (no platform libm). The table is built from
   arguments of at most a few hundred radians, so a plain range reduction by multiples of pi and a Taylor series on
   [-pi/2, pi/2] (error below 1e-16) is enough. */
#include "sr_compat.h"

#undef sin
#undef floor

double sr_speex_floor(double x) {
    if (!(x > -4.0e15 && x < 4.0e15)) return x;  /* huge or NaN: already integral / undefined */
    const double t = (double)(long long)x;
    return t > x ? t - 1.0 : t;
}

double sr_speex_sin(double x) {
    if (!(x > -4.0e15 && x < 4.0e15)) return 0.0;
    const double inv_pi = 0.31830988618379067154;
    const double pi_hi = 3.14159265358979311600, pi_lo = 1.2246467991473532e-16;
    const long long k = (long long)(x * inv_pi + (x >= 0 ? 0.5 : -0.5));
    const double r = (x - (double)k * pi_hi) - (double)k * pi_lo;
    const double r2 = r * r;
    /* r - r^3/3! + r^5/5! - ... up to r^27/27! */
    double term = r, sum = r;
    for (int n = 1; n <= 13; ++n) {
        term *= -r2 / (double)((2 * n) * (2 * n + 1));
        sum += term;
    }
    return (k & 1) ? -sum : sum;
}
