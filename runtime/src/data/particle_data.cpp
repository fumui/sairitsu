#include "data/particle_data.h"

#include "data/json.h"
#include "data/load_error.h"
#include "data/skin.h"

namespace sr::data {

namespace {

// Term index for a key, or -1.
int term_index(const std::string& key, bool allow_coordinates) {
    if (key == "c") return 0;
    if (allow_coordinates) {
        const int col = transform_column(key);
        if (col >= 0) return 1 + col;
    }
    auto numbered = [&](const char* prefix, int base) -> int {
        const std::string p(prefix);
        if (key.size() != p.size() + 1 || key.compare(0, p.size(), p) != 0) return -1;
        const char d = key.back();
        return d >= '1' && d <= '8' ? base + (d - '1') : -1;
    };
    if (int i = numbered("r", 9); i >= 0) return i;
    if (int i = numbered("sinr", 17); i >= 0) return i;
    if (int i = numbered("cosr", 25); i >= 0) return i;
    return -1;
}

ParticleExpr parse_expr(const JsonValue& v, bool allow_coordinates) {
    ParticleExpr e;
    v.each_member([&](const std::string& key, const JsonValue& coef) {
        const int i = term_index(key, allow_coordinates);
        if (i < 0) coef.fail("unknown expression term at");
        e.term[i] = coef.number();
    });
    return e;
}

ParticleProperty parse_property(const JsonValue& v) {
    ParticleProperty p;
    if (auto from = v.opt("from")) {
        p.has_from = true;
        p.from = parse_expr(*from, false);
    }
    if (auto to = v.opt("to")) {
        p.has_to = true;
        p.to = parse_expr(*to, false);
    }
    p.ease = v.opt_string("ease").value_or("");
    return p;
}

ParticleItem parse_item(const JsonValue& v) {
    ParticleItem item;
    item.sprite = v.integer("sprite");
    item.color = v.string("color");
    item.start = v.number("start");
    item.duration = v.number("duration");
    item.x = parse_property(v.field("x"));
    item.y = parse_property(v.field("y"));
    item.w = parse_property(v.field("w"));
    item.h = parse_property(v.field("h"));
    item.r = parse_property(v.field("r"));
    item.a = parse_property(v.field("a"));
    return item;
}

ParticleEffect parse_effect(const JsonValue& v) {
    ParticleEffect effect;
    effect.name = v.string("name");
    v.field("transform").each_member([&](const std::string& out_key, const JsonValue& expr) {
        const int row = transform_row(out_key);
        if (row < 0) expr.fail("unknown transform output at");
        effect.transform.row[row] = parse_expr(expr, true);
    });
    for (const JsonValue& g : v.field("groups").array()) {
        ParticleGroup group;
        group.count = g.integer("count");
        for (const JsonValue& p : g.field("particles").array()) {
            group.particles.push_back(parse_item(p));
        }
        effect.groups.push_back(std::move(group));
    }
    return effect;
}

}  // namespace

ParticleData parse_particle_data(std::string_view json) {
    JsonDocument doc(json, "particle.data");
    const JsonValue root = doc.root();
    ParticleData out;
    out.width = root.integer("width");
    out.height = root.integer("height");
    out.interpolation = root.boolean("interpolation");
    for (const JsonValue& s : root.field("sprites").array()) {
        out.sprites.push_back({s.number("x"), s.number("y"), s.number("w"), s.number("h")});
    }
    for (const JsonValue& e : root.field("effects").array()) {
        out.effects.push_back(parse_effect(e));
    }
    return out;
}

}  // namespace sr::data
