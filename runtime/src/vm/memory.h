#pragma once

#include <cstdint>
#include <span>
#include <vector>

#include "data/engine_data.h"
#include "vm/blocks.h"

namespace sr::vm {

// What a callback sees of one block: the bound slice and its access for the bound callback.
// An absent block (a per-entity block with no such entity, e.g. Entity Data of a Spawn-function
// entity) has data == nullptr, size == 0 and neither flag set.
struct BlockView {
    float* data = nullptr;
    int32_t size = 0;
    bool readable = false, writable = false;
};

// Counts that size the data-dependent blocks.
struct MemoryConfig {
    // Per-entity lengths the engine data declares (entityMemoryLength, entityDataLength, entitySharedMemoryLength):
    // Entity Memory; Entity Data and Entity Data Array; Entity Shared Memory and its array. -1: the block table's.
    int32_t entity_memory_length = -1;
    int32_t entity_data_length = -1;
    int32_t entity_shared_memory_length = -1;
    int32_t entity_count = 0;      // entities from the level
    int32_t spawn_capacity = 0;    // extra Entity Memory / Entity Despawn slots for Spawn-function entities
    int32_t option_count = 0;      // Level Option
    int32_t bucket_count = 0;      // Level Bucket
    int32_t archetype_count = 0;   // Archetype Life / Score
    int32_t rom_size = 0;          // Engine Rom floats
    int32_t touch_capacity = 32;   // Runtime Touch Array, 15 floats per touch
};

// Owns every block of one session. Entity indices in [entity_count, entity_count + spawn_capacity)
// are Spawn-function entities: they have Entity Memory (and Entity Despawn in play) only.
//
// Callbacks see memory through a lane: a view table plus a Temporary Memory. The Memory has one lane of its own
// (bind / view, Temporary Memory in its own storage); callbacks that run at the same time on several threads each
// use a lane of their own (make_lane), so binding one never changes what another sees. Binding only reads the
// Memory, so several lanes may be bound concurrently while the Memory itself is not resized or moved.
class Memory {
public:
    struct Lane {
        std::vector<BlockView> views;  // one per block, in block-table order
        std::vector<float> temporary;  // this lane's Temporary Memory (empty in the Memory's own lane)
    };
    Memory(data::Mode mode, const MemoryConfig& config);
    // Views point into the slots' storage: a copy would keep pointing at the original. Moving keeps the
    // vectors' heap buffers, so the views stay valid.
    Memory(const Memory&) = delete;
    Memory& operator=(const Memory&) = delete;
    Memory(Memory&&) noexcept = default;
    Memory& operator=(Memory&&) noexcept = default;

    // Sets the views for the next run(): access per `kind`, per-entity blocks resolved to `entity`'s
    // slice. entity < 0 (global callbacks such as updateSpawn) leaves per-entity blocks absent.
    void bind(data::CallbackKind kind, int32_t entity) { bind(kind, entity, own_); }
    BlockView* view(int32_t block_id) { return view(block_id, own_); }  // nullptr = unknown id
    // A lane for another thread: its Temporary Memory starts zeroed. It stays valid when the Memory is moved or
    // rebuilt with the same mode (its sizes don't depend on the counts in MemoryConfig).
    Lane make_lane() const;
    void bind(data::CallbackKind kind, int32_t entity, Lane& lane);
    BlockView* view(int32_t block_id, Lane& lane);
    Lane& own_lane() { return own_; }
    float* entity_block(int32_t block_id, int32_t entity);   // nullptr = unknown, global, or absent
    std::span<float> raw(int32_t block_id);                  // whole storage, ignoring access (loaders)
    // Floats per entity of a per-entity or entity-array block (its size for one entity); 0 for other blocks.
    int32_t entity_stride(int32_t block_id) const;
    data::Mode mode() const { return mode_; }

private:
    struct Slot {
        const BlockDef* def = nullptr;
        std::vector<float> own;  // empty when the block is a view into an array block
        float* base = nullptr;
        int32_t total = 0;       // floats at base (Global / EntityArray); the stride (PerEntity)
        int32_t stride = 0;      // PerEntity / EntityArray: floats per entity
        int32_t entities = 0;    // PerEntity: entity indices that have this block
    };
    int32_t slot_of(int32_t block_id) const;

    data::Mode mode_;
    MemoryConfig config_;
    std::vector<Slot> slots_;
    Lane own_;
    int32_t temporary_slot_ = -1;  // Temporary Memory's slot
    std::vector<int16_t> by_id_;  // block id -> slot, -1 unknown
};

}  // namespace sr::vm
