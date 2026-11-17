#include "doctest.h"
#include "util/fp.h"

TEST_CASE("fp contract is off") {
    volatile float a = 0x1.001p0f, c = -0x1.002p0f;   // a*a = 1 + 2^-11 + 2^-24 exactly; rounds (tie, even) to 1 + 2^-11
    CHECK(sr::mul_add_unfused(a, a, c) == 0.0f);       // a fused multiply-add would give 2^-24
}
