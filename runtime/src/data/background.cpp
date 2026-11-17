#include "data/background.h"

#include "data/json.h"

namespace sr::data {

BackgroundData parse_background_data(std::string_view json) {
    JsonDocument doc(json, "background.data");
    const JsonValue root = doc.root();
    BackgroundData out;
    out.aspect_ratio = root.opt_number("aspectRatio");
    out.fit = root.string("fit");
    out.color = root.string("color");
    out.scale_x = root.opt_number("scaleX");
    out.scale_y = root.opt_number("scaleY");
    return out;
}

BackgroundConfiguration parse_background_configuration(std::string_view json) {
    JsonDocument doc(json, "background.configuration");
    const JsonValue root = doc.root();
    BackgroundConfiguration out;
    out.scope = root.opt_string("scope");
    out.blur = root.number("blur");
    out.mask = root.string("mask");
    return out;
}

}  // namespace sr::data
