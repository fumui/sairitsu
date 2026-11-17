#include "vm/memory.h"

#include <algorithm>
#include <string>

namespace sr::vm {

namespace {

constexpr int32_t kTemporaryMemoryId = 10000;  // the same in both modes

// The block's floats per unit: the engine's declared length for the entity blocks it covers (the same ids in both
// modes), else the table's size.
int32_t unit_size(const BlockDef& d, const MemoryConfig& c) {
    int32_t declared = -1;
    switch (d.id) {
        case 4000: declared = c.entity_memory_length; break;        // Entity Memory
        case 4001: case 4101: declared = c.entity_data_length; break;  // Entity Data (Array)
        case 4002: case 4102: declared = c.entity_shared_memory_length; break;  // Entity Shared Memory (Array)
        default: break;
    }
    return declared >= 0 ? declared : d.size;
}

int64_t counted_size(const BlockDef& d, const MemoryConfig& c) {
    switch (d.count) {
        case BlockCount::Fixed: return d.size;
        case BlockCount::Entities: return int64_t(unit_size(d, c)) * c.entity_count;
        case BlockCount::Options: return int64_t(d.size) * c.option_count;
        case BlockCount::Buckets: return int64_t(d.size) * c.bucket_count;
        case BlockCount::Archetypes: return int64_t(d.size) * c.archetype_count;
        case BlockCount::RomSize: return c.rom_size;
        case BlockCount::Touches: return int64_t(d.size) * c.touch_capacity;
    }
    return 0;
}

}  // namespace

Memory::Memory(data::Mode mode, const MemoryConfig& config) : mode_(mode), config_(config) {
    auto table = block_table(mode);
    slots_.resize(table.size());
    int32_t max_id = 0;
    for (size_t i = 0; i < table.size(); ++i) {
        slots_[i].def = &table[i];
        max_id = std::max(max_id, table[i].id);
    }
    by_id_.assign(size_t(max_id) + 1, -1);
    for (size_t i = 0; i < table.size(); ++i) by_id_[table[i].id] = int16_t(i);

    auto array_of = [&](const BlockDef& d) -> Slot* {
        std::string name = std::string(d.name) + " Array";
        for (Slot& o : slots_)
            if (o.def->name == name) return &o;
        return nullptr;
    };

    for (Slot& s : slots_) {
        const BlockDef& d = *s.def;
        if (d.scope == BlockScope::PerEntity) {
            s.total = s.stride = unit_size(d, config);
            s.entities = d.spawned ? config.entity_count + config.spawn_capacity : config.entity_count;
            if (array_of(d)) continue;  // a view into the array block
            s.own.assign(size_t(s.stride) * size_t(s.entities), 0.0f);
        } else {
            s.total = int32_t(counted_size(d, config));
            if (d.scope == BlockScope::EntityArray) s.stride = unit_size(d, config);
            s.own.assign(size_t(s.total), 0.0f);
        }
        for (size_t k = 0; k < s.own.size(); ++k) s.own[k] = d.init[k % d.init_len];
    }
    for (Slot& s : slots_) {
        if (s.def->scope == BlockScope::PerEntity) {
            Slot* array = array_of(*s.def);
            s.base = array ? array->own.data() : s.own.data();
        } else {
            s.base = s.own.data();
        }
    }
    temporary_slot_ = slot_of(kTemporaryMemoryId);
    own_.views.resize(slots_.size());
    bind(data::CallbackKind::Preprocess, -1);
}

int32_t Memory::slot_of(int32_t id) const {
    if (id < 0 || size_t(id) >= by_id_.size()) return -1;
    return by_id_[size_t(id)];
}

Memory::Lane Memory::make_lane() const {
    Lane lane;
    lane.views.resize(slots_.size());
    if (temporary_slot_ >= 0) lane.temporary.assign(size_t(slots_[size_t(temporary_slot_)].total), 0.0f);
    return lane;
}

void Memory::bind(data::CallbackKind kind, int32_t entity, Lane& lane) {
    std::vector<BlockView>& views = lane.views;
    for (size_t i = 0; i < slots_.size(); ++i) {
        const Slot& s = slots_[i];
        const BlockDef& d = *s.def;
        bool r = (d.read_mask >> unsigned(kind)) & 1u;
        bool w = (d.write_mask >> unsigned(kind)) & 1u;
        if (d.scope == BlockScope::PerEntity) {
            if (entity < 0 || entity >= s.entities)
                views[i] = BlockView{};
            else
                views[i] = BlockView{s.base + size_t(entity) * size_t(s.total), s.total, r, w};
        } else {
            float* base = int32_t(i) == temporary_slot_ && !lane.temporary.empty() ? lane.temporary.data() : s.base;
            views[i] = BlockView{base, s.total, r, w};
        }
    }
}

BlockView* Memory::view(int32_t block_id, Lane& lane) {
    int32_t i = slot_of(block_id);
    return i < 0 || size_t(i) >= lane.views.size() ? nullptr : &lane.views[size_t(i)];
}

float* Memory::entity_block(int32_t block_id, int32_t entity) {
    int32_t i = slot_of(block_id);
    if (i < 0 || entity < 0) return nullptr;
    const Slot& s = slots_[size_t(i)];
    switch (s.def->scope) {
        case BlockScope::Global: return nullptr;
        case BlockScope::PerEntity:
            return entity < s.entities ? s.base + size_t(entity) * size_t(s.total) : nullptr;
        case BlockScope::EntityArray:
            return entity < config_.entity_count ? s.base + size_t(entity) * size_t(s.stride) : nullptr;
    }
    return nullptr;
}

int32_t Memory::entity_stride(int32_t block_id) const {
    const int32_t i = slot_of(block_id);
    return i < 0 ? 0 : slots_[size_t(i)].stride;
}

std::span<float> Memory::raw(int32_t block_id) {
    int32_t i = slot_of(block_id);
    if (i < 0) return {};
    const Slot& s = slots_[size_t(i)];
    if (s.def->scope == BlockScope::PerEntity)
        return std::span<float>(s.base, size_t(s.total) * size_t(s.entities));
    return std::span<float>(s.base, size_t(s.total));
}

}  // namespace sr::vm
