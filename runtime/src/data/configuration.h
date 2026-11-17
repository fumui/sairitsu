#pragma once

#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace sr::data {

enum class OptionType { Slider, Toggle, Select };

struct EngineOption {
    std::string name;
    std::optional<std::string> category, title, description, scope;
    bool standard = false;
    bool advanced = false;
    OptionType type = OptionType::Toggle;
    float def = 0;
    float min = 0, max = 0, step = 0;  // slider
    std::string unit;                  // slider
    std::vector<std::string> values;   // select
};

struct Visibility {
    float scale = 1, alpha = 1;
};

struct AnimationTween {
    float from = 0, to = 0, duration = 0;
    std::string ease;
};

struct Animation {
    AnimationTween scale, alpha;
};

// Mirrors EngineConfigurationUI from @sonolus/core 7.15.4. Enum-like fields are
// kept as their JSON strings.
struct Ui {
    std::optional<std::string> scope;
    std::string primary_metric, secondary_metric;
    Visibility menu, judgment, combo, primary_metric_visibility, secondary_metric_visibility,
        progress, tutorial_navigation, tutorial_instruction;
    Animation judgment_animation, combo_animation;
    std::string judgment_error_style, judgment_error_placement;
    float judgment_error_min = 0;
};

struct OptionCategory {
    std::string name, title;
};

struct EngineConfiguration {
    std::vector<OptionCategory> option_categories;
    std::vector<EngineOption> options;
    Ui ui;
    std::vector<std::string> replay_fallback_option_names;
};

// `json` is the decompressed engine configuration. Throws LoadError.
EngineConfiguration parse_engine_configuration(std::string_view json);

// Little-endian float32 array (already gunzipped). Throws LoadError if the size is
// not a multiple of 4.
std::vector<float> parse_rom(std::span<const uint8_t> bytes);

}  // namespace sr::data
