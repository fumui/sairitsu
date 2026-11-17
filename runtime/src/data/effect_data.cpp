#include "data/effect_data.h"

#include "data/json.h"

namespace sr::data {

EffectData parse_effect_data(std::string_view json) {
    JsonDocument doc(json, "effect.data");
    EffectData out;
    for (const JsonValue& c : doc.root().field("clips").array()) {
        out.clips.push_back({c.string("name"), c.string("filename")});
    }
    return out;
}

}  // namespace sr::data
