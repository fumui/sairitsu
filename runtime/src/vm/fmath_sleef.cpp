// Float32 math derived from SLEEF (https://sleef.org):
//   Copyright Naoki Shibata and contributors 2010 - 2021.
//   Distributed under the Boost Software License, Version 1.0 (runtime/third_party/sleef/LICENSE.txt).
// See runtime/third_party/sleef/README.md for the routines this file derives from.

#include <bit>
#include <cmath>
#include <cstdint>

#include "vm/fmath.h"

namespace sr::fm {

// ---- SLEEF math -------------------------------------------------------------------------------------
//
// sin, cos, tan and pow are the SLEEF routines built without fused multiply-add (Sleef_sinf_u35, Sleef_cosf_u35, Sleef_tanf_u35, Sleef_powf_u10); asin,
// acos, atan, atan2, sinh, cosh, tanh and log are SLEEF-style code inlined, with a
// few changes (tanh, cosh's overflow bound, atan2's special cases, the large-argument
// reduction). Each function below is written operation by operation: separate multiplies and adds, in
// the same order, nothing fused, nothing reassociated, so host and device give the same bits.

namespace {

constexpr float F(uint32_t u) { return std::bit_cast<float>(u); }
uint32_t B(float x) { return std::bit_cast<uint32_t>(x); }
bool is_nan(float x) { return x != x; }
bool is_inf(float x) { return x == F(0x7f800000u) || x == F(0xff800000u); }

constexpr float kInf = F(0x7f800000u);
constexpr float kNaN = F(0x7fc00000u);  // the default NaN the arm64 code returns
constexpr float kHalfPi = F(0x3fc90fdbu);
constexpr float kPi = F(0x40490fdbu);

// arm64 fcvtzs: x * 2^fbits truncated toward zero, saturated to int32, NaN gives 0.
int32_t cvtzs(float x, int fbits = 0) {
    if (is_nan(x)) return 0;
    const double v = double(x) * double(int64_t(1) << fbits);
    if (v >= 2147483648.0) return INT32_MAX;
    if (v <= -2147483648.0) return INT32_MIN;
    return int32_t(v);
}

// arm64 scvtf with fbits: w / 2^fbits, rounded once.
float scvtf(int32_t w, int fbits) { return float(double(w) / double(int64_t(1) << fbits)); }

// SLEEF's rint on this build: truncate x + copysign(0.5, x) (the -0.5 only for x < 0).
int32_t rint_i(float x) { return cvtzs(x + (x < 0.0f ? -0.5f : 0.5f)); }

float upper(float x) { return F(B(x) & 0xfffff000u); }
float sign_bit_xor(float x, float s) { return F(B(x) ^ (B(s) & 0x80000000u)); }
float copysign1(float x) { return F((B(x) & 0x80000000u) | 0x3f800000u); }

// x * 2^q as two power-of-two multiplies (SLEEF vldexp2).
float ldexp2(float x, int32_t q) {
    const int32_t h = q >> 1;
    x = x * F(0x3f800000u + (uint32_t(h) << 23));
    return x * F(0x3f800000u + (uint32_t(q - h) << 23));
}

// x * 2^q with the exponent split in up to five steps (SLEEF vldexp).
float ldexp5(float x, int32_t q) {
    const int32_t sgn = q >> 31;
    const int32_t m = ((sgn + q) >> 6) - sgn;
    int32_t e = m * 16 + 0x7f;
    if (e < 0) e = 0;
    if (e > 0xff) e = 0xff;
    const float u = F(uint32_t(e) << 23);
    x = x * u;
    x = x * u;
    x = x * u;
    x = x * u;
    return x * F(0x3f800000u + (uint32_t(q - m * 64) << 23));
}

// ---- double-float helpers (SLEEF df*, no fused operations) ----

struct Df {
    float x, y;
};

Df dfadd2(float x, float y) {
    const float s = x + y;
    const float v = s - x;
    return {s, (x - (s - v)) + (y - v)};
}

Df dfadd2(Df x, float y) {
    const float s = x.x + y;
    const float v = s - x.x;
    const float t = (x.x - (s - v)) + (y - v);
    return {s, t + x.y};
}

Df dfadd2(Df x, Df y) {
    const float s = x.x + y.x;
    const float v = s - x.x;
    const float t = (x.x - (s - v)) + (y.x - v);
    return {s, t + (x.y + y.y)};
}

Df dfadd(Df x, Df y) {
    const float s = x.x + y.x;
    return {s, (((x.x - s) + y.x) + x.y) + y.y};
}

Df dfadd(float x, Df y) {
    const float s = x + y.x;
    return {s, ((x - s) + y.x) + y.y};
}

Df dfnormalize(Df t) {
    const float s = t.x + t.y;
    return {s, (t.x - s) + t.y};
}

Df dfmul(float x, float y) {
    const float xh = upper(x), xl = x - xh;
    const float yh = upper(y), yl = y - yh;
    const float s = x * y;
    float t = xh * yh - s;
    t = xl * yh + t;
    t = xh * yl + t;
    t = xl * yl + t;
    return {s, t};
}

Df dfmul(Df x, float y) {
    const float xh = upper(x.x), xl = x.x - xh;
    const float yh = upper(y), yl = y - yh;
    const float s = x.x * y;
    float t = xh * yh - s;
    t = xl * yh + t;
    t = xh * yl + t;
    t = xl * yl + t;
    t = x.y * y + t;
    return {s, t};
}

Df dfmul(Df x, Df y) {
    const float xh = upper(x.x), xl = x.x - xh;
    const float yh = upper(y.x), yl = y.x - yh;
    const float s = x.x * y.x;
    float t = xh * yh - s;
    t = xl * yh + t;
    t = xh * yl + t;
    t = xl * yl + t;
    t = x.x * y.y + t;
    t = x.y * y.x + t;
    return {s, t};
}

Df dfsqu(Df x) {
    const float xh = upper(x.x), xl = x.x - xh;
    const float s = x.x * x.x;
    float t = xh * xh - s;
    t = (xh + xh) * xl + t;
    t = xl * xl + t;
    t = x.x * (x.y + x.y) + t;
    return {s, t};
}

Df dfdiv(Df n, Df d) {
    const float t = 1.0f / d.x;
    const float dh = upper(d.x), dl = d.x - dh;
    const float th = upper(t), tl = t - th;
    const float nhh = upper(n.x), nhl = n.x - nhh;
    const float s = n.x * t;
    float w = dh * th + -1.0f;
    w = dh * tl + w;
    w = dl * th + w;
    w = dl * tl + w;
    w = -w;
    float u = nhh * th - s;
    u = nhh * tl + u;
    u = nhl * th + u;
    u = nhl * tl + u;
    u = s * w + u;
    return {s, t * (n.y - s * d.y) + u};
}

// ---- large-argument reduction for sin, cos and tan (|x| >= 39000, and inf/NaN) ----

constexpr uint32_t kRempiTab[] = {
#include "fmath_rempi.inc"
};

struct Reduced {
    float x, y;  // the reduced argument as a double-float, in radians
    int32_t q;   // quadrant
};

struct Quarter {
    float d;
    int32_t q;
};

// One quarter-turn step of the reduction: the value modulo 1024, its distance to the nearest quarter, and
// the quadrant count it carries. Includes the odd 0x3dffffff special case.
Quarter quarter_step(float x) {
    const float c = F((B(x) & 0x80000000u) | 0x3f000000u);  // copysign(0.5, x)
    const float r = x - float(cvtzs(x * F(0x3a800000u))) * 1024.0f;
    const float f = scvtf(cvtzs(r * 4.0f + c), 2);
    const float e = r - f;
    float d = std::fabs(r - f) > 0.125f ? e - c : e;
    if (std::fabs(d) > F(0x501502f9u)) d = 0.0f;  // 1e10
    const bool keep = std::fabs(x) == F(0x3dffffffu);
    if (keep) d = x;
    int32_t q = ((((x > 0.0f) ? 4 : 3) + cvtzs(r, 3)) & 7) - 3;
    q >>= 1;
    if (keep) q = 0;
    return {d, q};
}

Reduced rempif(float a0) {
    uint32_t ab = B(a0);
    const uint32_t ex = (ab >> 23) & 0xffu;
    if (ex > 0xd9u) ab += 0xe0000000u;  // scale by 2^-64
    const float a = F(ab);
    uint32_t row = ex < 0x98u ? 0u : ex - 0x98u;
    if (row > 102u) row = 102u;  // inf/NaN (biased exponent 255): the result is NaN whatever the row
    const float t0 = F(kRempiTab[row * 4]), t1 = F(kRempiTab[row * 4 + 1]);
    const float t2 = F(kRempiTab[row * 4 + 2]), t3 = F(kRempiTab[row * 4 + 3]);
    const float ah = upper(a), al = a - ah;

    // x = a * t0
    const float t0h = upper(t0), t0l = t0 - t0h;
    const float xx = t0 * a;
    float xl = ah * t0h - xx;
    xl = al * t0h + xl;
    xl = t0l * ah + xl;
    xl = al * t0l + xl;
    const Quarter q1 = quarter_step(xx);
    const float nx = xl + q1.d;
    const float ny = xl + (q1.d - nx);

    // + a * t1
    const float t1h = upper(t1), t1l = t1 - t1h;
    const float yx = t1 * a;
    float yl = ah * t1h - yx;
    yl = al * t1h + yl;
    yl = t1l * ah + yl;
    yl = al * t1l + yl;
    const float sx = yx + nx;
    const float sv = sx - nx;
    const float st = (yx - sv) + (nx - (sx - sv));
    const float sl = (yl + ny) + st;
    const Quarter q2 = quarter_step(sx);
    const float mx = sl + q2.d;
    const float my = sl + (q2.d - mx);

    // + (t2, t3) * a
    const float t2h = upper(t2), t2l = t2 - t2h;
    const float zx = t2 * a;
    float zl = ah * t2h - zx;
    zl = t2l * ah + zl;
    zl = al * t2h + zl;
    zl = al * t2l + zl;
    zl = t3 * a + zl;
    const float px = zx + mx;
    const float pv = px - mx;
    const float pt = (zx - pv) + (mx - (px - pv));
    const float pl = (zl + my) + pt;
    const float hx = px + pl;
    const float hy = pl + (px - hx);

    // * 2pi as a double-float
    const float hi = hx * F(0x40c90fdbu);
    const float xh = upper(hx), xlo = hx - xh;
    float lo = xh * F(0x40c90000u) - hi;
    lo = xlo * F(0x40c90000u) + lo;
    lo = xh * F(0x3afdb000u) + lo;
    lo = xlo * F(0x3afdb000u) + lo;
    lo = hx * F(0xb43bbd2eu) + lo;
    lo = hy * F(0x40c90fdbu) + lo;

    const int32_t q = q1.q + q2.q;
    if (F(B(a) & 0x7fffffffu) < 0.7f) return {a, 0.0f, q};
    return {hi, lo, q};
}

// The reduced argument as one float; inf and NaN inputs give NaN.
float reduced_sum(Df x, float input) {
    const float d = x.x + x.y;
    return (is_inf(input) || is_nan(input)) ? kNaN : d;
}

// sin/cos polynomial: d + s * (u * d), s = d * d.
float sincos_poly(float d, float s) {
    float u = F(0x362f0a87u);
    u = s * u + F(0xb94fbaebu);
    u = s * u + F(0x3c088777u);
    u = s * u + F(0xbe2aaaa6u);
    return d + s * (u * d);
}

// expm1 for sinh and tanh (SLEEF expm1fk): e^d - 1.
float expm1k(float d) {
    const int32_t q = rint_i(d * F(0x3fb8aa3bu));
    const float u = float(q);
    float s = d + u * F(0xbf317200u);
    s = s + u * F(0xb5bfbe8eu);
    const float s2 = s * s;
    const float s4 = s2 * s2;
    const float a = s * F(0x3c0888a6u) + F(0x3d2aaa7au);
    const float b = s * F(0x39502bdau) + F(0x3ab696c9u);
    const float c = s * F(0x3e2aaaabu) + 0.5f;
    float p = c + s2 * a;
    p = s4 * b + p;
    float e = s + s2 * p;
    if (q != 0) e = ldexp2(e + 1.0f, q) + -1.0f;
    return e;
}

// asin/acos polynomial in x2.
float asin_poly(float x2) {
    float u = F(0x3d2bed81u);
    u = x2 * u + F(0x3cc693eeu);
    u = x2 * u + F(0x3d3a4332u);
    u = x2 * u + F(0x3d997f8au);
    u = x2 * u + F(0x3e2aaaf2u);
    return u;
}

// atan polynomial in t = s * s (SLEEF POLY8 split into an even/odd pair).
float atan_poly(float t) {
    const float t2 = t * t;
    const float t4 = t2 * t2;
    const float lo = (t * F(0x3e4cb9a7u) + F(0xbeaaaa5du)) + (t * F(0x3dd9ccf2u) + F(0xbe116f9fu)) * t2;
    const float hi = (t * F(0x3d2e19b6u) + F(0xbd995ffau)) + (t * F(0x3b390ccdu) + F(0xbc82b80du)) * t2;
    return lo + t4 * hi;
}

// log(d) as a double-float, for pow (SLEEF logkf).
Df logk(float d) {
    const bool o = d < F(0x00800000u);
    if (o) d = d * F(0x5f800000u);
    const uint32_t eb = (B(d * F(0x3faaaaabu)) >> 23) & 0xffu;
    const float m = F(B(d) - (eb << 23) + 0x3f800000u);
    const int32_t e = int32_t(eb) + (o ? -191 : -127);
    Df x = dfdiv(dfadd2(-1.0f, m), dfadd2(1.0f, m));
    const Df x2 = dfsqu(x);
    float t = F(0x3e76168au);
    t = t * x2.x + F(0x3e91fa4au);
    t = t * x2.x + F(0x3ecccdd9u);
    Df s = dfmul(Df{F(0x3f317218u), F(0xb102e308u)}, float(e));
    s = dfadd(s, Df{x.x * 2.0f, x.y * 2.0f});
    x = dfmul(x2, x);
    s = dfadd(s, dfmul(x, Df{F(0x3f2aaaaau), F(0x317db383u)}));
    x = dfmul(x2, x);
    s = dfadd(s, dfmul(x, t));
    return s;
}

// e^d for a double-float d, for pow (SLEEF expkf).
float expk(Df d) {
    const int32_t q = rint_i((d.x + d.y) * F(0x3fb8aa3bu));
    Df s = dfadd2(d, float(q) * F(0xbf317200u));
    s = dfadd2(s, float(q) * F(0xb5bfbe8eu));
    s = dfnormalize(s);
    float u = F(0x3ab2aef6u);
    u = u * s.x + F(0x3c09116bu);
    u = u * s.x + F(0x3d2aaf4cu);
    u = u * s.x + F(0x3e2aaa5eu);
    u = u * s.x + F(0x3efffffbu);
    Df t = dfadd(s, dfmul(dfsqu(s), u));
    t = dfadd(1.0f, t);
    u = ldexp5(t.x + t.y, q);
    if (d.x < -104.0f) u = 0.0f;
    return u;
}

}  // namespace

// Sleef_sinf_u35.
float sin(float x) {
    float d = x;
    int32_t q;
    const float ax = std::fabs(x);
    if (ax < 125.0f) {
        q = rint_i(x * F(0x3ea2f983u));
        const float u = float(q);
        d = d + u * F(0xc0490e00u);
        d = d + u * F(0xb8ed5000u);
        d = d + u * F(0xb1085a31u);
    } else if (ax < 39000.0f) {
        q = rint_i(x * F(0x3ea2f983u));
        const float u = float(q);
        d = d + u * F(0xc0490000u);
        d = d + u * F(0xba7d8000u);
        d = d + u * F(0xb5288000u);
        d = d + u * F(0xaf05a309u);
    } else {
        const Reduced r = rempif(x);
        q = ((r.q & 3) * 2 + (r.x > 0.0f ? 1 : 0) + 1) >> 2;
        Df v{r.x, r.y};
        if (r.q & 1) {
            const uint32_t sg = B(r.x) & 0x80000000u;
            v = dfadd2(v, Df{F(sg ^ 0xbfc90fdbu), F(sg | 0x333bbd2eu)});
        }
        d = reduced_sum(v, x);
    }
    const float s = d * d;
    if (q & 1) d = -d;
    const float r = sincos_poly(d, s);
    return B(x) == 0x80000000u ? x : r;
}

// Sleef_cosf_u35.
float cos(float x) {
    float d = x;
    int32_t q;
    const float ax = std::fabs(x);
    if (ax < 39000.0f) {
        q = int32_t(1u | (uint32_t(rint_i(x * F(0x3ea2f983u) + -0.5f)) << 1));
        const float u = float(q);
        if (ax < 125.0f) {
            d = d + u * F(0xbfc90e00u);
            d = d + u * F(0xb86d5000u);
            d = d + u * F(0xb0885a31u);
        } else {
            d = d + u * F(0xbfc90000u);
            d = d + u * F(0xb9fd8000u);
            d = d + u * F(0xb4a88000u);
            d = d + u * F(0xae85a309u);
        }
    } else {
        const Reduced r = rempif(x);
        q = ((r.q & 3) * 2 + (r.x > 0.0f ? 1 : 0) + 7) >> 1;
        Df v{r.x, r.y};
        if ((r.q & 1) == 0) {
            const uint32_t neg = (r.x > 0.0f) ? 0u : 0x80000000u;
            v = dfadd2(v, Df{F(neg ^ 0xbfc90fdbu), F(neg | 0x333bbd2eu)});
        }
        d = reduced_sum(v, x);
    }
    const float s = d * d;
    if ((q & 2) == 0) d = -d;
    return sincos_poly(d, s);
}

// Sleef_tanf_u35.
float tan(float x) {
    float d = x;
    int32_t q;
    const float ax = std::fabs(x);
    if (ax < 39000.0f) {
        q = rint_i(x * F(0x3f22f983u));
        const float u = float(q);
        if (ax < 62.5f) {
            d = d + u * F(0xbfc90e00u);
            d = d + u * F(0xb86d5000u);
            d = d + u * F(0xb0885a31u);
        } else {
            d = d + u * F(0xbfc90000u);
            d = d + u * F(0xb9fd8000u);
            d = d + u * F(0xb4a88000u);
            d = d + u * F(0xae85a309u);
        }
    } else {
        const Reduced r = rempif(x);
        q = r.q;
        d = reduced_sum(Df{r.x, r.y}, x);
    }
    const float s = d * d;
    const float s2 = s * s;
    const float s4 = s2 * s2;
    const float a = s * F(0x3cc71064u) + F(0x3d5aede5u);
    const float b = s * F(0x3c17eb82u) + F(0x3b5991d7u);
    const float c = s * F(0x3e08958eu) + F(0x3eaaaa79u);
    float u = c + s2 * a;
    u = s4 * b + u;
    if (q & 1) d = -d;
    float r = d + s * (d * u);
    if (q & 1) r = 1.0f / r;
    return r;
}

// SLEEF asinf, inlined.
float asin(float x) {
    const float ax = std::fabs(x);
    const bool o = ax < 0.5f;
    const float h = (1.0f - ax) * 0.5f;
    const float x2 = o ? x * x : h;
    const float v = o ? ax : sqrt(h);
    float r = v + (x2 * v) * asin_poly(x2);
    if (!o) r = kHalfPi - (r + r);
    return sign_bit_xor(r, x);
}

// SLEEF acosf, inlined.
float acos(float x) {
    const float ax = std::fabs(x);
    const bool o = ax < 0.5f;
    const float h = (1.0f - ax) * 0.5f;
    float v = o ? ax : sqrt(h);
    if (ax == 1.0f) v = 0.0f;
    const float x2 = o ? x * x : h;
    const float u = (x2 * v) * asin_poly(x2);
    const float y = kHalfPi - (sign_bit_xor(v, x) + sign_bit_xor(u, x));
    const float w = v + u;
    float r = o ? y : w + w;
    if (!o && x < 0.0f) r = kPi - r;
    return r;
}

// SLEEF atanf, inlined.
float atan(float x) {
    const bool neg = copysign1(x) == -1.0f;
    float s = neg ? -x : x;
    int32_t q = neg ? 2 : 0;
    if (s > 1.0f) {
        s = 1.0f / s;
        q |= 1;
    }
    const float t = s * s;
    float r = s + s * (t * atan_poly(t));
    if (q & 1) r = kHalfPi - r;
    return neg ? -r : r;
}

// SLEEF atan2f with extra special cases, inlined. Arctan2(y, x).
float atan2(float y, float x) {
    const bool xneg = x < 0.0f;
    int32_t q = xneg ? -2 : 0;
    const float ax = xneg ? -x : x;
    const float ay = std::fabs(y);
    const bool p = ax < ay;
    if (p) ++q;
    const float t = p ? ay : ax;
    float s = p ? -ax : ay;
    const bool xinf = is_inf(x);
    float r;
    if (x == 0.0f || xinf) {
        r = kHalfPi - (xinf ? copysign1(x) * kHalfPi : 0.0f);
    } else {
        s = s / t;
        const float tt = s * s;
        r = s + s * (tt * atan_poly(tt));
        r = float(q) * kHalfPi + r;
        r = sign_bit_xor(r, x);
    }
    if (is_inf(y)) r = kHalfPi - (xinf ? copysign1(x) * F(0x3f490fdbu) : 0.0f);
    if (y == 0.0f) r = copysign1(x) == -1.0f ? kPi : 0.0f;
    r = sign_bit_xor(r, y);
    return (is_nan(x) || is_nan(y)) ? kNaN : r;
}

// SLEEF sinhf_u35, inlined.
float sinh(float x) {
    const float ax = std::fabs(x);
    const float e = expm1k(ax);
    float y = (e * 0.5f) * ((e + 2.0f) / (e + 1.0f));
    if (ax > 88.0f) y = kInf;
    if (is_nan(y)) y = kInf;
    y = sign_bit_xor(y, x);
    return is_nan(x) ? kNaN : y;
}

// SLEEF coshf_u35 (expf inlined), with an overflow bound of 104, inlined.
float cosh(float x) {
    const float ax = std::fabs(x);
    const int32_t q = rint_i(ax * F(0x3fb8aa3bu));
    const float u = float(q);
    float s = ax + u * F(0xbf317200u);
    s = s + u * F(0xb5bfbe8eu);
    float p = F(0x39502bdau);
    p = s * p + F(0x3ab696c9u);
    p = s * p + F(0x3c0888a6u);
    p = s * p + F(0x3d2aaa7au);
    p = s * p + F(0x3e2aaaabu);
    p = s * p + 0.5f;
    float e = s + (s * s) * p;
    e = ldexp2(e + 1.0f, q);
    if (ax < -104.0f) e = 0.0f;  // never true for |x|; kept for the same operation sequence
    if (ax > 104.0f) e = kInf;
    float y = e * 0.5f + 0.5f / e;
    if (ax > 88.0f) y = kInf;
    if (is_nan(y)) y = kInf;
    return is_nan(x) ? kNaN : y;
}

// tanh: expm1(2|x|) / (expm1(2|x|) + 2), 1 beyond |x| = 18.71, inlined.
float tanh(float x) {
    const float ax = std::fabs(x);
    const float e = expm1k(ax + ax);
    float y = e / (e + 2.0f);
    if (ax > F(0x4195b844u)) y = 1.0f;
    if (is_nan(y)) y = 1.0f;
    y = sign_bit_xor(y, x);
    return is_nan(x) ? kNaN : y;
}

// SLEEF logf (u35), inlined.
float log(float x) {
    const bool tiny = x < F(0x00800000u);
    const float d = tiny ? x * F(0x5f800000u) : x;
    if (d < 0.0f) return kNaN;
    if (is_nan(d)) return kNaN;
    if (d == 0.0f) return F(0xff800000u);
    const uint32_t eb = (B(d * F(0x3faaaaabu)) >> 23) & 0xffu;
    const float m = F(B(d) - (eb << 23) + 0x3f800000u);
    const float v = (m + -1.0f) / (m + 1.0f);
    const float v2 = v * v;
    float t = F(0x3e750690u);
    t = v2 * t + F(0x3e920364u);
    t = v2 * t + F(0x3ecccd92u);
    t = v2 * t + F(0x3f2aaaabu);
    t = v2 * t + 2.0f;
    const int32_t e = int32_t(eb) + (tiny ? -191 : -127);
    const float r = float(e) * F(0x3f317218u) + v * t;
    return is_inf(d) ? kInf : r;
}

// Sleef_powf_u10.
float pow(float base, float exponent) {
    const float x = base, y = exponent;
    const float ay = std::fabs(y);
    const bool yisint = (ay >= 16777216.0f) || float(cvtzs(y)) == y;
    const bool yisodd = (cvtzs(y) & 1) && yisint && ay < 16777216.0f;

    float result = expk(dfmul(logk(std::fabs(x)), y));
    if (is_nan(result)) result = kInf;
    float f = yisodd ? -1.0f : 1.0f;
    if (!yisint) f = kNaN;
    if (x >= 0.0f) f = 1.0f;
    result = result * f;

    const float efx = sign_bit_xor(std::fabs(x) + -1.0f, y);
    float yinf = efx == 0.0f ? 1.0f : kInf;
    if (efx < 0.0f) yinf = 0.0f;
    if (is_inf(y)) result = yinf;

    if (x == 0.0f || is_inf(x)) {
        const float sgn = yisodd ? copysign1(x) : 1.0f;
        const float yy = x == 0.0f ? -y : y;
        result = (yy < 0.0f ? 0.0f : kInf) * sgn;
    }
    if (is_nan(x) || is_nan(y)) result = kNaN;
    if (y == 0.0f || x == 1.0f) result = 1.0f;
    return result;
}

}  // namespace sr::fm
