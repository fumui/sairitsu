#include "data/configuration.h"

#include <cstring>

#include "data/json.h"
#include "data/load_error.h"

namespace sr::data {

namespace {

Visibility parse_visibility(const JsonValue& v) {
    return {v.number("scale"), v.number("alpha")};
}

AnimationTween parse_tween(const JsonValue& v) {
    return {v.number("from"), v.number("to"), v.number("duration"), v.string("ease")};
}

Animation parse_animation(const JsonValue& v) {
    return {parse_tween(v.field("scale")), parse_tween(v.field("alpha"))};
}

Ui parse_ui(const JsonValue& v) {
    Ui ui;
    ui.scope = v.opt_string("scope");
    ui.primary_metric = v.string("primaryMetric");
    ui.secondary_metric = v.string("secondaryMetric");
    ui.menu = parse_visibility(v.field("menuVisibility"));
    ui.judgment = parse_visibility(v.field("judgmentVisibility"));
    ui.combo = parse_visibility(v.field("comboVisibility"));
    ui.primary_metric_visibility = parse_visibility(v.field("primaryMetricVisibility"));
    ui.secondary_metric_visibility = parse_visibility(v.field("secondaryMetricVisibility"));
    ui.progress = parse_visibility(v.field("progressVisibility"));
    ui.tutorial_navigation = parse_visibility(v.field("tutorialNavigationVisibility"));
    ui.tutorial_instruction = parse_visibility(v.field("tutorialInstructionVisibility"));
    ui.judgment_animation = parse_animation(v.field("judgmentAnimation"));
    ui.combo_animation = parse_animation(v.field("comboAnimation"));
    ui.judgment_error_style = v.string("judgmentErrorStyle");
    ui.judgment_error_placement = v.string("judgmentErrorPlacement");
    ui.judgment_error_min = v.number("judgmentErrorMin");
    return ui;
}

EngineOption parse_option(const JsonValue& v) {
    EngineOption o;
    o.name = v.string("name");
    o.category = v.opt_string("category");
    o.title = v.opt_string("title");
    o.description = v.opt_string("description");
    o.scope = v.opt_string("scope");
    o.standard = v.opt_boolean("standard").value_or(false);
    o.advanced = v.opt_boolean("advanced").value_or(false);
    o.def = v.number("def");
    const std::string type = v.string("type");
    if (type == "slider") {
        o.type = OptionType::Slider;
        o.min = v.number("min");
        o.max = v.number("max");
        o.step = v.number("step");
        o.unit = v.opt_string("unit").value_or("");
    } else if (type == "toggle") {
        o.type = OptionType::Toggle;
    } else if (type == "select") {
        o.type = OptionType::Select;
        for (const JsonValue& s : v.field("values").array()) o.values.push_back(s.string());
    } else {
        v.field("type").fail("unknown option type at");
    }
    return o;
}

std::vector<std::string> string_list(const JsonValue& list) {
    std::vector<std::string> out;
    for (const JsonValue& s : list.array()) out.push_back(s.string());
    return out;
}

}  // namespace

EngineConfiguration parse_engine_configuration(std::string_view json) {
    JsonDocument doc(json, "engine.configuration");
    const JsonValue root = doc.root();
    EngineConfiguration out;
    if (auto cats = root.opt("optionCategories")) {
        for (const JsonValue& c : cats->array()) {
            out.option_categories.push_back({c.string("name"), c.string("title")});
        }
    }
    for (const JsonValue& o : root.field("options").array()) out.options.push_back(parse_option(o));
    out.ui = parse_ui(root.field("ui"));
    if (auto names = root.opt("replayFallbackOptionNames")) {
        out.replay_fallback_option_names = string_list(*names);
    }
    return out;
}

std::vector<float> parse_rom(std::span<const uint8_t> bytes) {
    if (bytes.size() % 4 != 0) {
        throw LoadError("engine.rom: size " + std::to_string(bytes.size()) +
                        " is not a multiple of 4");
    }
    std::vector<float> out(bytes.size() / 4);
    for (size_t i = 0; i < out.size(); ++i) {
        const uint8_t* p = bytes.data() + i * 4;
        const uint32_t bits = uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 |
                              uint32_t(p[3]) << 24;
        std::memcpy(&out[i], &bits, 4);
    }
    return out;
}

}  // namespace sr::data
