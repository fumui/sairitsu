#include "data/engine_data.h"

#include <span>
#include <utility>

#include "data/json.h"
#include "data/load_error.h"

namespace sr::data {

namespace {

struct CallbackKey {
    const char* json_name;
    CallbackKind kind;
};

constexpr CallbackKey kWatchCallbacks[] = {
    {"preprocess", CallbackKind::Preprocess},
    {"spawnTime", CallbackKind::SpawnTime},
    {"despawnTime", CallbackKind::DespawnTime},
    {"initialize", CallbackKind::Initialize},
    {"updateSequential", CallbackKind::UpdateSequential},
    {"updateParallel", CallbackKind::UpdateParallel},
    {"terminate", CallbackKind::Terminate},
};

constexpr CallbackKey kPlayCallbacks[] = {
    {"preprocess", CallbackKind::Preprocess},
    {"spawnOrder", CallbackKind::SpawnOrder},
    {"shouldSpawn", CallbackKind::ShouldSpawn},
    {"initialize", CallbackKind::Initialize},
    {"updateSequential", CallbackKind::UpdateSequential},
    {"touch", CallbackKind::Touch},
    {"updateParallel", CallbackKind::UpdateParallel},
    {"terminate", CallbackKind::Terminate},
};

std::vector<NamedId> parse_named_ids(const JsonValue& list) {
    std::vector<NamedId> out;
    out.reserve(list.size());
    for (const JsonValue& item : list.array()) {
        out.push_back({item.string("name"), item.integer("id")});
    }
    return out;
}

Bucket parse_bucket(const JsonValue& v) {
    Bucket bucket;
    for (const JsonValue& s : v.field("sprites").array()) {
        BucketSprite sprite;
        sprite.id = s.integer("id");
        sprite.fallback_id = s.opt_integer("fallbackId");
        sprite.x = s.number("x");
        sprite.y = s.number("y");
        sprite.w = s.number("w");
        sprite.h = s.number("h");
        sprite.rotation = s.number("rotation");
        bucket.sprites.push_back(sprite);
    }
    bucket.unit = v.opt_string("unit").value_or("");
    return bucket;
}

Callback parse_callback(const JsonValue& v, size_t node_count) {
    Callback cb;
    JsonValue index = v.field("index");
    cb.node = index.integer();
    if (cb.node < 0 || size_t(cb.node) >= node_count) index.fail("node index out of range at");
    cb.order = v.opt_number("order").value_or(0.0f);
    return cb;
}

Archetype parse_archetype(const JsonValue& v, Mode mode, size_t node_count) {
    Archetype a;
    a.name = v.string("name");
    a.has_input = v.boolean("hasInput");
    const std::span<const CallbackKey> keys =
        mode == Mode::Play ? std::span<const CallbackKey>(kPlayCallbacks)
                           : std::span<const CallbackKey>(kWatchCallbacks);
    for (const CallbackKey& key : keys) {
        if (auto cb = v.opt(key.json_name)) {
            a.callbacks[key.kind] = parse_callback(*cb, node_count);
        }
    }
    for (const JsonValue& imp : v.field("imports").array()) {
        Import import;
        import.name = imp.string("name");
        import.index = imp.integer("index");
        import.def = imp.opt_number("def");
        a.imports.push_back(std::move(import));
    }
    if (mode == Mode::Play) {
        int32_t i = 0;
        for (const JsonValue& name : v.field("exports").array()) {
            a.exports.push_back({name.string(), i++, std::nullopt});
        }
    }
    return a;
}

std::vector<Node> parse_nodes(const JsonValue& list) {
    const std::vector<JsonValue> items = list.array();
    const size_t count = items.size();
    std::vector<Node> nodes;
    nodes.reserve(count);
    for (const JsonValue& item : items) {
        Node node;
        if (item.has("value")) {
            node.is_value = true;
            node.value = item.number("value");
        } else {
            node.func = item.string("func");
            for (const JsonValue& arg : item.field("args").array()) {
                const int32_t index = arg.integer();
                if (index < 0 || size_t(index) >= count) arg.fail("node index out of range at");
                node.args.push_back(index);
            }
        }
        nodes.push_back(std::move(node));
    }
    return nodes;
}

}  // namespace

EngineModeData parse_engine_mode_data(std::string_view json, Mode mode) {
    JsonDocument doc(json, mode == Mode::Play ? "engine.playData" : "engine.watchData");
    const JsonValue root = doc.root();

    EngineModeData out;
    const JsonValue skin = root.field("skin");
    out.render_mode = skin.opt_string("renderMode").value_or("default");
    out.sprites = parse_named_ids(skin.field("sprites"));
    out.clips = parse_named_ids(root.field("effect").field("clips"));
    out.effects = parse_named_ids(root.field("particle").field("effects"));
    for (const JsonValue& b : root.field("buckets").array()) out.buckets.push_back(parse_bucket(b));

    out.nodes = parse_nodes(root.field("nodes"));
    const size_t node_count = out.nodes.size();
    for (const JsonValue& a : root.field("archetypes").array()) {
        out.archetypes.push_back(parse_archetype(a, mode, node_count));
    }
    const auto length = [&](const char* key) -> std::optional<int32_t> {
        const auto v = root.opt(key);
        if (!v) return std::nullopt;
        const int32_t n = v->integer();
        if (n < 0 || n > kMaxEntityBlockLength)
            v->fail("expected a length from 0 to " + std::to_string(kMaxEntityBlockLength) + " at");
        return n;
    };
    out.entity_memory_length = length("entityMemoryLength");
    out.entity_data_length = length("entityDataLength");
    out.entity_shared_memory_length = length("entitySharedMemoryLength");
    if (mode == Mode::Watch) {
        if (auto spawn = root.opt("updateSpawn")) {
            out.update_spawn = spawn->integer();
            if (out.update_spawn < 0 || size_t(out.update_spawn) >= node_count) {
                spawn->fail("node index out of range at");
            }
        }
    }
    return out;
}

}  // namespace sr::data
