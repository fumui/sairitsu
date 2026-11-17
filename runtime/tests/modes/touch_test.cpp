// TouchPool: pointer events to the per-frame Runtime Touch Array entries.
#include <cstdint>
#include <vector>

#include "doctest.h"
#include "modes/touch.h"
#include "vm/fmath.h"

using namespace sr::modes;

namespace {

TouchEvent ev(int32_t p, TouchEvent::Phase ph, double t, float x = 0, float y = 0) { return {p, ph, t, x, y}; }

}  // namespace

TEST_CASE("a tap within one frame is started and ended") {
    TouchPool pool;
    pool.push(ev(7, TouchEvent::Down, 0.1, 0.5f, 0.25f));
    pool.push(ev(7, TouchEvent::Up, 0.2, 0.5f, 0.25f));
    const auto v = pool.frame(0.3);
    REQUIRE(v.size() == 1);
    CHECK(v[0].started);
    CHECK(v[0].ended);
    CHECK(v[0].t == doctest::Approx(0.2f));
    CHECK(v[0].st == doctest::Approx(0.1f));
    CHECK(v[0].x == 0.5f);
    CHECK(v[0].sy == 0.25f);
    CHECK(pool.frame(0.4).empty());
}

TEST_CASE("a held touch reports delta since the last cycle") {
    TouchPool pool;
    pool.push(ev(1, TouchEvent::Down, 0.0, 0, 0));
    auto v = pool.frame(0.01);
    REQUIRE(v.size() == 1);
    CHECK(v[0].started);
    pool.push(ev(1, TouchEvent::Move, 0.05, 0.5f, 0.0f));
    v = pool.frame(0.06);
    REQUIRE(v.size() == 1);
    CHECK_FALSE(v[0].started);
    CHECK(v[0].dx == 0.5f);
    pool.push(ev(1, TouchEvent::Move, 0.1, 0.75f, 0.0f));
    v = pool.frame(0.11);
    REQUIRE(v.size() == 1);
    CHECK(v[0].dx == 0.25f);
    CHECK(v[0].sx == 0.0f);
    CHECK(v[0].x == 0.75f);
    // Held still: still reported, no delta, no velocity.
    v = pool.frame(0.2);
    REQUIRE(v.size() == 1);
    CHECK(v[0].dx == 0.0f);
    CHECK(v[0].vx == 0.0f);
    CHECK(v[0].vr == 0.0f);
    CHECK(v[0].t == doctest::Approx(0.1f));
}

TEST_CASE("velocity follows the touch rules") {
    TouchPool pool;
    pool.push(ev(3, TouchEvent::Down, 0.0, 0, 0));
    pool.frame(0.01);
    pool.push(ev(3, TouchEvent::Move, 0.1, 0.3f, 0.4f));
    const auto v = pool.frame(0.11);
    REQUIRE(v.size() == 1);
    CHECK(v[0].id == 101.0f);
    CHECK_FALSE(v[0].started);
    CHECK_FALSE(v[0].ended);
    CHECK(v[0].dx == doctest::Approx(0.3f));
    CHECK(v[0].dy == doctest::Approx(0.4f));
    CHECK(v[0].vx == doctest::Approx(3.0f).epsilon(1e-5));
    CHECK(v[0].vy == doctest::Approx(4.0f).epsilon(1e-5));
    CHECK(v[0].vr == doctest::Approx(5.0f).epsilon(1e-5));
    CHECK(v[0].vw == doctest::Approx(sr::fm::atan2(0.4f, 0.3f)).epsilon(1e-6));
}

TEST_CASE("velocity uses the float difference of float times") {
    // A record holds T as float: dt = float(T) - float(prevT), not float(T - prevT). At 1000 s the float
    // ulp is 2^-14, so 1000.0001 is stored as 1000 + 2^-13 and dt is 2^-13, not 1e-4.
    TouchPool pool;
    pool.push(ev(1, TouchEvent::Down, 1000.0, 0, 0));
    pool.frame(1000.0);
    pool.push(ev(1, TouchEvent::Move, 1000.0001, 1.0f, 0));
    const auto v = pool.frame(1000.0001);
    REQUIRE(v.size() == 1);
    const float dt = float(1000.0001) - float(1000.0);
    CHECK(dt == 0x1p-13f);
    CHECK(v[0].vx == 1.0f / dt);
}

TEST_CASE("cancel ends the touch") {
    TouchPool pool;
    pool.push(ev(2, TouchEvent::Down, 0.0));
    pool.frame(0.01);
    pool.push(ev(2, TouchEvent::Cancel, 0.05, 0.1f, 0.0f));
    auto v = pool.frame(0.06);
    REQUIRE(v.size() == 1);
    CHECK(v[0].ended);
    CHECK_FALSE(v[0].started);
    CHECK(pool.frame(0.07).empty());
}

TEST_CASE("touches past capacity are dropped while down") {
    TouchPool pool;
    for (int32_t p = 0; p < 33; ++p) pool.push(ev(p, TouchEvent::Down, 0.0));
    pool.push(ev(32, TouchEvent::Move, 0.01, 1, 1));
    auto v = pool.frame(0.02);
    CHECK(v.size() == 32);
    for (const Touch& t : v) CHECK(t.id != 0.0f);
    // Pointer 32 stays absent while down, and when one of the others comes up it still does.
    pool.push(ev(0, TouchEvent::Up, 0.03));
    pool.push(ev(32, TouchEvent::Move, 0.03, 2, 2));
    v = pool.frame(0.04);
    CHECK(v.size() == 32);
    v = pool.frame(0.05);
    CHECK(v.size() == 31);
    pool.push(ev(32, TouchEvent::Up, 0.06));
    CHECK(pool.frame(0.07).size() == 31);
    pool.push(ev(32, TouchEvent::Down, 0.08));
    v = pool.frame(0.09);
    CHECK(v.size() == 32);
    CHECK(v[31].started);
}

TEST_CASE("events after the frame time wait") {
    TouchPool pool;
    pool.push(ev(1, TouchEvent::Down, 0.5));
    CHECK(pool.frame(0.4).empty());
    const auto v = pool.frame(0.6);
    REQUIRE(v.size() == 1);
    CHECK(v[0].started);
}

TEST_CASE("events cleared before a frame are dropped") {
    TouchPool pool;
    pool.push(ev(1, TouchEvent::Down, 0.0));
    pool.clear();
    CHECK(pool.frame(0.1).empty());
    pool.push(ev(1, TouchEvent::Move, 0.2, 1, 1));
    CHECK(pool.frame(0.3).empty());
}

TEST_CASE("clear forgets touches that are down") {
    TouchPool pool;
    pool.push(ev(1, TouchEvent::Down, 0.0));
    CHECK(pool.frame(0.1).size() == 1);
    pool.clear();
    pool.push(ev(1, TouchEvent::Move, 0.2, 1, 1));
    pool.push(ev(1, TouchEvent::Up, 0.25, 1, 1));
    CHECK(pool.frame(0.3).empty());
}

TEST_CASE("ids follow the touch rules") {
    TouchPool pool;
    pool.push(ev(9, TouchEvent::Down, 0.0));
    pool.push(ev(4, TouchEvent::Down, 0.0));
    auto v = pool.frame(0.1);
    REQUIRE(v.size() == 2);
    CHECK(v[0].id == 101.0f);
    CHECK(v[1].id == 102.0f);
    pool.push(ev(9, TouchEvent::Up, 0.15));
    pool.push(ev(4, TouchEvent::Up, 0.15));
    pool.frame(0.2);
    pool.clear();
    pool.push(ev(9, TouchEvent::Down, 0.3));
    v = pool.frame(0.4);
    REQUIRE(v.size() == 1);
    CHECK(v[0].id == 103.0f);
    CHECK(TouchPool().frame(0).empty());
    TouchPool fresh;
    fresh.push(ev(1, TouchEvent::Down, 0.0));
    CHECK(fresh.frame(0.1)[0].id == 101.0f);
}

TEST_CASE("to_touch_event: the centre is the origin") {
    const TouchEvent e = to_touch_event(RawTouch{5, 7, TouchEvent::Down, 1600, 1068}, 3200, 2136, 12.5, 0.0f);
    CHECK(e.pointer == 7);
    CHECK(e.phase == TouchEvent::Down);
    CHECK(e.x == 0.0f);
    CHECK(e.y == 0.0f);
    CHECK(e.t == 12.5);
}

TEST_CASE("to_touch_event: the corners and the y flip") {
    const int32_t w = 3200, h = 2136;
    const double half_h = h * 0.5;
    const TouchEvent tl = to_touch_event(RawTouch{0, 0, TouchEvent::Move, 0, 0}, w, h, 0, 0);
    CHECK(tl.x == float(-1600.0 / half_h));
    CHECK(tl.y == 1.0f);  // Android's y grows downward; the screen's up
    const TouchEvent br = to_touch_event(RawTouch{0, 0, TouchEvent::Up, 3200, 2136}, w, h, 0, 0);
    CHECK(br.x == float(1600.0 / half_h));
    CHECK(br.y == -1.0f);
    const TouchEvent tr = to_touch_event(RawTouch{0, 0, TouchEvent::Cancel, 3200, 0}, w, h, 0, 0);
    CHECK(tr.x == float(1600.0 / half_h));
    CHECK(tr.y == 1.0f);
    const TouchEvent bl = to_touch_event(RawTouch{0, 0, TouchEvent::Down, 0, 2136}, w, h, 0, 0);
    CHECK(bl.x == float(-1600.0 / half_h));
    CHECK(bl.y == -1.0f);
    // A quarter of the way down is y = 0.5.
    CHECK(to_touch_event(RawTouch{0, 0, TouchEvent::Move, 1600, 534}, w, h, 0, 0).y == 0.5f);
}

TEST_CASE("to_touch_event: the time is the heard time minus the input offset") {
    const TouchEvent e = to_touch_event(RawTouch{0, 0, TouchEvent::Down, 0, 0}, 100, 100, 3.25, 0.05f);
    CHECK(e.t == 3.25 - double(0.05f));
    CHECK(to_touch_event(RawTouch{0, 0, TouchEvent::Down, 0, 0}, 100, 100, 3.25, -0.5f).t == 3.75);
}

TEST_CASE("the phase values the UI sends are the enum's") {
    static_assert(TouchEvent::Down == 0 && TouchEvent::Move == 1 && TouchEvent::Up == 2 && TouchEvent::Cancel == 3);
    CHECK(int(TouchEvent::Cancel) == 3);
}

TEST_CASE("touches while paused are dropped") {
    TouchPool pool;
    pool.push(ev(1, TouchEvent::Down, 0.0));
    CHECK(pool.frame(0.05).size() == 1);  // down when the pause comes
    std::vector<RawTouch> raw = {RawTouch{200'000'000, 1, TouchEvent::Move, 10, 10},
                                 RawTouch{210'000'000, 2, TouchEvent::Down, 20, 20}};
    const auto heard = [](int64_t ns) { return double(ns) * 1e-9; };
    drain_touches(raw, pool, /*paused=*/true, 100, 100, 0.0f, 0, heard);
    CHECK(raw.empty());
    // The pool forgot the touch that was down and took none of the queued events.
    CHECK(pool.frame(1.0).empty());
    // Running again: events flow into the pool.
    raw = {RawTouch{500'000'000, 3, TouchEvent::Down, 50, 50}};
    drain_touches(raw, pool, /*paused=*/false, 100, 100, 0.0f, 0, heard);
    CHECK(raw.empty());
    CHECK(pool.frame(1.0).size() == 1);
}

TEST_CASE("a touch at a frame's vsync has that frame's time: the update timeline") {
    // A frame's time is taken when the frame updates and a touch's t on the same clock, so a touch
    // at the vsync of a frame reads that frame's time. Ours takes a frame's time at its expected present time, a
    // pipeline latency after the vsync, so a touch's CLOCK_MONOTONIC time moves by the same latency (without
    // it every touch read about 23 ms early).
    constexpr int64_t kVsync = 3'000'000'000;
    constexpr int64_t kPipeline = 28'666'667;  // present - vsync, set explicitly
    const auto heard = [](int64_t ns) { return double(ns - 1'000'000'000) * 1e-9; };  // music 0 at 1 s
    const double frame_time = heard(kVsync + kPipeline);
    std::vector<RawTouch> raw = {RawTouch{kVsync, 1, TouchEvent::Down, 50, 50},
                                 RawTouch{kVsync - 5'000'000, 2, TouchEvent::Down, 50, 50}};
    TouchPool pool;
    drain_touches(raw, pool, /*paused=*/false, 100, 100, 0.0f, kPipeline, heard);
    const std::vector<Touch> v = pool.frame(frame_time);
    REQUIRE(v.size() == 2);
    CHECK(v[0].t == float(frame_time));          // at the vsync: the frame's time
    CHECK(v[1].t == float(frame_time - 0.005));  // 5 ms before it: 5 ms earlier
    // The input offset still comes off.
    raw = {RawTouch{kVsync, 3, TouchEvent::Down, 50, 50}};
    TouchPool offset;
    drain_touches(raw, offset, /*paused=*/false, 100, 100, 0.01f, kPipeline, heard);
    const std::vector<Touch> w = offset.frame(frame_time);
    REQUIRE(w.size() == 1);
    CHECK(w[0].t == float(frame_time - double(0.01f)));
}
