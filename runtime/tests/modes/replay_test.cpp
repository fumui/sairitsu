// Replay decoding (modes/replay.h).
#include <algorithm>
#include <cstdint>
#include <filesystem>
#include <set>
#include <string>
#include <vector>

#include "data/load_error.h"
#include "doctest.h"
#include "modes/replay.h"
#include "support/archive.h"
#include "support/engine_fixture.h"

using namespace sr;

namespace {

std::vector<uint8_t> replay_fixture(const char* name) {
    return test::read_fixture_file(std::filesystem::path(SR_TEST_FIXTURES) / "replay" / name);
}

// A minimal gzipped replay: two inputs with these entityIndex deltas, a 100 px high play area `width` wide.
std::vector<uint8_t> small_replay(const std::string& entity_deltas, int width = 160) {
    return test::make_gzip(R"({"duration":1,"playArea":{"width":)" + std::to_string(width) +
                           R"(,"height":100},"result":{"grade":"pass","arcadeScore":0,"accuracyScore":0,"combo":0,)"
                           R"("perfect":0,"great":0,"good":0,"miss":0,"totalCount":0},)"
                           R"("inputs":{"entityIndex":[)" + entity_deltas +
                           R"(],"time":[0,0],"judgment":[0,0],"accuracy":[0,0]},)"
                           R"("touches":{"l":[],"t":[],"x":[],"y":[]},"entities":[],"streams":[]})");
}

}  // namespace

TEST_CASE("replay decodes the benchmark counts") {
    const modes::Replay r = modes::parse_replay(replay_fixture("benchmark-replaydata"));
    CHECK(r.touches.size() == 525);
    size_t points = 0;
    for (const auto& t : r.touches) points += t.points.size();
    CHECK(points == 5176);

    CHECK(r.inputs.size() == 687);
    int count[4] = {0, 0, 0, 0};
    for (const auto& in : r.inputs) {
        const int j = int(in.judgment);
        REQUIRE(j >= 0);
        REQUIRE(j <= 3);
        ++count[j];
    }
    CHECK(count[1] == 647);
    CHECK(count[2] == 38);
    CHECK(count[3] == 0);
    CHECK(count[0] == 2);

    CHECK(r.result.grade == "pass");
    CHECK(r.result.arcade == 964040);
    CHECK(r.result.accuracy == 983430);
    CHECK(r.result.combo == 447);
    CHECK(r.result.perfect == 647);
    CHECK(r.result.great == 38);
    CHECK(r.result.good == 0);
    CHECK(r.result.miss == 2);
    CHECK(r.result.total == 687);

    CHECK(r.width == 3200);
    CHECK(r.height == 2136);
    CHECK(r.exports.size() == 1080);
    REQUIRE(!r.exports[0].empty());
    CHECK(r.exports[0][0].first == "replayRevision");
    CHECK(r.streams.size() == 280);
    for (const auto& s : r.streams) CHECK(s.keys.size() == s.values.size());
}

TEST_CASE("replay stream keys and values are running sums") {
    const modes::Replay r = modes::parse_replay(replay_fixture("benchmark-replaydata"));
    float last = 0;
    for (const auto& s : r.streams) {
        REQUIRE(!s.keys.empty());
        for (size_t i = 1; i < s.keys.size(); ++i) CHECK(s.keys[i] >= s.keys[i - 1]);
        last = std::max(last, s.keys.back());
    }
    // The first key of stream 1 is the first delta (-0.461128235); the latest key is near the last touch (107.1 s).
    CHECK(r.streams[0].id == 1);
    CHECK(r.streams[0].keys[0] == doctest::Approx(-0.461128235f));
    CHECK(r.streams[0].keys[1] == doctest::Approx(-0.461128235f + 0.0999984741f));
    CHECK(last == doctest::Approx(107.1059f).epsilon(1e-5));
    // Values are summed too (Play writes them as Play's own stream values, delta-encoded): stream 2's raw
    // 0.5, 1, -5, 6 are 0.5, 1.5, -3.5, 2.5.
    CHECK(r.streams[1].values[0] == 0.5f);
    CHECK(r.streams[1].values[1] == 1.5f);
    CHECK(r.streams[1].values[2] == -3.5f);
    CHECK(r.streams[1].values[3] == 2.5f);
}

TEST_CASE("replay points lie in the play area") {
    const modes::Replay r = modes::parse_replay(replay_fixture("benchmark-replaydata"));
    // 2 of 5,176 points lie up to 4 px outside the play area.
    const float half_h = float(r.height) / 2;
    const float max_x = (float(r.width) / 2 + 4) / half_h;
    const float max_y = (half_h + 4) / half_h;
    for (const auto& touch : r.touches) {
        double prev = -1e9;
        for (const auto& p : touch.points) {
            CHECK(std::abs(p.x) <= max_x);
            CHECK(std::abs(p.y) <= max_y);
            CHECK(p.t >= -2);
            CHECK(p.t <= r.duration + 1);
            CHECK(p.t >= prev);
            prev = p.t;
        }
    }
}

TEST_CASE("replay input entities are distinct level entities") {
    const modes::Replay r = modes::parse_replay(replay_fixture("benchmark-replaydata"));
    std::set<int32_t> seen;
    for (const auto& in : r.inputs) {
        CHECK(in.entity >= 0);
        CHECK(in.entity < 1080);
        CHECK(seen.insert(in.entity).second);
    }
}

TEST_CASE("replay config gives 58 options") {
    const std::vector<float> o = modes::parse_replay_config(replay_fixture("benchmark-config"));
    REQUIRE(o.size() == 58);
    CHECK(o[1] == 9.8f);
}

TEST_CASE("a truncated replay throws LoadError") {
    std::vector<uint8_t> bytes = replay_fixture("benchmark-replaydata");
    bytes.resize(100);
    CHECK_THROWS_AS(modes::parse_replay(bytes), data::LoadError);
}

TEST_CASE("replay entity indices are running sums") {
    const modes::Replay r = modes::parse_replay(small_replay("5,-2"));
    REQUIRE(r.inputs.size() == 2);
    CHECK(r.inputs[0].entity == 5);
    CHECK(r.inputs[1].entity == 3);
}

TEST_CASE("a replay whose entity index sum leaves int32 throws LoadError") {
    CHECK_THROWS_AS(modes::parse_replay(small_replay("2147483647,1")), data::LoadError);
    CHECK_THROWS_AS(modes::parse_replay(small_replay("-2147483648,-1")), data::LoadError);
}

TEST_CASE("a replay without a positive play-area width throws LoadError") {
    CHECK_NOTHROW(modes::parse_replay(small_replay("0,0", 1)));
    CHECK_THROWS_AS(modes::parse_replay(small_replay("0,0", 0)), data::LoadError);
    CHECK_THROWS_AS(modes::parse_replay(small_replay("0,0", -160)), data::LoadError);
}
