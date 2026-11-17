#include <cstdint>
#include <limits>

#include "doctest.h"
#include "vm/convert.h"

TEST_SUITE("vm") {

TEST_CASE("to_index truncates and saturates") {
    using sr::vm::to_index;
    CHECK(to_index(1.9f) == 1);
    CHECK(to_index(-1.9f) == -1);
    CHECK(to_index(0.0f) == 0);
    CHECK(to_index(-0.0f) == 0);
    CHECK(to_index(-0.9f) == 0);
    CHECK(to_index(123456.0f) == 123456);
    CHECK(to_index(std::numeric_limits<float>::quiet_NaN()) == 0);
    CHECK(to_index(3e9f) == INT32_MAX);
    CHECK(to_index(-3e9f) == INT32_MIN);
    CHECK(to_index(2147483648.0f) == INT32_MAX);
    CHECK(to_index(-2147483648.0f) == INT32_MIN);
    CHECK(to_index(2147483520.0f) == 2147483520);  // largest float below 2^31
    CHECK(to_index(std::numeric_limits<float>::infinity()) == INT32_MAX);
    CHECK(to_index(-std::numeric_limits<float>::infinity()) == INT32_MIN);
}

}  // TEST_SUITE
