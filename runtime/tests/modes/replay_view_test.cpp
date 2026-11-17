// What Watch shows of a replay (modes/replay_view.h): touch markers, the bar's Miss and Good ticks, the seek range.
#include "modes/replay_view.h"

#include "doctest.h"

using namespace sr;

namespace {

modes::Replay replay_of(int32_t width, int32_t height) {
    modes::Replay r;
    r.width = width;
    r.height = height;
    return r;
}

}  // namespace

TEST_SUITE("modes") {

TEST_CASE("markers interpolate alive touches") {
    modes::Replay r = replay_of(1600, 1068);
    r.touches.push_back(modes::ReplayTouch{{{0.0, 0.0f, 0.25f}, {1.0, 1.0f, 0.25f}}});
    const float aspect = 1600.0f / 1068.0f;  // the replay's own play area: no mapping

    const auto mid = modes::replay_markers(r, 0.5, aspect);
    REQUIRE(mid.size() == 1);
    CHECK(mid[0].x == doctest::Approx(0.5f));
    CHECK(mid[0].y == doctest::Approx(0.25f));
    CHECK(modes::replay_markers(r, 1.5, aspect).empty());
    CHECK(modes::replay_markers(r, -0.1, aspect).empty());
    // The ends are alive.
    REQUIRE(modes::replay_markers(r, 0.0, aspect).size() == 1);
    REQUIRE(modes::replay_markers(r, 1.0, aspect).size() == 1);
    CHECK(modes::replay_markers(r, 1.0, aspect)[0].x == doctest::Approx(1.0f));

    // Several points: the segment holding the time; a one-point touch shows only at its time.
    r.touches[0].points.push_back({3.0, -1.0f, 0.25f});
    CHECK(modes::replay_markers(r, 2.0, aspect)[0].x == doctest::Approx(0.0f));
    r.touches.push_back(modes::ReplayTouch{{{2.0, 0.5f, -0.5f}}});
    CHECK(modes::replay_markers(r, 2.0, aspect).size() == 2);
    CHECK(modes::replay_markers(r, 2.01, aspect).size() == 1);
}

TEST_CASE("markersMapOntoOtherAspect") {
    modes::Replay r = replay_of(3200, 2136);  // aspect 1.498
    r.touches.push_back(modes::ReplayTouch{{{0.0, 1.0f, -0.4f}, {1.0, 1.0f, -0.4f}}});
    const auto m = modes::replay_markers(r, 0.5, 2.0f);
    REQUIRE(m.size() == 1);
    CHECK(m[0].x == doctest::Approx(1.335f).epsilon(1e-3));
    CHECK(m[0].y == doctest::Approx(-0.4f));
}

TEST_CASE("ticks keep only miss and good") {
    modes::Replay r = replay_of(1600, 1068);
    r.inputs = {{0, 1.0f, 1, 0}, {1, 2.0f, 0, 0}, {2, 3.0f, 2, 0}, {3, 4.0f, 3, 0}};  // Perfect, Miss, Great, Good
    const auto ticks = modes::replay_ticks(r);
    REQUIRE(ticks.size() == 2);
    CHECK(ticks[0].time == 2.0f);
    CHECK(ticks[0].miss);
    CHECK(ticks[1].time == 4.0f);
    CHECK_FALSE(ticks[1].miss);
}

TEST_CASE("seekClampsToRange") {
    CHECK(modes::clamp_seek(-5, 100) == -2);
    CHECK(modes::clamp_seek(150, 100) == 100);
    CHECK(modes::clamp_seek(42, 100) == 42);
}

}  // TEST_SUITE
