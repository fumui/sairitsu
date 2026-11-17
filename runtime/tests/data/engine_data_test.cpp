#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>

#include "data/engine_data.h"
#include "data/load_error.h"
#include "doctest.h"

using namespace sr::data;

namespace {
std::string read_fixture(const char* name) {
    std::ifstream in(std::string(SR_FIXTURE_DIR) + "/data/" + name, std::ios::binary);
    REQUIRE_MESSAGE(bool(in), "cannot open fixture ", name);
    std::stringstream ss;
    ss << in.rdbuf();
    return ss.str();
}
}  // namespace

TEST_SUITE("data") {
TEST_CASE("node value and function parse") {
    const EngineModeData d = parse_engine_mode_data(read_fixture("engine_watch.json"), Mode::Watch);
    REQUIRE(d.nodes.size() == 4);
    CHECK(d.nodes[0].is_value);
    CHECK(d.nodes[0].value == 1.5f);
    CHECK_FALSE(d.nodes[2].is_value);
    CHECK(d.nodes[2].func == "Add");
    CHECK(d.nodes[2].args == std::vector<int32_t>{0, 1});
    CHECK(d.update_spawn == 3);
    CHECK(d.render_mode == "lightweight");
    REQUIRE(d.archetypes.size() == 1);
    const Archetype& a = d.archetypes[0];
    CHECK(a.has_input);
    CHECK(a.callbacks.at(CallbackKind::Preprocess).order == 3.0f);
    CHECK(a.callbacks.at(CallbackKind::SpawnTime).node == 3);
    CHECK(a.callbacks.count(CallbackKind::Initialize) == 0);
    REQUIRE(a.imports.size() == 2);
    CHECK_FALSE(a.imports[0].def.has_value());
    CHECK(a.imports[1].index == 1);
    REQUIRE(d.buckets.size() == 2);
    CHECK(d.buckets[0].sprites[0].fallback_id == 1);
    CHECK(d.buckets[0].unit == "#MILLISECOND_UNIT");
}

TEST_CASE("unknown fields are ignored") {
    // the fixture carries someFutureField and unknownTopLevel
    CHECK_NOTHROW(parse_engine_mode_data(read_fixture("engine_watch.json"), Mode::Watch));
}

TEST_CASE("json number is double then float") {
    const EngineModeData d = parse_engine_mode_data(read_fixture("engine_watch.json"), Mode::Watch);
    // the fixture writes 0.1 for an import default and a value node
    CHECK(d.archetypes[0].imports[1].def.value() == static_cast<float>(0.1));
    CHECK(d.nodes[1].value == static_cast<float>(0.1));
}

TEST_CASE("json number rounds through double, not straight to float") {
    // Just above the midpoint of 1.0f and its successor: as a double it rounds to the
    // midpoint exactly, then ties-to-even gives 1.0f; a direct float parse gives the
    // successor.
    const char* text = "1.0000000596046447753906251";
    const std::string json =
        R"({"skin":{"sprites":[]},"effect":{"clips":[]},"particle":{"effects":[]},"buckets":[],)"
        R"("archetypes":[],"nodes":[{"value":)" + std::string(text) + "}]}";
    const EngineModeData d = parse_engine_mode_data(json, Mode::Watch);
    const float via_double = static_cast<float>(std::strtod(text, nullptr));
    const float direct = std::strtof(text, nullptr);
    REQUIRE(via_double != direct);
    CHECK(d.nodes[0].value == via_double);
    CHECK(d.nodes[0].value == 1.0f);
}

TEST_CASE("non-object json root throws LoadError") {
    CHECK_THROWS_AS(parse_engine_mode_data("[1,2]", Mode::Play), LoadError);
    CHECK_THROWS_AS(parse_engine_mode_data("42", Mode::Watch), LoadError);
    CHECK_THROWS_AS(parse_engine_mode_data("", Mode::Watch), LoadError);
}

TEST_CASE("missing archetypes field names it") {
    try {
        parse_engine_mode_data(R"({"skin":{"sprites":[]},"effect":{"clips":[]},)"
                               R"("particle":{"effects":[]},"buckets":[],"nodes":[]})",
                               Mode::Play);
        FAIL("expected LoadError");
    } catch (const LoadError& e) {
        const std::string what = e.what();
        CHECK(what.find("engine.playData") != std::string::npos);
        CHECK(what.find("archetypes") != std::string::npos);
    }
}

TEST_CASE("bad field type names the path") {
    try {
        parse_engine_mode_data(
            R"({"skin":{"sprites":[{"name":"a","id":"x"}]},"effect":{"clips":[]},)"
            R"("particle":{"effects":[]},"buckets":[],"archetypes":[],"nodes":[]})",
            Mode::Watch);
        FAIL("expected LoadError");
    } catch (const LoadError& e) {
        const std::string what = e.what();
        CHECK(what.find("engine.watchData") != std::string::npos);
        CHECK(what.find("skin.sprites[0].id") != std::string::npos);
    }
}

TEST_CASE("play data accepts exports and touch, watch ignores them") {
    const char* json =
        R"({"skin":{"sprites":[]},"effect":{"clips":[]},"particle":{"effects":[]},"buckets":[],)"
        R"("archetypes":[{"name":"T","hasInput":true,"touch":{"index":0},"imports":[],)"
        R"("exports":["hitTime","x"]}],"nodes":[{"value":0}]})";
    const EngineModeData play = parse_engine_mode_data(json, Mode::Play);
    CHECK(play.archetypes[0].callbacks.count(CallbackKind::Touch) == 1);
    REQUIRE(play.archetypes[0].exports.size() == 2);
    CHECK(play.archetypes[0].exports[1].name == "x");
}

TEST_CASE("entity block lengths are read when given") {
    // Engine data's optional entityMemoryLength / entityDataLength / entitySharedMemoryLength (wiki
    // engine-specs/resources/engine-watch-data); Next SEKAI declares 43 / 38 / 20 and addresses Entity Data
    // Array as ref * 38.
    const std::string head = R"({"skin":{"sprites":[]},"effect":{"clips":[]},"particle":{"effects":[]},"buckets":[],)"
                             R"("archetypes":[],"nodes":[{"value":0}])";
    const EngineModeData given = parse_engine_mode_data(
        head + R"(,"entityMemoryLength":43,"entityDataLength":38,"entitySharedMemoryLength":20})", Mode::Watch);
    CHECK(given.entity_memory_length == 43);
    CHECK(given.entity_data_length == 38);
    CHECK(given.entity_shared_memory_length == 20);
    const EngineModeData absent = parse_engine_mode_data(head + "}", Mode::Play);
    CHECK_FALSE(absent.entity_memory_length.has_value());
    CHECK_FALSE(absent.entity_data_length.has_value());
    CHECK_FALSE(absent.entity_shared_memory_length.has_value());
    CHECK_THROWS_AS(parse_engine_mode_data(head + R"(,"entityDataLength":-1})", Mode::Play), LoadError);
    CHECK_THROWS_AS(parse_engine_mode_data(head + R"(,"entityDataLength":1.5})", Mode::Play), LoadError);
}
}
