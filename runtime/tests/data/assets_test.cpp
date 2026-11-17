#include <cstring>
#include <string>

#include "data/background.h"
#include "data/configuration.h"
#include "data/effect_data.h"
#include "data/load_error.h"
#include "data/particle_data.h"
#include "data/resources.h"
#include "data/skin.h"
#include "doctest.h"

using namespace sr::data;

TEST_SUITE("data") {
TEST_CASE("skin data parses sprites and transform coefficients") {
    const SkinData skin = parse_skin_data(R"({
        "width": 4096, "height": 4096, "interpolation": true,
        "sprites": [
          {"name": "Stage", "x": 1, "y": 2, "w": 3, "h": 4, "transform": {
             "x1": {"x1": 1}, "y1": {"y1": 2, "x3": 0.5}, "x2": {}, "y2": {}, "x3": {}, "y3": {},
             "x4": {}, "y4": {"y4": -1}}}
        ]})");
    CHECK(skin.width == 4096);
    CHECK(skin.interpolation);
    REQUIRE(skin.sprites.size() == 1);
    const SkinSprite& s = skin.sprites[0];
    CHECK(s.name == "Stage");
    CHECK(s.w == 3.0f);
    // rows: x1,y1,x2,y2,x3,y3,x4,y4; columns: x1..x4,y1..y4
    CHECK(s.transform.coef[0][0] == 1.0f);
    CHECK(s.transform.coef[1][4] == 2.0f);
    CHECK(s.transform.coef[1][2] == 0.5f);
    CHECK(s.transform.coef[7][7] == -1.0f);
    CHECK(s.transform.coef[2][2] == 0.0f);
}

TEST_CASE("skin data names the missing field") {
    try {
        parse_skin_data(R"({"width":1,"height":1,"interpolation":false,"sprites":[{"name":"a"}]})");
        FAIL("expected LoadError");
    } catch (const LoadError& e) {
        const std::string what = e.what();
        CHECK(what.find("skin.data") != std::string::npos);
        CHECK(what.find("sprites[0].x") != std::string::npos);
    }
}

TEST_CASE("particle data parses effects, groups and expressions") {
    const ParticleData p = parse_particle_data(R"({
        "width": 4096, "height": 4096, "interpolation": false,
        "sprites": [{"x": 0, "y": 0, "w": 10, "h": 10}],
        "effects": [{
          "name": "Tap",
          "transform": {"x1": {"c": 1, "x2": 2, "r3": 3}, "y4": {"sinr8": 4, "cosr1": 5}},
          "groups": [{"count": 2, "particles": [{
             "sprite": 0, "color": "red", "start": 0, "duration": 1,
             "x": {"from": {"c": 1}, "to": {"r1": 2}, "ease": "outQuad"},
             "y": {}, "w": {}, "h": {}, "r": {}, "a": {"from": {"c": 1}}}]}]
        }]})");
    REQUIRE(p.sprites.size() == 1);
    REQUIRE(p.effects.size() == 1);
    const ParticleEffect& e = p.effects[0];
    CHECK(e.transform.row[0].term[0] == 1.0f);   // c
    CHECK(e.transform.row[0].term[2] == 2.0f);   // x2
    CHECK(e.transform.row[0].term[9 + 2] == 3.0f);   // r3
    CHECK(e.transform.row[7].term[17 + 7] == 4.0f);  // sinr8
    CHECK(e.transform.row[7].term[25] == 5.0f);      // cosr1
    const ParticleItem& item = e.groups[0].particles[0];
    CHECK(item.x.has_from);
    CHECK(item.x.has_to);
    CHECK(item.x.to.term[9] == 2.0f);
    CHECK(item.x.ease == "outQuad");
    CHECK_FALSE(item.y.has_from);
    CHECK(item.a.has_from);
    CHECK_FALSE(item.a.has_to);
}

TEST_CASE("effect and background data parse") {
    const EffectData fx = parse_effect_data(
        R"({"clips":[{"name":"#PERFECT","filename":"0"},{"name":"x","filename":"1"}]})");
    REQUIRE(fx.clips.size() == 2);
    CHECK(fx.clips[1].filename == "1");

    const BackgroundData bg =
        parse_background_data(R"({"fit":"cover","color":"#000000","scaleX":1.5})");
    CHECK(bg.fit == "cover");
    CHECK_FALSE(bg.aspect_ratio.has_value());
    CHECK(bg.scale_x == 1.5f);
    CHECK_FALSE(bg.scale_y.has_value());
    const BackgroundConfiguration cfg =
        parse_background_configuration(R"({"blur":0.5,"mask":"#00000080"})");
    CHECK(cfg.blur == 0.5f);
    CHECK(cfg.mask == "#00000080");
    CHECK_THROWS_AS(parse_background_configuration(R"({"blur":0.5})"), LoadError);
}

TEST_CASE("engine configuration parses options and ui") {
    const char* json = R"({
      "options": [
        {"name": "#SPEED", "standard": true, "type": "slider", "def": 1, "min": 0.5, "max": 2,
         "step": 0.05, "unit": "#PERCENTAGE_UNIT"},
        {"name": "flag", "type": "toggle", "def": 1},
        {"name": "pick", "type": "select", "def": 0, "values": ["a", "b"]}
      ],
      "ui": {
        "primaryMetric": "arcade", "secondaryMetric": "life",
        "menuVisibility": {"scale": 1, "alpha": 1}, "judgmentVisibility": {"scale": 1, "alpha": 1},
        "comboVisibility": {"scale": 1, "alpha": 1},
        "primaryMetricVisibility": {"scale": 1, "alpha": 1},
        "secondaryMetricVisibility": {"scale": 1, "alpha": 1},
        "progressVisibility": {"scale": 1, "alpha": 1},
        "tutorialNavigationVisibility": {"scale": 1, "alpha": 1},
        "tutorialInstructionVisibility": {"scale": 1, "alpha": 1},
        "judgmentAnimation": {"scale": {"from": 1, "to": 1, "duration": 0.1, "ease": "linear"},
                              "alpha": {"from": 1, "to": 0, "duration": 0.2, "ease": "none"}},
        "comboAnimation": {"scale": {"from": 1, "to": 1, "duration": 0, "ease": "linear"},
                           "alpha": {"from": 1, "to": 1, "duration": 0, "ease": "linear"}},
        "judgmentErrorStyle": "none", "judgmentErrorPlacement": "center", "judgmentErrorMin": 20
      }})";
    const EngineConfiguration c = parse_engine_configuration(json);
    REQUIRE(c.options.size() == 3);
    CHECK(c.options[0].type == OptionType::Slider);
    CHECK(c.options[0].standard);
    CHECK(c.options[0].step == 0.05f);
    CHECK(c.options[1].type == OptionType::Toggle);
    CHECK(c.options[2].values.size() == 2);
    CHECK(c.ui.primary_metric == "arcade");
    CHECK(c.ui.judgment_animation.alpha.duration == 0.2f);
    CHECK(c.ui.judgment_error_min == 20.0f);
}

TEST_CASE("rom parses little-endian float32") {
    const uint8_t bytes[] = {0x00, 0x00, 0x80, 0x3f, 0x00, 0x00, 0x00, 0xc0};
    const std::vector<float> rom = parse_rom(bytes);
    REQUIRE(rom.size() == 2);
    CHECK(rom[0] == 1.0f);
    CHECK(rom[1] == -2.0f);
    CHECK_THROWS_AS(parse_rom(std::span<const uint8_t>(bytes, 7)), LoadError);
}

TEST_CASE("resource kind names round trip") {
    CHECK(std::string(resource_kind_name(ResourceKind::EngineWatchData)) == "engine.watchData");
    CHECK(std::string(resource_kind_name(ResourceKind::LevelBgm)) == "level.bgm");
    for (int32_t i = 0; i < kResourceKindCount; ++i) {
        const auto kind = static_cast<ResourceKind>(i);
        CHECK(resource_kind_from_name(resource_kind_name(kind)) == kind);
    }
    CHECK_FALSE(resource_kind_from_name("nope").has_value());
}

TEST_CASE("load_level names a missing resource kind") {
    try {
        load_level({});
        FAIL("expected LoadError");
    } catch (const LoadError& e) {
        CHECK(std::string(e.what()).find("engine.watchData") != std::string::npos);
    }
}
}
