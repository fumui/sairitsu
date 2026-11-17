#pragma once

#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace sr::data {

struct EntityDatum {
    std::string name;
    bool is_ref = false;
    float value = 0;   // when !is_ref
    int32_t ref = -1;  // entity index when is_ref; -1 when the ref names no entity
};

struct LevelEntity {
    std::string archetype;
    std::string name;  // empty when the entity is unnamed
    std::vector<EntityDatum> data;
};

struct LevelData {
    float bgm_offset = 0;
    std::vector<LevelEntity> entities;
};

// Parses the decompressed level JSON and resolves every `ref` to the index of the
// entity carrying that name. Throws LoadError, naming the ref, if none does.
LevelData parse_level_data(std::string_view json);

}  // namespace sr::data
