#pragma once

#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace sr::data {

// One node of the engine's expression forest. A value node carries a constant;
// a function node names a runtime function and the indices of its argument nodes.
struct Node {
    bool is_value = false;
    float value = 0;
    std::string func;
    std::vector<int32_t> args;
};

enum class CallbackKind : uint8_t {
    Preprocess, SpawnTime, DespawnTime, SpawnOrder, ShouldSpawn,
    Initialize, UpdateSequential, Touch, UpdateParallel, Terminate, UpdateSpawn
};

struct Callback {
    int32_t node = -1;  // entry node index
    float order = 0;
};

struct Import {
    std::string name;
    int32_t index = 0;           // entity data slot (exports: position in the list)
    std::optional<float> def;
};

struct Archetype {
    std::string name;
    bool has_input = false;
    std::map<CallbackKind, Callback> callbacks;
    std::vector<Import> imports;
    std::vector<Import> exports;  // play data only; names only (index = position)
};

struct NamedId {
    std::string name;
    int32_t id = 0;
};

struct BucketSprite {
    int32_t id = 0;
    std::optional<int32_t> fallback_id;
    float x = 0, y = 0, w = 0, h = 0, rotation = 0;
};

struct Bucket {
    std::vector<BucketSprite> sprites;
    std::string unit;
};

enum class Mode { Play, Watch };

struct EngineModeData {
    std::string render_mode;  // "default" when absent
    std::vector<NamedId> sprites, clips, effects;
    std::vector<Bucket> buckets;
    std::vector<Archetype> archetypes;
    int32_t update_spawn = -1;  // watch only: entry node of the global updateSpawn, -1 if absent
    std::vector<Node> nodes;
    // Optional per-entity lengths of Entity Memory, Entity Data (and its array) and Entity Shared Memory (and its
    // array); absent, the blocks keep the wiki's sizes (engine-specs/resources/engine-watch-data, -play-data).
    std::optional<int32_t> entity_memory_length, entity_data_length, entity_shared_memory_length;
};

// The largest entity block length engine data may declare (a guard against absurd allocations; the limit
// is unknown).
inline constexpr int32_t kMaxEntityBlockLength = 65536;

// `json` is the decompressed engine play or watch data. Throws LoadError.
EngineModeData parse_engine_mode_data(std::string_view json, Mode mode);

}  // namespace sr::data
