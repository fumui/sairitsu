#include <cmath>
#include <cstdint>
#include <cstring>
#include <functional>
#include <limits>
#include <random>
#include <string>
#include <utility>

#include "doctest.h"
#include "vm/fmath.h"

namespace {

constexpr float kInf = std::numeric_limits<float>::infinity();
constexpr float kNan = std::numeric_limits<float>::quiet_NaN();
constexpr int kSamples = 100000;

uint32_t bits(float x) {
    uint32_t u;
    std::memcpy(&u, &x, sizeof u);
    return u;
}

// Maps float bit patterns to integers that are monotonic in the float value (-0 and +0 adjacent).
int64_t ordered(float x) {
    const uint32_t u = bits(x);
    return (u & 0x80000000u) ? -static_cast<int64_t>(u & 0x7fffffffu) : static_cast<int64_t>(u);
}

int64_t ulp_distance(float a, float b) {
    const int64_t d = ordered(a) - ordered(b);
    return d < 0 ? -d : d;
}

// Fixed-seed generator; uniform doubles are built by hand so inputs do not depend on the standard library.
struct Gen {
    std::mt19937 rng;
    explicit Gen(uint32_t seed) : rng(seed) {}
    double unit() { return (static_cast<double>(rng()) + 0.5) / 4294967296.0; }
    double uniform(double lo, double hi) { return lo + (hi - lo) * unit(); }
    // Magnitude uniform in log space, random sign.
    double log_uniform(double lo, double hi) {
        const double m = std::exp(uniform(std::log(lo), std::log(hi)));
        return (rng() & 1u) ? m : -m;
    }
    double log_positive(double lo, double hi) { return std::fabs(log_uniform(lo, hi)); }
};

void check_ulp(const char* name, const std::function<float(float)>& ours, const std::function<double(double)>& ref,
               const std::function<float(Gen&)>& sample,
               int64_t max_ulp = 1) {
    Gen g(0x5eed1234u);
    int64_t worst = 0;
    float worst_x = 0;
    for (int i = 0; i < kSamples; ++i) {
        const float x = sample(g);
        const float want = static_cast<float>(ref(static_cast<double>(x)));
        const float got = ours(x);
        if (std::isnan(want)) {
            REQUIRE_MESSAGE(std::isnan(got), name << "(" << x << ") should be NaN");
            continue;
        }
        const int64_t d = ulp_distance(got, want);
        if (d > worst) {
            worst = d;
            worst_x = x;
        }
    }
    INFO(std::string(name) << " worst " << worst << " ulp at x=" << worst_x);
    CHECK(worst <= max_ulp);
}

void check_ulp2(const char* name, const std::function<float(float, float)>& ours,
                const std::function<double(double, double)>& ref,
                const std::function<std::pair<float, float>(Gen&)>& sample, int64_t max_ulp = 1) {
    Gen g(0xabcdef01u);
    int64_t worst = 0;
    float wx = 0, wy = 0;
    for (int i = 0; i < kSamples; ++i) {
        const auto [x, y] = sample(g);
        const float want = static_cast<float>(ref(static_cast<double>(x), static_cast<double>(y)));
        const float got = ours(x, y);
        if (std::isnan(want)) {
            REQUIRE(std::isnan(got));
            continue;
        }
        const int64_t d = ulp_distance(got, want);
        if (d > worst) {
            worst = d;
            wx = x;
            wy = y;
        }
    }
    INFO(std::string(name) << " worst " << worst << " ulp at (" << wx << ", " << wy << ")");
    CHECK(worst <= max_ulp);
}

// A dense core range mixed with a wide log-uniform tail.
std::function<float(Gen&)> mixed(double core, double tail_lo, double tail_hi) {
    return [=](Gen& g) {
        return (g.rng() & 3u) ? static_cast<float>(g.uniform(-core, core))
                              : static_cast<float>(g.log_uniform(tail_lo, tail_hi));
    };
}

std::function<float(Gen&)> uniform_in(double lo, double hi) {
    return [=](Gen& g) { return static_cast<float>(g.uniform(lo, hi)); };
}

std::function<float(Gen&)> positive_log(double lo, double hi) {
    return [=](Gen& g) { return static_cast<float>(g.log_positive(lo, hi)); };
}

}  // namespace

TEST_SUITE("vm") {

TEST_CASE("fmath within the documented error of the double reference") {
    using namespace sr;
    // The reference is the platform's double function rounded to float (test-only use of libm).
    // sin, cos and tan include arguments up to 1e9 to exercise the large-argument range reduction.
    // The transcendentals are SLEEF routines (bits pinned in edge_cases_test.cpp):
    // 3.5 ulp for the u35 ones, 1 ulp for pow (u10); against a rounded reference that is 4 and 2. exp, exp2 and
    // log2 are still musl (1 ulp).
    constexpr int64_t kU35 = 4;
    check_ulp("sin", fm::sin, [](double x) { return std::sin(x); }, mixed(10.0, 1e-8, 1e9), kU35);
    check_ulp("cos", fm::cos, [](double x) { return std::cos(x); }, mixed(10.0, 1e-8, 1e9), kU35);
    check_ulp("tan", fm::tan, [](double x) { return std::tan(x); }, mixed(10.0, 1e-8, 1e9), kU35);
    check_ulp("asin", fm::asin, [](double x) { return std::asin(x); }, uniform_in(-1.0, 1.0), kU35);
    check_ulp("acos", fm::acos, [](double x) { return std::acos(x); }, uniform_in(-1.0, 1.0), kU35);
    check_ulp("atan", fm::atan, [](double x) { return std::atan(x); }, mixed(10.0, 1e-8, 1e15), kU35);
    check_ulp("sinh", fm::sinh, [](double x) { return std::sinh(x); }, mixed(10.0, 1e-8, 88.0), kU35);
    check_ulp("cosh", fm::cosh, [](double x) { return std::cosh(x); }, mixed(10.0, 1e-8, 88.0), kU35);
    check_ulp("tanh", fm::tanh, [](double x) { return std::tanh(x); }, mixed(5.0, 1e-8, 20.0), kU35);
    check_ulp("exp", fm::exp, [](double x) { return std::exp(x); }, uniform_in(-87.0, 88.0));
    check_ulp("exp2", fm::exp2, [](double x) { return std::exp2(x); }, uniform_in(-125.0, 127.0));
    check_ulp("log", fm::log, [](double x) { return std::log(x); }, positive_log(1e-37, 1e38), kU35);
    check_ulp("log2", fm::log2, [](double x) { return std::log2(x); }, positive_log(1e-37, 1e38));
    check_ulp("sqrt", fm::sqrt, [](double x) { return std::sqrt(x); }, positive_log(1e-37, 1e38));
    check_ulp2("atan2", fm::atan2, [](double y, double x) { return std::atan2(y, x); }, [](Gen& g) {
        return std::pair<float, float>(static_cast<float>(g.log_uniform(1e-6, 1e6)),
                                       static_cast<float>(g.log_uniform(1e-6, 1e6)));
    }, kU35);
    check_ulp2("pow", fm::pow, [](double b, double e) { return std::pow(b, e); }, [](Gen& g) {
        return std::pair<float, float>(static_cast<float>(g.log_positive(1e-3, 1e3)),
                                       static_cast<float>(g.uniform(-12.0, 12.0)));
    }, 2);
    // Negative bases with integer exponents.
    check_ulp2("pow(negative, integer)", fm::pow, [](double b, double e) { return std::pow(b, e); }, [](Gen& g) {
        return std::pair<float, float>(static_cast<float>(g.uniform(-50.0, 0.0)),
                                       static_cast<float>(static_cast<int>(g.uniform(-6.0, 6.0))));
    }, 2);
}

TEST_CASE("fmath rounding functions are exact") {
    using namespace sr;
    Gen g(77);
    for (int i = 0; i < kSamples; ++i) {
        const float x = static_cast<float>((i & 1) ? g.uniform(-100.0, 100.0) : g.log_uniform(1e-3, 1e10));
        const float y = static_cast<float>(g.log_uniform(1e-2, 1e3));
        REQUIRE(bits(fm::floor(x)) == bits(std::floor(x)));
        REQUIRE(bits(fm::ceil(x)) == bits(std::ceil(x)));
        REQUIRE(bits(fm::trunc(x)) == bits(std::trunc(x)));
        REQUIRE(bits(fm::fmod(x, y)) == bits(std::fmod(x, y)));
    }
}

TEST_CASE("round is half to even") {
    using namespace sr;
    CHECK(fm::round(0.5f) == 0.0f);
    CHECK(fm::round(1.5f) == 2.0f);
    CHECK(fm::round(2.5f) == 2.0f);
    CHECK(fm::round(-0.5f) == 0.0f);
    CHECK(fm::round(-1.5f) == -2.0f);
    CHECK(fm::round(-2.5f) == -2.0f);
    CHECK(fm::round(2.4999998f) == 2.0f);
    CHECK(fm::round(2.5000002f) == 3.0f);
    CHECK(fm::round(8388609.0f) == 8388609.0f);
    CHECK(fm::round(-3.7f) == -4.0f);
    CHECK(std::isnan(fm::round(kNan)));
    CHECK(fm::round(kInf) == kInf);
}

TEST_CASE("fmath special values") {
    using namespace sr;
    CHECK(std::isnan(fm::sin(kNan)));
    CHECK(std::isnan(fm::cos(kNan)));
    CHECK(std::isnan(fm::tan(kNan)));
    CHECK(std::isnan(fm::exp(kNan)));
    CHECK(std::isnan(fm::log(kNan)));
    CHECK(std::isnan(fm::pow(kNan, 2.0f)));
    CHECK(std::isnan(fm::atan2(kNan, 1.0f)));
    CHECK(std::isnan(fm::floor(kNan)));
    CHECK(std::isnan(fm::sqrt(-1.0f)));
    CHECK(std::isnan(fm::sin(kInf)));
    CHECK(std::isnan(fm::cos(-kInf)));
    CHECK(std::isnan(fm::asin(2.0f)));
    CHECK(std::isnan(fm::log(-1.0f)));

    CHECK(bits(fm::sin(-0.0f)) == bits(-0.0f));
    CHECK(bits(fm::sin(0.0f)) == bits(0.0f));
    CHECK(bits(fm::tan(-0.0f)) == bits(-0.0f));
    CHECK(bits(fm::atan(-0.0f)) == bits(-0.0f));
    CHECK(bits(fm::asin(-0.0f)) == bits(-0.0f));
    CHECK(bits(fm::sinh(-0.0f)) == bits(-0.0f));
    CHECK(bits(fm::tanh(-0.0f)) == bits(-0.0f));
    CHECK(fm::cos(0.0f) == 1.0f);

    CHECK(fm::exp(kInf) == kInf);
    CHECK(fm::exp(-kInf) == 0.0f);
    CHECK(fm::exp(1000.0f) == kInf);
    CHECK(fm::exp2(-1000.0f) == 0.0f);
    CHECK(fm::log(0.0f) == -kInf);
    CHECK(fm::log(kInf) == kInf);
    CHECK(fm::log2(0.0f) == -kInf);
    CHECK(fm::sinh(kInf) == kInf);
    CHECK(fm::sinh(-kInf) == -kInf);
    CHECK(fm::cosh(-kInf) == kInf);
    CHECK(fm::tanh(kInf) == 1.0f);
    CHECK(fm::tanh(-kInf) == -1.0f);
    CHECK(fm::pow(0.0f, -1.0f) == kInf);
    CHECK(fm::pow(2.0f, kInf) == kInf);
    CHECK(std::isnan(fm::pow(-8.0f, 1.0f / 3.0f)));
    CHECK(fm::pow(kNan, 0.0f) == 1.0f);
    CHECK(fm::pow(1.0f, kNan) == 1.0f);
    CHECK(fm::floor(kInf) == kInf);
    CHECK(fm::ceil(-kInf) == -kInf);
    CHECK(bits(fm::ceil(-0.5f)) == bits(-0.0f));
    CHECK(std::isnan(fm::fmod(1.0f, 0.0f)));
    CHECK(std::isnan(fm::fmod(kInf, 1.0f)));
    CHECK(bits(fm::atan2(0.0f, -1.0f)) == bits(3.14159274f));
    CHECK(bits(fm::atan2(-0.0f, -1.0f)) == bits(-3.14159274f));
}

// Rem, Mod and Sign (tests/vm/edge_cases_test.cpp has more cases).
TEST_CASE("rem is x - y * (int64)(x / y) in double") {
    using namespace sr;
    CHECK(bits(fm::rem(-4.0f, 2.0f)) == bits(0.0f));
    CHECK(bits(fm::rem(-0.0f, 2.0f)) == bits(-0.0f));
    CHECK(bits(fm::rem(-0.0f, -3.0f)) == bits(-0.0f));  // a zero x is returned as is
    CHECK(bits(fm::rem(0.0f, -1.0f)) == bits(0.0f));
    CHECK(bits(fm::rem(4.0f, 2.0f)) == bits(0.0f));
    CHECK(bits(fm::rem(4.0f, -2.0f)) == bits(0.0f));
    CHECK(fm::rem(-5.0f, 3.0f) == -2.0f);
    CHECK(fm::rem(5.0f, -3.0f) == 2.0f);
    CHECK(fm::rem(5.5f, 2.0f) == 1.5f);
    CHECK(fm::rem(5.0f, kInf) == 5.0f);
    CHECK(fm::rem(1e30f, 7.0f) == 0.0f);  // |x / y| >= 2^63: the quotient as it is
    CHECK(bits(fm::rem(-0.0f, -kInf)) == bits(-0.0f));
    CHECK(std::isnan(fm::rem(5.0f, 0.0f)));
    CHECK(std::isnan(fm::rem(kInf, 3.0f)));
    CHECK(std::isnan(fm::rem(kNan, 3.0f)));
}

TEST_CASE("mod is rem(rem(x, y) + y, y)") {
    using namespace sr;
    CHECK(fm::mod(-1.0f, 3.0f) == 2.0f);
    CHECK(fm::mod(1.0f, -3.0f) == -2.0f);
    CHECK(fm::mod(-1.0f, -3.0f) == -1.0f);
    CHECK(fm::mod(7.0f, 3.0f) == 1.0f);
    CHECK(fm::mod(5.5f, 2.0f) == 1.5f);
    CHECK(bits(fm::mod(-4.0f, 2.0f)) == bits(0.0f));
    CHECK(bits(fm::mod(4.0f, -2.0f)) == bits(0.0f));  // a zero result is +0 whatever the divisor's sign
    CHECK(std::isnan(fm::mod(5.0f, 0.0f)));
    CHECK(std::isnan(fm::mod(5.0f, kInf)));  // rem(5 + inf, inf)
}

TEST_CASE("frac and sign") {
    using namespace sr;
    CHECK(fm::frac(1.25f) == 0.25f);
    CHECK(fm::frac(-0.25f) == 0.75f);
    CHECK(fm::frac(-3.0f) == 0.0f);
    CHECK(fm::frac(-1e-10f) == 1.0f);  // Python: -1e-10 % 1 == 1.0
    CHECK(fm::sign(3.0f) == 1.0f);
    CHECK(fm::sign(-3.0f) == -1.0f);
    CHECK(bits(fm::sign(0.0f)) == bits(0.0f));
    CHECK(bits(fm::sign(-0.0f)) == bits(0.0f));
    CHECK(bits(fm::sign(kNan)) == bits(0.0f));
    CHECK(fm::sign(kInf) == 1.0f);
    CHECK(fm::sign(-kInf) == -1.0f);
}

}  // TEST_SUITE
