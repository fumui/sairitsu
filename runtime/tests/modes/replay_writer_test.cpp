// Replay writing (modes/replay_writer.h): the format parse_replay reads.
#include <cstdint>
#include <span>
#include <string>
#include <vector>

#include "data/gzip.h"
#include "doctest.h"
#include "modes/replay.h"
#include "modes/replay_writer.h"
#include "modes/touch.h"
#include "srun/play.h"

using namespace sr;

namespace {

modes::Touch touch_at(float id, float x, float y) {
    modes::Touch t{};
    t.id = id;
    t.x = x;
    t.y = y;
    return t;
}

void check_point(const modes::RecordedPoint& p, int32_t t_ms, int32_t x, int32_t y) {
    CHECK(p.t_ms == t_ms);
    CHECK(p.x == x);
    CHECK(p.y == y);
}

}  // namespace

TEST_CASE("gzip round-trips through gunzip") {
    std::vector<uint8_t> random(100 * 1024);
    uint32_t state = 0x12345678;
    for (uint8_t& b : random) {
        state = state * 1664525u + 1013904223u;
        b = uint8_t(state >> 24);
    }
    for (const std::vector<uint8_t>& bytes : {std::vector<uint8_t>{}, std::vector<uint8_t>{0x2a}, random}) {
        const std::vector<uint8_t> gz = data::gzip(bytes);
        REQUIRE(gz.size() >= 18);
        CHECK(gz[0] == 0x1f);
        CHECK(gz[1] == 0x8b);
        CHECK(data::gunzip(gz) == bytes);
    }
}

TEST_CASE("recorder truncates and centres points") {
    // T = trunc((Time - inputOffset) x 1000), x and y = trunc(px from the centre); screen units are px / (H/2).
    modes::TouchRecorder rec(3200, 2136, 0.01f);
    const std::vector<modes::Touch> touches{touch_at(101, 0.5f, -0.25f)};
    rec.frame(1.2345, touches);
    REQUIRE(rec.touches().size() == 1);
    REQUIRE(rec.touches()[0].points.size() == 1);
    check_point(rec.touches()[0].points[0], 1224, 534, -267);
}

TEST_CASE("recorder gives back the whole px a touch's screen units came from") {
    // Units are float(px / (H/2)); a plain trunc(units x H/2) loses 1 px on some of them (333 px, for one).
    modes::TouchRecorder rec(3200, 2136, 0);
    const double half_h = 1068;
    CHECK(int32_t(float(333 / half_h) * float(half_h)) == 332);
    for (int32_t px = -1604; px <= 1604; ++px) {
        rec.clear();
        const float units = float(double(px) / half_h);
        rec.frame(0, std::vector<modes::Touch>{touch_at(101, units, units)});
        REQUIRE(rec.touches().size() == 1);
        CHECK_MESSAGE(rec.touches()[0].points[0].x == px, "px ", px);
        CHECK_MESSAGE(rec.touches()[0].points[0].y == px, "px ", px);
    }
    // A sub-pixel position still truncates toward zero.
    rec.clear();
    rec.frame(0, std::vector<modes::Touch>{touch_at(101, float(333.9 / half_h), float(-333.9 / half_h))});
    check_point(rec.touches()[0].points[0], 0, 333, -333);
}

TEST_CASE("recorder replaces a point equal to the last two") {
    modes::TouchRecorder rec(3200, 2136, 0);
    const std::vector<modes::Touch> here{touch_at(101, 0.5f, 0.5f)};
    rec.frame(1, here);
    rec.frame(2, here);
    rec.frame(3, here);
    REQUIRE(rec.touches().size() == 1);
    const std::vector<modes::RecordedPoint>& points = rec.touches()[0].points;
    REQUIRE(points.size() == 2);
    check_point(points[0], 1000, 534, 534);
    check_point(points[1], 3000, 534, 534);

    rec.frame(4, std::vector<modes::Touch>{touch_at(101, -0.5f, 0.5f)});
    REQUIRE(points.size() == 3);
    check_point(points[2], 4000, -534, 534);
}

TEST_CASE("recorder keeps touches in first-seen order") {
    modes::TouchRecorder rec(3200, 2136, 0);
    rec.frame(1, std::vector<modes::Touch>{touch_at(105, 0, 0)});
    rec.frame(2, std::vector<modes::Touch>{touch_at(102, 1, 1), touch_at(105, 0, 0.5f)});
    REQUIRE(rec.touches().size() == 2);
    CHECK(rec.touches()[0].points.size() == 2);  // 105
    REQUIRE(rec.touches()[1].points.size() == 1);  // 102
    check_point(rec.touches()[1].points[0], 2000, 1068, 1068);

    rec.clear();
    CHECK(rec.touches().empty());
}

TEST_CASE("encoded replay parses back") {
    modes::ReplayData d;
    d.duration = 111.439247;
    d.input_offset = 0.025f;
    d.width = 3200;
    d.height = 2136;
    d.result = {modes::Grade::Pass, 964040, 983430, 447, 647, 38, 0, 2, 687};
    d.inputs = {{5, 0, 1.5f, 1, 0.0123f}, {2, 0, 1.5f, 2, -0.0456f}, {9, 0, 2.25f, 0, 0.1f}};
    d.entities = {{{"time", 1.5f}}, {{"end_time", -2.25f}}, {{"replayRevision", 1}}};
    // The second stream's 1e8 + 1.8472 delta rounds, so it reads back as delta_round_trip gives it.
    d.streams = {{3, {-0.5f, 1, 3}, {0, 1, 0}}, {2000018, {-0.461f, 1.8472061f}, {-1e8f, 1.8472061f}}};
    d.touches = {{{{-461, 100, -200}, {-400, 105, -190}}},
                 {{{1000, -1604, 206}, {1008, -1600, 200}, {1016, -1600, 200}}}};

    const modes::Replay r = modes::parse_replay(modes::encode_replay(d));
    CHECK(r.duration == d.duration);
    CHECK(r.input_offset == d.input_offset);
    CHECK(r.width == 3200);
    CHECK(r.height == 2136);

    REQUIRE(r.inputs.size() == d.inputs.size());
    for (size_t i = 0; i < d.inputs.size(); ++i) {
        CHECK(r.inputs[i].entity == d.inputs[i].entity);
        CHECK(r.inputs[i].time == d.inputs[i].time);
        CHECK(r.inputs[i].judgment == d.inputs[i].judgment);
        CHECK(r.inputs[i].accuracy == d.inputs[i].accuracy);
    }

    REQUIRE(r.touches.size() == d.touches.size());
    const double half_h = 2136 / 2.0;
    for (size_t i = 0; i < d.touches.size(); ++i) {
        REQUIRE(r.touches[i].points.size() == d.touches[i].points.size());
        for (size_t k = 0; k < d.touches[i].points.size(); ++k) {
            const modes::RecordedPoint& p = d.touches[i].points[k];
            CHECK(r.touches[i].points[k].t == double(p.t_ms) / 1000.0);
            CHECK(r.touches[i].points[k].x == float(double(p.x) / half_h));
            CHECK(r.touches[i].points[k].y == float(double(p.y) / half_h));
        }
    }

    REQUIRE(r.streams.size() == d.streams.size());
    for (size_t i = 0; i < d.streams.size(); ++i) {
        CHECK(r.streams[i].id == d.streams[i].id);
        CHECK(r.streams[i].keys == tools::delta_round_trip(d.streams[i].keys));
        CHECK(r.streams[i].values == tools::delta_round_trip(d.streams[i].values));
    }
    CHECK(r.streams[1].values[1] == 0.0f);

    CHECK(r.exports == d.entities);

    CHECK(r.result.grade == "pass");
    CHECK(r.result.arcade == 964040);
    CHECK(r.result.accuracy == 983430);
    CHECK(r.result.combo == 447);
    CHECK(r.result.perfect == 647);
    CHECK(r.result.great == 38);
    CHECK(r.result.good == 0);
    CHECK(r.result.miss == 2);
    CHECK(r.result.total == 687);
}

TEST_CASE("encoded config parses back") {
    const std::vector<float> options{1, 9.8f, 0};
    CHECK(modes::parse_replay_config(modes::encode_replay_config(options)) == options);
}
