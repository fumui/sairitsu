// FrameClock (time/frame_clock.h): Runtime Update values from the music time at each frame's present time.
#include <random>
#include <vector>

#include "doctest.h"
#include "time/frame_clock.h"
#include "time/time_tables.h"

using sr::time::FrameClock;
using sr::time::FrameTime;
using sr::time::TimeTables;

TEST_SUITE("time") {

TEST_CASE("frame clock runs through the two-second pre-roll at negative times") {
    // At 120 Hz the first frame has delta 1/120.
    const TimeTables tables;
    FrameClock clock(tables);
    const FrameTime first = clock.next(-0.9924, 1.0f / 120);
    CHECK(first.time == -0.9924f);
    CHECK(first.delta == 1.0f / 120);
    CHECK(first.scaled == -0.9924f);
    const FrameTime second = clock.next(-0.9924 + 1.0 / 120, 1.0f / 120);
    CHECK(second.delta == second.time - first.time);
    FrameClock early(tables);
    CHECK(early.next(-2.5).time == -2.0f);  // never before the pre-roll's start
}

TEST_CASE("frame clock loops with a skip to the pre-roll") {
    const TimeTables tables;
    FrameClock clock(tables);
    clock.next(-2.0, 1.0f / 120);
    clock.next(111.44);
    const FrameTime skip = clock.skip_to(-2.0f);
    CHECK(skip.time == -2.0f);
    CHECK(skip.delta == 0.0f);
    CHECK(skip.skip);
    CHECK(skip.scaled == tables.time_to_scaled_time(-2.0f));
    // Time carries on from -2 with no backward-time hold at the old end.
    const FrameTime after = clock.next(-2.0 + 1.0 / 120);
    CHECK(after.time == -2.0f + float(1.0 / 120));
    CHECK(after.delta == after.time - skip.time);
    CHECK_FALSE(after.skip);
}

TEST_CASE("frame clock never goes backwards") {
    const TimeTables tables;  // no changes: scaled time is time
    FrameClock clock(tables, 0.0f);
    // Before the start (pre-roll), a backwards step, a repeat, a long hitch.
    const std::vector<double> music = {-0.4, -0.01, 0.1, 0.2, 0.15, 0.15, 0.3, 0.55, 0.5};
    const std::vector<float> want = {0.0f, 0.0f, 0.1f, 0.2f, 0.2f, 0.2f, 0.3f, 0.55f, 0.55f};
    for (size_t i = 0; i < music.size(); ++i) {
        const FrameTime f = clock.next(music[i]);
        CHECK(f.time == want[i]);
        CHECK(f.delta >= 0.0f);
        CHECK_FALSE(f.skip);
    }
}

TEST_CASE("frame clock delta matches time differences") {
    const TimeTables tables;
    FrameClock clock(tables, 0.0f);
    std::mt19937 rng(26);
    std::uniform_real_distribution<double> step(-0.004, 0.034);  // jittered vsyncs, some backwards
    double t = 0;
    float previous = 0;
    for (int i = 0; i < 2000; ++i) {
        t += step(rng);
        const FrameTime f = clock.next(t);
        if (i == 0) {
            CHECK(f.delta == 0.0f);
        } else {
            CHECK(f.delta == f.time - previous);
        }
        CHECK(f.scaled == tables.time_to_scaled_time(f.time));
        previous = f.time;
    }
}

}  // TEST_SUITE
