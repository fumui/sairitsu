#include <fstream>
#include <sstream>
#include <string>

#include "data/level_data.h"
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
TEST_CASE("level data ref resolves to entity index") {
    const LevelData level = parse_level_data(read_fixture("level.json"));
    CHECK(level.bgm_offset == 0.25f);
    REQUIRE(level.entities.size() == 3);
    const auto& a = level.entities[1];
    REQUIRE(a.data.size() == 2);
    CHECK_FALSE(a.data[0].is_ref);
    CHECK(a.data[0].value == 4.0f);
    CHECK(a.data[1].is_ref);
    CHECK(a.data[1].ref == 2);                  // forward reference
    CHECK(level.entities[2].data[1].ref == 1);  // backward reference
}

// Editors store labels as dangling refs (UntitledCharts' #TIMESCALE_GROUP editorName: "Default", "#1"),
// and such levels must still play, so an unknown ref loads as unresolved instead of failing.
TEST_CASE("unknown ref loads as unresolved") {
    const LevelData level = parse_level_data(read_fixture("level_bad_ref.json"));
    REQUIRE(level.entities.size() == 1);
    REQUIRE(level.entities[0].data.size() == 1);
    CHECK(level.entities[0].data[0].is_ref);
    CHECK(level.entities[0].data[0].ref == -1);
}

TEST_CASE("non-string ref names the field") {
    try {
        parse_level_data(R"({"bgmOffset":0,"entities":[{"archetype":"Tap","data":[{"name":"next","ref":3}]}]})");
        FAIL("expected LoadError");
    } catch (const LoadError& e) {
        CHECK(std::string(e.what()).find("level.data") != std::string::npos);
    }
}
}
