#pragma once

#include <string>
#include <string_view>
#include <vector>

namespace sr::data {

struct EffectClip {
    std::string name;
    std::string filename;  // entry name inside the effect.audio zip
};

struct EffectData {
    std::vector<EffectClip> clips;
};

// `json` is the decompressed effect data. Throws LoadError.
EffectData parse_effect_data(std::string_view json);

}  // namespace sr::data
