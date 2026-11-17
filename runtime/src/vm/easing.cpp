#include "vm/easing.h"

#include <bit>
#include <cstdint>

#include "vm/fmath.h"

namespace sr::vm {

EaseKind ease_kind(std::string_view name) {
    static constexpr std::string_view kNames[kEaseKindCount] = {
        "linear",
        "inSine", "outSine", "inOutSine", "outInSine",
        "inQuad", "outQuad", "inOutQuad", "outInQuad",
        "inCubic", "outCubic", "inOutCubic", "outInCubic",
        "inQuart", "outQuart", "inOutQuart", "outInQuart",
        "inQuint", "outQuint", "inOutQuint", "outInQuint",
        "inExpo", "outExpo", "inOutExpo", "outInExpo",
        "inCirc", "outCirc", "inOutCirc", "outInCirc",
        "inBack", "outBack", "inOutBack", "outInBack",
        "inElastic", "outElastic", "inOutElastic", "outInElastic",
    };
    for (int i = 0; i < kEaseKindCount; ++i)
        if (kNames[i] == name) return EaseKind(i);
    return EaseKind::Linear;
}

namespace {

// The easings in float32, operation by operation: fixed constants (float bit patterns), a fixed operation
// order, sin/cos/pow through the SLEEF ports in sr::fm.
constexpr float F(uint32_t u) { return std::bit_cast<float>(u); }
constexpr float kPi = F(0x40490fdbu);
constexpr float kC1 = F(0x3fd9cd60u);       // 1.70158
constexpr float kC3 = F(0x402ce6b0u);       // 2.70158
constexpr float kC3x4 = F(0x412ce6b0u);     // 10.80632, InOutBack's lower half: In(2x) / 2 scaled out
constexpr float kC1x2 = F(0x4059cd60u);     // 3.40316
constexpr float kC3Half = F(0x3face6b0u);   // 1.35079
constexpr float kC1Half = F(0x3f59cd60u);   // 0.85079

// Integer powers are repeated multiplication from the left, x * x * x ....
float sq(float x) { return x * x; }
float cb(float x) { return x * x * x; }
float q4(float x) { return x * x * x * x; }
float q5(float x) { return x * x * x * x * x; }
float pow2(float e) { return fm::pow(2.0f, e); }

// fmaxnm(fminnm(x, 1), 0): a (quiet) NaN clamps to 1, -0 to +0.
float clamp01(float x) {
    const float m = (x < 1.0f) ? x : 1.0f;
    return (m > 0.0f) ? m : 0.0f;
}

// The upper half of the InOut polynomial easings: 1 - t^n / 2 with t = 2 - 2x, as t^n * -0.5 + 1.
float in_out_upper(float tn) { return tn * -0.5f + 1.0f; }

// Elastic's sine argument: ((v + v) * pi) / 3, i.e. v * 2pi / 3.
float elastic_angle(float v) { return ((v + v) * kPi) / 3.0f; }

float in_sine(float x) { return 1.0f - fm::cos((x * kPi) * 0.5f); }
float out_sine(float x) { return fm::sin((x * kPi) * 0.5f); }
float in_out_sine(float x) { return (fm::cos(x * kPi) + -1.0f) * -0.5f; }
float out_in_sine(float x) {
    if (x < 0.5f) return fm::sin(x * kPi) * 0.5f;
    return fm::cos((x + -0.5f) * kPi) * -0.5f + 1.0f;
}

float in_quad(float x) { return sq(x); }
float out_quad(float x) { return 1.0f - sq(1.0f - x); }
float in_out_quad(float x) {
    if (x < 0.5f) return x * (x + x);
    return in_out_upper(sq(2.0f - (x + x)));
}
float out_in_quad(float x) {
    if (x < 0.5f) return (1.0f - sq(1.0f - 2.0f * x)) / 2.0f;
    return sq(2.0f * x - 1.0f) / 2.0f + 0.5f;
}

float in_cubic(float x) { return cb(x); }
float out_cubic(float x) { return 1.0f - cb(1.0f - x); }
float in_out_cubic(float x) {
    if (x < 0.5f) return x * (x * (x * 4.0f));
    return in_out_upper(cb(2.0f - (x + x)));
}
float out_in_cubic(float x) {
    if (x < 0.5f) return (1.0f - cb(1.0f - 2.0f * x)) / 2.0f;
    return cb(2.0f * x - 1.0f) / 2.0f + 0.5f;
}

float in_quart(float x) { return q4(x); }
float out_quart(float x) { return 1.0f - q4(1.0f - x); }
float in_out_quart(float x) {
    if (x < 0.5f) return x * (x * (x * (x * 8.0f)));
    return in_out_upper(q4(2.0f - (x + x)));
}
float out_in_quart(float x) {
    if (x < 0.5f) return (1.0f - q4(1.0f - 2.0f * x)) / 2.0f;
    return q4(2.0f * x - 1.0f) / 2.0f + 0.5f;
}

float in_quint(float x) { return q5(x); }
float out_quint(float x) { return 1.0f - q5(1.0f - x); }
float in_out_quint(float x) {
    if (x < 0.5f) return x * (x * (x * (x * (x * 16.0f))));
    return in_out_upper(q5(2.0f - (x + x)));
}
float out_in_quint(float x) {
    if (x < 0.5f) return (1.0f - q5(1.0f - 2.0f * x)) / 2.0f;
    return q5(2.0f * x - 1.0f) / 2.0f + 0.5f;
}

float in_expo(float x) { return x == 0.0f ? 0.0f : pow2(x * 10.0f + -10.0f); }
float out_expo(float x) { return x == 1.0f ? 1.0f : 1.0f - pow2(x * -10.0f); }
float in_out_expo(float x) {
    if (x == 0.0f || x == 1.0f) return x;
    const float a = x * 20.0f;
    if (x < 0.5f) return pow2(a + -10.0f) * 0.5f;
    return pow2(10.0f - a) * -0.5f + 1.0f;
}
float out_in_expo(float x) {
    if (x == 0.0f || x == 1.0f) return x;
    if (x < 0.5f) return 0.5f - pow2(x * -20.0f) * 0.5f;
    return pow2(x * 20.0f + -20.0f) * 0.5f + 0.5f;
}

float in_circ(float x) { return 1.0f - fm::sqrt(1.0f - sq(x)); }
float out_circ(float x) { return fm::sqrt(1.0f - sq(x - 1.0f)); }
float in_out_circ(float x) {
    if (x < 0.5f) return (1.0f - fm::sqrt(1.0f - sq(2.0f * x))) / 2.0f;
    return (fm::sqrt(1.0f - sq(2.0f * x - 2.0f)) + 1.0f) / 2.0f;
}
float out_in_circ(float x) {
    const float h = fm::sqrt(1.0f - sq((x + x) + -1.0f)) * 0.5f;
    return x < 0.5f ? h : 1.0f - h;
}

// c3 * x * x * x - c1 * x * x, multiplied from the left.
float in_back(float x) { return kC3 * x * x * x - kC1 * x * x; }
float out_back(float x) {
    const float y = x + -1.0f;
    return (kC3 * y * y * y + 1.0f) + kC1 * y * y;
}
// The In/Out composition, with the halving folded into the constants.
float in_out_back(float x) {
    if (x < 0.5f) return kC3x4 * x * x * x - kC1x2 * x * x;
    const float t = (x + x) + -2.0f;
    return (kC3Half * t * t * t + 1.0f) + kC1Half * t * t;
}
float out_in_back(float x) {
    const float t = (x + x) + -1.0f;
    const float a = kC3Half * t * t * t + 0.5f;
    const float b = kC1Half * t * t;
    return x < 0.5f ? a + b : a + -b;
}

float in_elastic(float x) {
    if (x == 0.0f || x == 1.0f) return x;
    const float a = x * 10.0f;
    const float p = pow2(a + -10.0f);
    return -(p * fm::sin(elastic_angle(a + -10.75f)));
}
float out_elastic(float x) {
    if (x == 0.0f || x == 1.0f) return x;
    const float p = pow2(x * -10.0f);
    return p * fm::sin(elastic_angle(x * 10.0f + -0.75f)) + 1.0f;
}
// InOutElastic uses the period and offset of In/OutElastic (2pi / 3, 10.75), not 2pi / 4.5 and
// 11.125 as easings.net does.
float in_out_elastic(float x) {
    if (x == 0.0f || x == 1.0f) return x;
    const float a = x * 20.0f;
    if (x < 0.5f) {
        const float p = pow2(a + -10.0f);
        return (fm::sin(elastic_angle(a + -10.75f)) * p) * -0.5f;
    }
    const float p = pow2(10.0f - a);
    return (p * fm::sin(elastic_angle(a + -10.75f))) * 0.5f + 1.0f;
}
float out_in_elastic(float x) {
    if (x == 0.0f || x == 1.0f) return x;
    if (x < 0.5f) {
        const float h = pow2(x * -20.0f) * 0.5f;
        return h * fm::sin(elastic_angle(x * 20.0f + -0.75f)) + 0.5f;
    }
    const float a = ((x + x) + -1.0f) * 10.0f;
    const float h = pow2(a + -10.0f) * 0.5f;
    return 0.5f - h * fm::sin(elastic_angle(a + -10.75f));
}

}  // namespace

float ease(EaseKind kind, float input) {
    const float x = clamp01(input);
    switch (kind) {
        case EaseKind::Linear: return x;
        case EaseKind::InSine: return in_sine(x);
        case EaseKind::OutSine: return out_sine(x);
        case EaseKind::InOutSine: return in_out_sine(x);
        case EaseKind::OutInSine: return out_in_sine(x);
        case EaseKind::InQuad: return in_quad(x);
        case EaseKind::OutQuad: return out_quad(x);
        case EaseKind::InOutQuad: return in_out_quad(x);
        case EaseKind::OutInQuad: return out_in_quad(x);
        case EaseKind::InCubic: return in_cubic(x);
        case EaseKind::OutCubic: return out_cubic(x);
        case EaseKind::InOutCubic: return in_out_cubic(x);
        case EaseKind::OutInCubic: return out_in_cubic(x);
        case EaseKind::InQuart: return in_quart(x);
        case EaseKind::OutQuart: return out_quart(x);
        case EaseKind::InOutQuart: return in_out_quart(x);
        case EaseKind::OutInQuart: return out_in_quart(x);
        case EaseKind::InQuint: return in_quint(x);
        case EaseKind::OutQuint: return out_quint(x);
        case EaseKind::InOutQuint: return in_out_quint(x);
        case EaseKind::OutInQuint: return out_in_quint(x);
        case EaseKind::InExpo: return in_expo(x);
        case EaseKind::OutExpo: return out_expo(x);
        case EaseKind::InOutExpo: return in_out_expo(x);
        case EaseKind::OutInExpo: return out_in_expo(x);
        case EaseKind::InCirc: return in_circ(x);
        case EaseKind::OutCirc: return out_circ(x);
        case EaseKind::InOutCirc: return in_out_circ(x);
        case EaseKind::OutInCirc: return out_in_circ(x);
        case EaseKind::InBack: return in_back(x);
        case EaseKind::OutBack: return out_back(x);
        case EaseKind::InOutBack: return in_out_back(x);
        case EaseKind::OutInBack: return out_in_back(x);
        case EaseKind::InElastic: return in_elastic(x);
        case EaseKind::OutElastic: return out_elastic(x);
        case EaseKind::InOutElastic: return in_out_elastic(x);
        case EaseKind::OutInElastic: return out_in_elastic(x);
    }
    return x;
}

}  // namespace sr::vm
