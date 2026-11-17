#include <cmath>
#include <cstdint>
#include <cstring>
#include <initializer_list>

#include "doctest.h"
#include "vm/easing.h"

namespace {

using sr::vm::EaseKind;
using sr::vm::ease;

constexpr const char* kNames[sr::vm::kEaseKindCount] = {
    "Linear",
    "InSine", "OutSine", "InOutSine", "OutInSine",
    "InQuad", "OutQuad", "InOutQuad", "OutInQuad",
    "InCubic", "OutCubic", "InOutCubic", "OutInCubic",
    "InQuart", "OutQuart", "InOutQuart", "OutInQuart",
    "InQuint", "OutQuint", "InOutQuint", "OutInQuint",
    "InExpo", "OutExpo", "InOutExpo", "OutInExpo",
    "InCirc", "OutCirc", "InOutCirc", "OutInCirc",
    "InBack", "OutBack", "InOutBack", "OutInBack",
    "InElastic", "OutElastic", "InOutElastic", "OutInElastic",
};

}  // namespace

TEST_SUITE("vm") {

TEST_CASE("easings hit endpoints") {
    for (int i = 0; i < sr::vm::kEaseKindCount; ++i) {
        const auto k = static_cast<EaseKind>(i);
        INFO(kNames[i]);
        CHECK(ease(k, 0.0f) == 0.0f);
        CHECK(ease(k, 1.0f) == 1.0f);
        CHECK(ease(k, -0.5f) == 0.0f);
        CHECK(ease(k, 2.0f) == 1.0f);
    }
}

TEST_CASE("easings match the double reference closely") {
    // Reference: the sonolus.py formulas evaluated in double. Float32 arithmetic drifts a few ulp from it.
    const double pi = 3.14159265358979323846;
    const double c1 = 1.70158, c3 = c1 + 1, c4 = 2 * pi / 3;
    for (int i = 1; i < 100; ++i) {
        const double x = i / 100.0;
        const float xf = static_cast<float>(x);
        CHECK(ease(EaseKind::Linear, xf) == xf);
        CHECK(ease(EaseKind::InSine, xf) == doctest::Approx(1 - std::cos(x * pi / 2)).epsilon(1e-5).scale(1.0));
        CHECK(ease(EaseKind::OutQuad, xf) == doctest::Approx(1 - (1 - x) * (1 - x)).epsilon(1e-5).scale(1.0));
        CHECK(ease(EaseKind::InOutCubic, xf) ==
              doctest::Approx(x < 0.5 ? 4 * x * x * x : 1 - std::pow(-2 * x + 2, 3) / 2).epsilon(1e-5).scale(1.0));
        CHECK(ease(EaseKind::InBack, xf) == doctest::Approx(c3 * x * x * x - c1 * x * x).epsilon(1e-5).scale(1.0));
        CHECK(ease(EaseKind::OutElastic, xf) ==
              doctest::Approx(std::pow(2, -10 * x) * std::sin((x * 10 - 0.75) * c4) + 1).epsilon(1e-5).scale(1.0));
        CHECK(ease(EaseKind::InExpo, xf) == doctest::Approx(std::pow(2, 10 * x - 10)).epsilon(1e-5).scale(1.0));
        CHECK(ease(EaseKind::OutCirc, xf) == doctest::Approx(std::sqrt(1 - (x - 1) * (x - 1))).epsilon(1e-5).scale(1.0));
    }
}

TEST_CASE("easings are continuous at one half") {
    for (const EaseKind k : {EaseKind::InOutSine, EaseKind::OutInSine, EaseKind::InOutQuad, EaseKind::OutInQuad,
                             EaseKind::InOutCubic, EaseKind::OutInCubic, EaseKind::InOutQuart, EaseKind::OutInQuart,
                             EaseKind::InOutQuint, EaseKind::OutInQuint, EaseKind::InOutExpo, EaseKind::OutInExpo,
                             EaseKind::InOutCirc, EaseKind::OutInCirc, EaseKind::InOutBack, EaseKind::OutInBack}) {
        INFO(kNames[static_cast<int>(k)]);
        CHECK(ease(k, 0.49999f) == doctest::Approx(ease(k, 0.50001f)).epsilon(1e-2).scale(1.0));
    }
}

TEST_CASE("ease clamps like python min and max") {
    CHECK(ease(EaseKind::Linear, std::nanf("")) == 1.0f);
    CHECK(std::signbit(ease(EaseKind::Linear, -0.0f)) == false);
}

}  // TEST_SUITE
