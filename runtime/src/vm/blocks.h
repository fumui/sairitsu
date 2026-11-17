#pragma once

#include <cstddef>
#include <cstdint>
#include <span>
#include <string_view>

#include "data/engine_data.h"

namespace sr::vm {

// Where a block's storage lives.
//   Global      one block for the whole session.
//   PerEntity   the bound entity's slice (Entity Memory, Data, Input, ...); a view into the matching
//               "<name> Array" block when there is one, otherwise it owns the storage.
//   EntityArray all entities, indexed entity * size + index.
enum class BlockScope : uint8_t { Global, PerEntity, EntityArray };

// What sizes a block. Fixed: `size` floats. Entities: `size` per entity. The rest: `size` per item
// (option, bucket, archetype, touch) or a flat float count (RomSize, where size is 1).
enum class BlockCount : uint8_t { Fixed, Entities, Options, Buckets, Archetypes, RomSize, Touches };

// One row of a generated table (blocks_watch.inc / blocks_play.inc).
//   size     fixed total size, or the stride (per entity / item) of a counted block.
//   spawned  per-entity block that also exists for entities made by the Spawn function.
//   read_mask/write_mask  one bit per data::CallbackKind.
//   init/init_len  initial values: the pattern tiles over the block (or over one entity/item).
struct BlockDef {
    int32_t id;
    std::string_view name;
    int32_t size;
    BlockScope scope;
    BlockCount count;
    bool spawned;
    uint16_t read_mask, write_mask;
    const float* init;
    size_t init_len;
};

constexpr uint16_t callback_bit(data::CallbackKind k) { return uint16_t(1u << unsigned(k)); }

// Sorted by id.
std::span<const BlockDef> block_table(data::Mode mode);
const BlockDef* find_block(data::Mode mode, int32_t id);  // nullptr = unknown id

}  // namespace sr::vm
