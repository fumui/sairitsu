#include "data/level_data.h"

#include <map>
#include <utility>

#include "data/json.h"
#include "data/load_error.h"

namespace sr::data {

LevelData parse_level_data(std::string_view json) {
    JsonDocument doc(json, "level.data");
    const JsonValue root = doc.root();
    LevelData out;
    out.bgm_offset = root.number("bgmOffset");

    const std::vector<JsonValue> items = root.field("entities").array();
    out.entities.reserve(items.size());
    std::map<std::string, int32_t> by_name;  // not unordered_map: its rehash calls the platform ceilf
    // Per entity: (datum slot, ref value). Resolved after all names are known.
    std::vector<std::vector<std::pair<size_t, JsonValue>>> pending(items.size());

    for (size_t i = 0; i < items.size(); ++i) {
        const JsonValue& item = items[i];
        LevelEntity entity;
        entity.archetype = item.string("archetype");
        entity.name = item.opt_string("name").value_or("");
        if (!entity.name.empty()) by_name.emplace(entity.name, int32_t(i));
        for (const JsonValue& d : item.field("data").array()) {
            EntityDatum datum;
            datum.name = d.string("name");
            if (d.has("ref")) {
                datum.is_ref = true;
                pending[i].emplace_back(entity.data.size(), d.field("ref"));
            } else {
                datum.value = d.number("value");
            }
            entity.data.push_back(std::move(datum));
        }
        out.entities.push_back(std::move(entity));
    }

    for (size_t i = 0; i < pending.size(); ++i) {
        for (const auto& [slot, ref] : pending[i]) {
            // An unknown name stays unresolved (-1): editors park labels in refs (UntitledCharts writes
            // #TIMESCALE_GROUP editorName: "Default") that engines never import, and such levels still play.
            const auto it = by_name.find(ref.string());
            if (it != by_name.end()) out.entities[i].data[slot].ref = it->second;
        }
    }
    return out;
}

}  // namespace sr::data
