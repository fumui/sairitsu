#pragma once

#include <optional>
#include <string>
#include <string_view>

namespace sr::data {

struct BackgroundData {
    std::optional<float> aspect_ratio;
    std::string fit;    // width | height | contain | cover
    std::string color;  // CSS-style colour string
    std::optional<float> scale_x, scale_y;
};

struct BackgroundConfiguration {
    std::optional<std::string> scope;
    float blur = 0;
    std::string mask;
};

// Inputs are the decompressed JSON. Throw LoadError.
BackgroundData parse_background_data(std::string_view json);
BackgroundConfiguration parse_background_configuration(std::string_view json);

}  // namespace sr::data
