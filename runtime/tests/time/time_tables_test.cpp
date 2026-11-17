// Unit tests for TimeTables plus the golden vectors in fixtures/golden/time (golden.reference_time,
// tools/sairitsu_tools/golden.py). A time fixture case is
//   {"bpm": [[beat, bpm]...], "timescale": [[beat, scale]...], "args": [x], "result": r, "tol_ulp": n}
// where either list may be absent (no changes).
#include <algorithm>
#include <bit>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <functional>
#include <limits>
#include <map>
#include <sstream>
#include <string>
#include <vector>

#include "data/load_error.h"
#include "doctest.h"
#include "time/time_tables.h"
#include "yyjson.h"

#ifndef SR_TEST_FIXTURES
#error "SR_TEST_FIXTURES must name runtime/tests/fixtures"
#endif

using sr::data::EngineModeData;
using sr::data::EntityDatum;
using sr::data::LevelData;
using sr::data::LevelEntity;
using sr::time::TimeTables;

namespace {

using Changes = std::vector<std::pair<float, float>>;

LevelEntity change(const char* archetype, const char* second, float beat, float value) {
    LevelEntity e;
    e.archetype = archetype;
    EntityDatum b;
    b.name = "#BEAT";
    b.value = beat;
    EntityDatum v;
    v.name = second;
    v.value = value;
    e.data = {b, v};
    return e;
}

TimeTables tables(const Changes& bpm, const Changes& scale = {}) {
    LevelData level;
    for (const auto& [beat, v] : bpm) level.entities.push_back(change("#BPM_CHANGE", "#BPM", beat, v));
    for (const auto& [beat, v] : scale) level.entities.push_back(change("#TIMESCALE_CHANGE", "#TIMESCALE", beat, v));
    return TimeTables::build(level, EngineModeData{});
}

float json_float(yyjson_val* v) {
    if (yyjson_is_str(v)) {
        const std::string s = yyjson_get_str(v);
        if (s == "nan") return std::numeric_limits<float>::quiet_NaN();
        if (s == "inf") return std::numeric_limits<float>::infinity();
        if (s == "-inf") return -std::numeric_limits<float>::infinity();
        FAIL("bad number token " << s);
    }
    REQUIRE(yyjson_is_num(v));
    return float(yyjson_get_num(v));
}

Changes json_changes(yyjson_val* arr) {
    Changes out;
    if (arr == nullptr) return out;
    size_t i, n;
    yyjson_val* p;
    yyjson_arr_foreach(arr, i, n, p) out.emplace_back(json_float(yyjson_arr_get(p, 0)), json_float(yyjson_arr_get(p, 1)));
    return out;
}

bool same(float got, float want) {
    if (std::isnan(got) || std::isnan(want)) return std::isnan(got) && std::isnan(want);
    return std::bit_cast<uint32_t>(got) == std::bit_cast<uint32_t>(want);  // zero signs count
}

}  // namespace

TEST_SUITE("time") {

TEST_CASE("single bpm") {
    const TimeTables t = tables({{0, 130}});
    CHECK(t.beat_to_time(13) == 6.0f);  // 13 * 60 / 130 is exact
    CHECK(t.beat_to_bpm(13) == 130.0f);
    CHECK(t.beat_to_starting_beat(13) == 0.0f);
    CHECK(t.beat_to_starting_time(13) == 0.0f);
}

TEST_CASE("without any change: no BPM section, scaled time is time") {
    const TimeTables t = tables({});
    CHECK(t.beat_to_time(3) == std::numeric_limits<float>::infinity());  // 3 * 60 / 0
    CHECK(t.beat_to_time(-3) == -std::numeric_limits<float>::infinity());
    CHECK(std::isnan(t.beat_to_time(0)));
    CHECK(t.beat_to_bpm(3) == 0.0f);
    CHECK(t.beat_to_starting_beat(3) == 0.0f);
    CHECK(t.beat_to_starting_time(3) == 0.0f);
    CHECK(t.time_to_scaled_time(5) == 5.0f);
    CHECK(t.time_to_time_scale(5) == 1.0f);
    CHECK(t.time_to_starting_time(5) == 0.0f);
    CHECK(t.time_to_starting_scaled_time(5) == 0.0f);
}

TEST_CASE("bpm change mid level") {
    const TimeTables t = tables({{0, 120}, {8, 60}, {12, 240}});
    CHECK(t.beat_to_time(4) == 2.0f);
    CHECK(t.beat_to_time(8) == 4.0f);      // a change belongs to the section it starts
    CHECK(t.beat_to_time(10) == 6.0f);
    CHECK(t.beat_to_time(12) == 8.0f);
    CHECK(t.beat_to_time(14) == 8.5f);
    CHECK(t.beat_to_bpm(7.99f) == 120.0f);
    CHECK(t.beat_to_bpm(8) == 60.0f);
    CHECK(t.beat_to_bpm(100) == 240.0f);
    CHECK(t.beat_to_starting_beat(11) == 8.0f);
    CHECK(t.beat_to_starting_time(11) == 4.0f);
    CHECK(t.beat_to_starting_beat(12) == 12.0f);
    CHECK(t.beat_to_starting_time(12) == 8.0f);
}

TEST_CASE("changes may come in any entity order") {
    const TimeTables t = tables({{12, 240}, {0, 120}, {8, 60}});
    CHECK(t.beat_to_time(14) == 8.5f);
}

TEST_CASE("before the first change there is no section") {
    const TimeTables t = tables({{0, 120}, {8, 60}});
    CHECK(t.beat_to_time(-4) == -std::numeric_limits<float>::infinity());  // -4 * 60 / 0
    CHECK(t.beat_to_bpm(-4) == 0.0f);
    CHECK(t.beat_to_starting_beat(-4) == 0.0f);
    CHECK(t.beat_to_starting_time(-4) == 0.0f);
    // A first change that is not at beat 0 anchors time 0 at its own beat.
    const TimeTables u = tables({{4, 120}});
    CHECK(u.beat_to_time(4) == 0.0f);
    CHECK(u.beat_to_time(5) == 0.5f);
    CHECK(std::isnan(u.beat_to_time(0)));  // (0 - 0) * 60 / 0
    CHECK(u.beat_to_time(1) == std::numeric_limits<float>::infinity());
    CHECK(u.beat_to_starting_beat(0) == 0.0f);
    CHECK(u.beat_to_starting_beat(4) == 4.0f);
}

TEST_CASE("pinned bits: double section times, one rounding at the end") {
    // BPM changes 1.3 @ 130, 7.7 @ 175, 13.9 @ 133.33, 22.45 @ 97.5; timescale 2.2 x1.25, 9.35 x0.7, 17.6 x1.9.
    const TimeTables t = tables({{1.3f, 130}, {7.7f, 175}, {13.9f, 133.33f}, {22.45f, 97.5f}},
                                {{2.2f, 1.25f}, {9.35f, 0.7f}, {17.6f, 1.9f}});
    auto f = [](uint32_t b) { return std::bit_cast<float>(b); };
    const std::pair<uint32_t, uint32_t> beat_to_time[] = {
        {0x3fa66666u, 0x00000000u}, {0x40000000u, 0x3ea56a57u}, {0x40b1999au, 0x3ffb13b1u},
        {0x40f6b852u, 0x403d43fdu}, {0x4131c28fu, 0x4083ef86u}, {0x415f3333u, 0x40a34415u},
        {0x4199851fu, 0x40eeb94eu}, {0x41ce0000u, 0x412f53aau}, {0x42c8051fu, 0x4262a024u},
        {0x4580059au, 0x451d4287u},
    };
    for (const auto& [x, want] : beat_to_time) {
        CAPTURE(x);
        CHECK(std::bit_cast<uint32_t>(t.beat_to_time(f(x))) == want);
    }
    const std::pair<uint32_t, uint32_t> starting_time[] = {
        {0x40f6b852u, 0x403d0bd0u}, {0x415f3333u, 0x40a28bc2u}, {0x41ce0000u, 0x410ed5a2u}};
    for (const auto& [x, want] : starting_time) {
        CAPTURE(x);
        CHECK(std::bit_cast<uint32_t>(t.beat_to_starting_time(f(x))) == want);
    }
    const std::pair<uint32_t, uint32_t> scaled[] = {
        {0x3ed70a3du, 0x3ed7a179u}, {0x3ff33333u, 0x40115a95u}, {0x4061eb85u, 0x4089b175u},
        {0x40a33333u, 0x40acdc77u}, {0x40f4cccdu, 0x41045fceu}, {0x41a8cccdu, 0x42075045u},
        {0x4395f333u, 0x440ce319u},
    };
    for (const auto& [x, want] : scaled) {
        CAPTURE(x);
        CHECK(std::bit_cast<uint32_t>(t.time_to_scaled_time(f(x))) == want);
    }
    CHECK(t.time_to_scaled_time(0.4f) == 0.4f);  // before the first timescale change: t itself
}

TEST_CASE("timescale integrates") {
    // 120 BPM: beat 4 = 2 s, beat 8 = 4 s. Scale 1 until 2 s, then 2, then 0.5 from 4 s.
    const TimeTables t = tables({{0, 120}}, {{0, 1}, {4, 2}, {8, 0.5f}});
    CHECK(t.time_to_scaled_time(1) == 1.0f);
    CHECK(t.time_to_scaled_time(2) == 2.0f);
    CHECK(t.time_to_scaled_time(3) == 4.0f);
    CHECK(t.time_to_scaled_time(4) == 6.0f);
    CHECK(t.time_to_scaled_time(6) == 7.0f);
    CHECK(t.time_to_time_scale(3) == 2.0f);
    CHECK(t.time_to_time_scale(4) == 0.5f);
    CHECK(t.time_to_starting_time(3) == 2.0f);
    CHECK(t.time_to_starting_scaled_time(3) == 2.0f);
    CHECK(t.time_to_starting_time(5) == 4.0f);
    CHECK(t.time_to_starting_scaled_time(5) == 6.0f);
}

TEST_CASE("before its first change scaled time is time") {
    const TimeTables t = tables({{0, 120}}, {{0, 3}, {4, 1}});
    CHECK(t.time_to_scaled_time(-1) == -1.0f);
    CHECK(t.time_to_time_scale(-1) == 1.0f);
    CHECK(t.time_to_starting_time(-1) == 0.0f);
    CHECK(t.time_to_starting_scaled_time(-1) == 0.0f);
}

TEST_CASE("timescale changes are placed through the bpm table") {
    const TimeTables t = tables({{0, 60}, {4, 120}}, {{0, 1}, {6, 2}});  // beat 6 = 4 + 1 = 5 s
    CHECK(t.time_to_starting_time(5) == 5.0f);
    CHECK(t.time_to_scaled_time(7) == 9.0f);
}

TEST_CASE("nan and infinity pick sections like a linear scan") {
    const TimeTables t = tables({{0, 120}, {8, 60}}, {{0, 1}, {4, 2}});
    const float nan = std::numeric_limits<float>::quiet_NaN();
    const float inf = std::numeric_limits<float>::infinity();
    CHECK(t.beat_to_bpm(nan) == 60.0f);
    CHECK(t.beat_to_bpm(inf) == 60.0f);
    CHECK(t.beat_to_bpm(-inf) == 0.0f);  // before the first change
    CHECK(std::isnan(t.beat_to_time(nan)));
    CHECK(t.time_to_time_scale(nan) == 2.0f);
}

TEST_CASE("build reads the import default when the entity omits a datum, and ignores other archetypes") {
    EngineModeData engine;
    sr::data::Archetype a;
    a.name = "#BPM_CHANGE";
    sr::data::Import im;
    im.name = "#BPM";
    im.def = 120.0f;
    a.imports.push_back(im);
    engine.archetypes.push_back(a);

    LevelData level;
    LevelEntity other;
    other.archetype = "Note";
    level.entities.push_back(other);
    LevelEntity e;
    e.archetype = "#BPM_CHANGE";
    EntityDatum b;
    b.name = "#BEAT";
    b.value = 0;
    e.data = {b};
    level.entities.push_back(e);
    CHECK(TimeTables::build(level, engine).beat_to_time(4) == 2.0f);

    CHECK_THROWS_AS(TimeTables::build(level, EngineModeData{}), sr::data::LoadError);
}

TEST_CASE("pinned bits at 130 BPM") {
    // Non-dyadic: float(double(beat - 0) * 60 / 130), in this order.
    // The float32 order this replaced gave 0x4239e85f at 100.7.
    const TimeTables t = tables({{0, 130}});
    const std::pair<float, uint32_t> pins[] = {{1.0f, 0x3eec4ec5u}, {3.0f, 0x3fb13b14u}, {7.5f, 0x405d89d9u},
                                               {13.3f, 0x40c46e47u}, {100.7f, 0x4239e85eu}, {255.0f, 0x42eb6276u}};
    for (const auto& [beat, bits] : pins) CHECK(std::bit_cast<uint32_t>(t.beat_to_time(beat)) == bits);
}

TEST_CASE("unsorted and duplicate changes") {
    const TimeTables u = tables({{0, 120}}, {{8, 0.5f}, {0, 1}, {4, 2}});
    CHECK(u.time_to_scaled_time(6) == 7.0f);
    // Duplicate beats keep entity order; the later one governs from that beat on.
    const TimeTables d = tables({{0, 120}, {4, 60}, {4, 240}});
    CHECK(d.beat_to_bpm(4) == 240.0f);
    CHECK(d.beat_to_time(8) == 3.0f);  // 2 s to beat 4, 0 s at 60 BPM, then 4 beats at 240
    const TimeTables s = tables({{0, 120}}, {{0, 1}, {4, 2}, {4, 3}});
    CHECK(s.time_to_time_scale(2) == 3.0f);
    CHECK(s.time_to_scaled_time(3) == 5.0f);
}

TEST_CASE("zero bpm and zero timescale do not crash") {
    const TimeTables z = tables({{0, 120}, {4, 0}}, {{0, 1}, {2, 0}});
    CHECK(z.beat_to_bpm(5) == 0.0f);
    (void)z.beat_to_time(5);  // division by zero: inf, no fault
    (void)z.beat_to_time(100);
    const TimeTables s = tables({{0, 60}}, {{0, 0}, {2, 1}});
    CHECK(s.time_to_scaled_time(1) == 0.0f);
    CHECK(s.time_to_scaled_time(3) == 1.0f);
}

TEST_CASE("timescale first change not at 0, and timescale without bpm") {
    // Scaled time starts at the first change's own time and runs on from there.
    const TimeTables t = tables({{0, 60}}, {{3, 2}, {5, 1}});
    CHECK(t.time_to_scaled_time(3) == 3.0f);
    CHECK(t.time_to_scaled_time(4) == 5.0f);
    CHECK(t.time_to_scaled_time(1) == 1.0f);  // before the first change: time itself
    CHECK(t.time_to_scaled_time(6) == 8.0f);
    CHECK(t.time_to_starting_scaled_time(4) == 3.0f);
    // Without BPM changes the change times are not finite (beat 0 is NaN, beat 2 infinite).
    const TimeTables n = tables({}, {{0, 1}, {2, 3}});
    CHECK(std::isnan(n.time_to_scaled_time(3)));
    CHECK(n.beat_to_time(2) == std::numeric_limits<float>::infinity());
}

TEST_CASE("a ref where a number is needed is a load error") {
    LevelData level;
    LevelEntity e = change("#BPM_CHANGE", "#BPM", 0, 120);
    e.data[1].is_ref = true;
    e.data[1].ref = 0;
    level.entities.push_back(e);
    CHECK_THROWS_AS(TimeTables::build(level, EngineModeData{}), sr::data::LoadError);
}

TEST_CASE("golden time vectors match") {
    const std::filesystem::path dir = std::filesystem::path(SR_TEST_FIXTURES) / "golden" / "time";
    std::vector<std::filesystem::path> files;
    if (std::filesystem::exists(dir))
        for (const auto& e : std::filesystem::directory_iterator(dir))
            if (e.path().extension() == ".json") files.push_back(e.path());
    std::sort(files.begin(), files.end());
    REQUIRE_MESSAGE(files.size() == 8, "expected 8 time fixtures in " << dir.string()
                                          << " (python -m sairitsu_tools golden --out runtime/tests/fixtures/golden)");

    using Fn = std::function<float(const TimeTables&, float)>;
    const std::map<std::string, Fn> funcs = {
        {"BeatToTime", [](const TimeTables& t, float x) { return t.beat_to_time(x); }},
        {"BeatToBPM", [](const TimeTables& t, float x) { return t.beat_to_bpm(x); }},
        {"BeatToStartingBeat", [](const TimeTables& t, float x) { return t.beat_to_starting_beat(x); }},
        {"BeatToStartingTime", [](const TimeTables& t, float x) { return t.beat_to_starting_time(x); }},
        {"TimeToScaledTime", [](const TimeTables& t, float x) { return t.time_to_scaled_time(x); }},
        {"TimeToStartingScaledTime", [](const TimeTables& t, float x) { return t.time_to_starting_scaled_time(x); }},
        {"TimeToStartingTime", [](const TimeTables& t, float x) { return t.time_to_starting_time(x); }},
        {"TimeToTimeScale", [](const TimeTables& t, float x) { return t.time_to_time_scale(x); }},
    };

    size_t total = 0, mismatches = 0;
    for (const auto& path : files) {
        std::ifstream in(path, std::ios::binary);
        std::ostringstream ss;
        ss << in.rdbuf();
        const std::string text = ss.str();
        yyjson_doc* doc = yyjson_read(text.data(), text.size(), 0);
        REQUIRE_MESSAGE(doc != nullptr, "unreadable " << path.string());
        yyjson_val* root = yyjson_doc_get_root(doc);
        const std::string name = yyjson_get_str(yyjson_obj_get(root, "func"));
        const auto fn = funcs.find(name);
        REQUIRE_MESSAGE(fn != funcs.end(), "unknown time function " << name);
        yyjson_val* cases = yyjson_obj_get(root, "cases");
        REQUIRE(yyjson_arr_size(cases) > 0);
        size_t i, n;
        yyjson_val* c;
        yyjson_arr_foreach(cases, i, n, c) {
            const TimeTables t = tables(json_changes(yyjson_obj_get(c, "bpm")), json_changes(yyjson_obj_get(c, "timescale")));
            const float x = json_float(yyjson_arr_get(yyjson_obj_get(c, "args"), 0));
            const float want = json_float(yyjson_obj_get(c, "result"));
            const float got = fn->second(t, x);
            ++total;
            if (!same(got, want)) {
                ++mismatches;
                if (mismatches <= 5) {
                    char* json = yyjson_val_write(c, 0, nullptr);
                    FAIL_CHECK(name << " case " << i << ": got " << got << ", sonolus.py " << want << ", json " << json);
                    std::free(json);
                }
            }
        }
        yyjson_doc_free(doc);
    }
    CHECK(total > 0);
    CHECK(mismatches == 0);
}

}  // TEST_SUITE
