#include "data/skin.h"

#include "data/json.h"
#include "data/load_error.h"

namespace sr::data {

namespace {

bool is_coordinate_key(const std::string& key) {
    return key.size() == 2 && (key[0] == 'x' || key[0] == 'y') && key[1] >= '1' && key[1] <= '4';
}

SkinTransform parse_transform(const JsonValue& v) {
    SkinTransform t;
    v.each_member([&](const std::string& out_key, const JsonValue& expr) {
        const int row = transform_row(out_key);
        if (row < 0) expr.fail("unknown transform output at");
        expr.each_member([&](const std::string& in_key, const JsonValue& coef) {
            const int col = transform_column(in_key);
            if (col < 0) coef.fail("unknown transform term at");
            t.coef[row][col] = coef.number();
        });
    });
    return t;
}

}  // namespace

int transform_row(const std::string& key) {
    if (!is_coordinate_key(key)) return -1;
    return 2 * (key[1] - '1') + (key[0] == 'y' ? 1 : 0);
}

int transform_column(const std::string& key) {
    if (!is_coordinate_key(key)) return -1;
    return (key[0] == 'y' ? 4 : 0) + (key[1] - '1');
}

SkinData parse_skin_data(std::string_view json) {
    JsonDocument doc(json, "skin.data");
    const JsonValue root = doc.root();
    SkinData out;
    out.width = root.integer("width");
    out.height = root.integer("height");
    out.interpolation = root.boolean("interpolation");
    for (const JsonValue& s : root.field("sprites").array()) {
        SkinSprite sprite;
        sprite.name = s.string("name");
        sprite.x = s.number("x");
        sprite.y = s.number("y");
        sprite.w = s.number("w");
        sprite.h = s.number("h");
        sprite.transform = parse_transform(s.field("transform"));
        out.sprites.push_back(std::move(sprite));
    }
    return out;
}

}  // namespace sr::data
