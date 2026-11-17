#include <cstdint>
#include <string>
#include <type_traits>
#include <utility>

#include "doctest.h"
#include "vm/blocks.h"
#include "vm/memory.h"

using sr::data::CallbackKind;
using sr::data::Mode;
using sr::vm::BlockDef;
using sr::vm::callback_bit;
using sr::vm::find_block;
using sr::vm::Memory;
using sr::vm::MemoryConfig;

namespace {

bool readable(const BlockDef& b, CallbackKind k) { return b.read_mask & callback_bit(k); }
bool writable(const BlockDef& b, CallbackKind k) { return b.write_mask & callback_bit(k); }

// Callbacks a block can be written in, as a mask.
uint16_t write_set(Mode m, int32_t id) { return find_block(m, id)->write_mask; }

MemoryConfig small_config() {
    MemoryConfig c;
    c.entity_count = 3;
    c.spawn_capacity = 2;
    c.option_count = 4;
    c.bucket_count = 2;
    c.archetype_count = 5;
    c.rom_size = 7;
    return c;
}

}  // namespace

TEST_SUITE("vm") {

TEST_CASE("watch block ids") {
    struct { int32_t id; const char* name; } expect[] = {
        {1000, "Runtime Environment"}, {1001, "Runtime Update"}, {1002, "Runtime Skin Transform"},
        {10000, "Temporary Memory"}, {4003, "Entity Info"}, {4004, "Entity Input"},
    };
    for (auto& e : expect) {
        const BlockDef* b = find_block(Mode::Watch, e.id);
        REQUIRE(b != nullptr);
        CHECK(std::string(b->name) == e.name);
    }
    CHECK(std::string(find_block(Mode::Watch, 4005)->name) == "Entity Score");
    CHECK(find_block(Mode::Watch, 4999) == nullptr);
    CHECK(find_block(Mode::Watch, -1) == nullptr);
}

TEST_CASE("play skin transform is 1003") {
    CHECK(std::string(find_block(Mode::Play, 1003)->name) == "Runtime Skin Transform");
    CHECK(std::string(find_block(Mode::Play, 1002)->name) == "Runtime Touch Array");
    CHECK(std::string(find_block(Mode::Play, 4004)->name) == "Entity Despawn");
    CHECK(std::string(find_block(Mode::Play, 4005)->name) == "Entity Input");
}

TEST_CASE("tables are sorted by id with no duplicates") {
    for (Mode m : {Mode::Play, Mode::Watch}) {
        auto t = sr::vm::block_table(m);
        for (size_t i = 1; i < t.size(); ++i) CHECK(t[i - 1].id < t[i].id);
    }
}

TEST_CASE("runtime update not writable in watch") {
    const BlockDef* b = find_block(Mode::Watch, 1001);
    CHECK(b->write_mask == 0);
    CHECK(readable(*b, CallbackKind::UpdateSequential));
    CHECK(b->size == 4);
}

TEST_CASE("entity input not writable in updateSpawn") {
    const BlockDef* b = find_block(Mode::Watch, 4004);
    CHECK(readable(*b, CallbackKind::UpdateSpawn));
    CHECK_FALSE(writable(*b, CallbackKind::UpdateSpawn));
    CHECK(writable(*b, CallbackKind::Preprocess));
    CHECK(writable(*b, CallbackKind::SpawnTime));
    CHECK(writable(*b, CallbackKind::Terminate));
}

TEST_CASE("skin transform writable in preprocess and updateSequential only") {
    // Watch: exactly those two. Play adds touch (sonolus.py and the wiki agree).
    CHECK(write_set(Mode::Watch, 1002) ==
          (callback_bit(CallbackKind::Preprocess) | callback_bit(CallbackKind::UpdateSequential)));
    CHECK(write_set(Mode::Play, 1003) ==
          (callback_bit(CallbackKind::Preprocess) | callback_bit(CallbackKind::UpdateSequential) |
           callback_bit(CallbackKind::Touch)));
}

TEST_CASE("play has no watch-only callbacks in its masks") {
    for (const BlockDef& b : sr::vm::block_table(Mode::Play)) {
        uint16_t watch_only = callback_bit(CallbackKind::SpawnTime) | callback_bit(CallbackKind::DespawnTime) |
                              callback_bit(CallbackKind::UpdateSpawn);
        CHECK((b.read_mask & watch_only) == 0);
        CHECK((b.write_mask & watch_only) == 0);
    }
}

TEST_CASE("sizes and initial values come from the wiki") {
    CHECK(find_block(Mode::Watch, 4000)->size == 64);   // Entity Memory
    CHECK(find_block(Mode::Watch, 4001)->size == 32);   // Entity Data
    CHECK(find_block(Mode::Play, 4005)->size == 5);     // Entity Input
    CHECK(find_block(Mode::Watch, 10000)->size == 4096);

    Memory mem(Mode::Play, small_config());
    float* skin = mem.view(1003)->data;
    for (int i = 0; i < 16; ++i) CHECK(skin[i] == (i % 5 == 0 ? 1.0f : 0.0f));
    auto life = mem.raw(2005);
    CHECK(life[6] == 1000.0f);
    CHECK(life[7] == 1000.0f);
    CHECK(life[0] == 0.0f);
    auto arch_score = mem.raw(5001);
    REQUIRE(arch_score.size() == 5);
    for (float v : arch_score) CHECK(v == 1.0f);
    CHECK(mem.raw(2002).size() == 4);
    CHECK(mem.raw(2003).size() == 12);
    CHECK(mem.raw(5000).size() == 20);
    CHECK(mem.raw(3000).size() == 7);
    CHECK(mem.raw(1002).size() == 15 * 32);
    CHECK(mem.raw(4101).size() == 32 * 3);
}

TEST_CASE("entity input starts with no bucket") {
    Memory mem(Mode::Watch, small_config());
    for (int e = 0; e < 3; ++e) {
        float* in = mem.entity_block(4004, e);
        REQUIRE(in != nullptr);
        CHECK(in[0] == 0.0f);
        CHECK(in[1] == -1.0f);
        CHECK(in[2] == 0.0f);
    }
}

TEST_CASE("bind selects entity slice") {
    Memory mem(Mode::Watch, small_config());
    mem.bind(CallbackKind::Initialize, 1);
    auto* v = mem.view(4000);
    REQUIRE(v != nullptr);
    CHECK(v->size == 64);
    CHECK(v->readable);
    CHECK(v->writable);
    CHECK(v->data == mem.entity_block(4000, 1));
    CHECK(v->data == mem.entity_block(4000, 0) + 64);
    v->data[5] = 42.0f;

    mem.bind(CallbackKind::UpdateSequential, 0);
    CHECK(mem.view(4000)->data[5] == 0.0f);
    mem.bind(CallbackKind::UpdateSequential, 1);
    CHECK(mem.view(4000)->data[5] == 42.0f);

    // Entity Data is a view into Entity Data Array: same floats, one slice per entity.
    float* arr = mem.view(4101)->data;
    CHECK(mem.view(4001)->data == arr + 32);
    CHECK(mem.view(4001)->size == 32);
    // Global blocks do not move.
    float* level = mem.view(2000)->data;
    mem.bind(CallbackKind::UpdateSequential, 2);
    CHECK(mem.view(2000)->data == level);
    CHECK(mem.view(4001)->data == arr + 64);
}

TEST_CASE("engine-declared entity block lengths set the per-entity strides") {
    for (Mode m : {Mode::Watch, Mode::Play}) {
        MemoryConfig c = small_config();
        c.entity_memory_length = 43;
        c.entity_data_length = 38;
        c.entity_shared_memory_length = 20;
        Memory mem(m, c);
        CHECK(mem.entity_stride(4000) == 43);
        CHECK(mem.entity_stride(4001) == 38);
        CHECK(mem.entity_stride(4101) == 38);
        CHECK(mem.entity_stride(4002) == 20);
        CHECK(mem.entity_stride(4102) == 20);
        CHECK(mem.raw(4101).size() == 3 * 38);
        CHECK(mem.raw(4102).size() == 3 * 20);
        mem.bind(CallbackKind::Preprocess, 2);
        CHECK(mem.view(4000)->size == 43);
        CHECK(mem.view(4001)->size == 38);
        CHECK(mem.view(4001)->data == mem.view(4101)->data + 2 * 38);
        CHECK(mem.view(4002)->data == mem.view(4102)->data + 2 * 20);
        CHECK(mem.entity_block(4101, 2) == mem.view(4101)->data + 2 * 38);
        CHECK(mem.entity_block(4000, 4) == mem.entity_block(4000, 0) + 4 * 43);  // a Spawn-function entity
        // Without them, the wiki's sizes.
        Memory plain(m, small_config());
        CHECK(plain.entity_stride(4000) == 64);
        CHECK(plain.entity_stride(4101) == 32);
        CHECK(plain.entity_stride(4102) == 32);
    }
}

TEST_CASE("lanes bind independently") {
    Memory mem(Mode::Watch, small_config());
    Memory::Lane lane = mem.make_lane();
    mem.bind(CallbackKind::UpdateParallel, 0);
    mem.bind(CallbackKind::UpdateParallel, 1, lane);
    // Binding the lane leaves the Memory's own views alone.
    CHECK(mem.view(4000)->data == mem.entity_block(4000, 0));
    CHECK(mem.view(4000, lane)->data == mem.entity_block(4000, 1));
    // Shared blocks are shared; Temporary Memory is the lane's own.
    CHECK(mem.view(2000, lane)->data == mem.view(2000)->data);
    REQUIRE(mem.view(10000, lane)->size == 4096);
    CHECK(mem.view(10000, lane)->data == lane.temporary.data());
    CHECK(mem.view(10000)->data == mem.raw(10000).data());
    mem.view(10000, lane)->data[3] = 7.0f;
    CHECK(mem.raw(10000)[3] == 0.0f);
    CHECK(mem.view(12345, lane) == nullptr);
}

TEST_CASE("bind sets access for the callback") {
    Memory mem(Mode::Watch, small_config());
    mem.bind(CallbackKind::UpdateParallel, 0);
    CHECK(mem.view(1002)->readable);
    CHECK_FALSE(mem.view(1002)->writable);
    mem.bind(CallbackKind::UpdateSequential, 0);
    CHECK(mem.view(1002)->writable);
    CHECK(mem.view(4001)->readable);
    CHECK_FALSE(mem.view(4001)->writable);
    CHECK(mem.view(99999) == nullptr);
    CHECK(mem.view(-5) == nullptr);
}

TEST_CASE("spawned entity has no entity data view") {
    Memory mem(Mode::Watch, small_config());
    mem.bind(CallbackKind::Initialize, 3);  // first Spawn-function entity
    CHECK(mem.view(4000)->data != nullptr);  // Entity Memory exists
    CHECK(mem.view(4000)->size == 64);
    for (int32_t id : {4001, 4002, 4003, 4004, 4005, 4006}) {
        auto* v = mem.view(id);
        REQUIRE(v != nullptr);
        CHECK(v->data == nullptr);
        CHECK(v->size == 0);
        CHECK_FALSE(v->readable);
        CHECK_FALSE(v->writable);
    }
    CHECK(mem.entity_block(4001, 3) == nullptr);
    CHECK(mem.entity_block(4000, 4) != nullptr);
    CHECK(mem.entity_block(4000, 5) == nullptr);

    Memory play(Mode::Play, small_config());
    play.bind(CallbackKind::Initialize, 4);
    CHECK(play.view(4004)->data != nullptr);  // Entity Despawn
    CHECK(play.view(4005)->data == nullptr);  // Entity Input
}

TEST_CASE("global callbacks have no entity") {
    Memory mem(Mode::Watch, small_config());
    mem.bind(CallbackKind::UpdateSpawn, -1);
    CHECK(mem.view(4000)->data == nullptr);
    CHECK(mem.view(1001)->data != nullptr);
    CHECK(mem.view(10000)->writable);
}

TEST_CASE("memory moves but does not copy") {
    // A copy would keep views into the original's storage.
    static_assert(!std::is_copy_constructible_v<Memory>);
    static_assert(!std::is_copy_assignable_v<Memory>);
    static_assert(std::is_move_constructible_v<Memory>);
    static_assert(std::is_move_assignable_v<Memory>);
    Memory a(Mode::Watch, small_config());
    a.raw(2000)[0] = 4;
    Memory b(std::move(a));
    CHECK(b.view(2000)->data[0] == 4.0f);
}

}  // TEST_SUITE
