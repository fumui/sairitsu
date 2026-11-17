// The replay feed (tools/srun/play.h): stream values through float deltas, and the frame clock recovered from a
// replay fixture.
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <memory>
#include <map>
#include <optional>
#include <set>
#include <span>
#include <string>
#include <vector>

#include "data/resources.h"
#include "doctest.h"
#include "modes/play_session.h"
#include "modes/replay.h"
#include "modes/replay_writer.h"
#include "modes/watch_session.h"
#include "srun/play.h"
#include "srun/watch.h"
#include "time/frame_clock.h"
#include "support/engine_fixture.h"

using namespace sr;

namespace {

std::vector<uint8_t> replay_fixture(const char* name) {
    return test::read_fixture_file(std::filesystem::path(SR_TEST_FIXTURES) / "replay" / name);
}

}  // namespace

TEST_CASE("replay stream values round-trip through float deltas") {
    // A replay holds each value's float difference from the one before; parse_replay sums them again.
    CHECK(tools::delta_round_trip({0.5f, 1.5f, -3.5f}) == std::vector<float>{0.5f, 1.5f, -3.5f});
    // -1e8 then 1.8472: the delta 1e8 + 1.8472 rounds to 1e8, so the value comes back as 0.
    CHECK(tools::delta_round_trip({-1e8f, 1.8472061f}) == std::vector<float>{-1e8f, 0.0f});
    CHECK(tools::within_ulp(1.0f, std::nextafter(1.0f, 2.0f)));
    CHECK_FALSE(tools::within_ulp(1.0f, std::nextafter(std::nextafter(1.0f, 2.0f), 2.0f)));
}

TEST_CASE("replay frame clock reproduces every input time") {
    const modes::Replay replay = modes::parse_replay(replay_fixture("benchmark-replaydata"));
    const tools::ReplayFeed feed = tools::replay_feed(replay);
    std::vector<float> times;
    for (const modes::ReplayInput& in : replay.inputs) times.push_back(in.time);
    std::sort(times.begin(), times.end());
    times.erase(std::unique(times.begin(), times.end()), times.end());
    CHECK(times.size() == 643);
    // Every input time is f32(f32(start + t0 + k period) - start) on the fitted grid, and every point's millisecond
    // names one grid frame.
    CHECK(feed.reproduced == 643);
    CHECK(feed.unmatched_points == 0);
    CHECK(1.0 / feed.period == doctest::Approx(119.9996).epsilon(1e-6));
    for (const float t : times) {
        const int64_t k = std::llround((double(t) - feed.t0) / feed.period);
        CHECK(tools::feed_frame(feed, k) == double(t));
    }
    // Events are in time order.
    for (size_t i = 1; i < feed.events.size(); ++i) CHECK(feed.events[i].t >= feed.events[i - 1].t);
}

