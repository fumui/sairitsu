#pragma once

#include <cstdint>
#include <span>
#include <string>
#include <vector>

#include "data/engine_data.h"
#include "data/resources.h"
#include "modes/presentation.h"
#include "modes/stats.h"
#include "modes/streams.h"
#include "time/time_tables.h"
#include "vm/host.h"

namespace sr::modes {

// Engine id -> resource index (into LoadedLevel skin.sprites, effect.clips, particle.effects), -1 = missing.
// Indexed by the engine's id; an id past the end names nothing.
struct ResourceMaps {
    std::vector<int32_t> sprite, clip, effect;
};

// Matches the mode data's sprites, clips and particle effects to the level's resources by name (the first resource
// of a name wins). Ids below 0 or above kMaxResourceId are left out (they name nothing).
ResourceMaps build_resource_maps(const data::LoadedLevel& level, const data::EngineModeData& mode);
inline constexpr int32_t kMaxResourceId = 65535;

// Particle and loop ids, the random stream and DrawCmd::entity key an entity by its index below 65,535 and its
// index + 1 from there; 65,535 stands for the global callbacks (updateSpawn). Levels can have
// more than 65,535 entities (UntitledCharts levels reach 129k).
inline constexpr int32_t kGlobalEntity = 65535;
inline constexpr int32_t entity_key(int32_t entity) {
    return entity < 0 ? kGlobalEntity : (entity < kGlobalEntity ? entity : entity + 1);
}
// The most entities (level entities, then Spawn-function entities) whose instance ids stay integers in int32 range,
// so to_id hands them back; more is a load error.
inline constexpr int32_t kMaxEntities = 294910;

// Per entity (and one for the global callbacks), for the whole session: the counters behind particle ids, loop ids
// and random numbers, and the draw order of the current frame (reset every frame).
struct EntityCounters {
    uint32_t particle = 0, loop = 0, draw = 0;
    uint64_t random = 0;
};

// A Spawn call: the entity joins the spawn queue at the next update cycle's spawning (functions__spawn).
struct SpawnRequest {
    int32_t archetype;
    std::vector<float> data;
};

// Play only: a StreamSet call, and an ExportValue call (the calling entity, the export's index, its value).
struct StreamWrite {
    int32_t id;
    float key, value;
};
struct ExportWrite {
    int32_t entity, index;
    float value;
};

// The side effects of callbacks, in call order. A parallel phase gives every entity its own and merges them in
// entity-index order, so the result never depends on the thread count (Decisions: determinism).
struct Effects {
    std::vector<DrawCmd> draws;
    std::vector<AudioCmd> audio;
    std::vector<ParticleCmd> particles;
    std::vector<SpawnRequest> spawns;
    std::vector<LifeEvent> life;  // AddLifeScheduled
    std::vector<StreamWrite> streams;  // Play: StreamSet
    std::vector<ExportWrite> exports;  // Play: ExportValue
    std::vector<float> log;  // DebugLog values
    std::string error;       // the first call the session faults on, empty if none

    void clear();
};

// Instance ids (particles and looped clips): the n-th positive float that is an integer, n = 1 + key * 256 +
// (counter++ % 256) for the calling entity's entity_key. That is n itself up to 2^24 (every level within 65,535
// entities); past it, the floats' integer spacing grows (2, 4, ..) but ids stay distinct and below 2^31.
float instance_id(int32_t key, uint32_t& counter);

// The VM's Random / RandomInteger source: uniform in [0, 1) with 24 bits, a pure function of the session seed, the
// calling entity's entity_key and that entity's call count. Each entity has its own
// sequence, so values never depend on which thread runs which entity or in what order. SplitMix64 (Steele, Lea and
// Flood 2014) over seed, entity and count. This is only the VM's source; particles have their
// own (particle/particle_system.h).
float watch_random(uint64_t seed, int32_t entity, uint64_t count);

// The vm::Host of a Watch or Play session: one per thread. Bind it to a callback with begin(); the shared parts (time
// tables, resource maps) are only read, so hosts on different threads may run at once as long as their entities
// (and so their counters and Effects) differ. In Play it also records Play, PlayLooped and StopLooped as AudioCmd
// (time 0: start at the next mix callback), StreamSet and ExportValue; those functions are compile errors in Watch.
class SessionHost : public vm::Host {
public:
    // `streams` is the store the Stream* functions read (null: empty). It must outlive the host and not change
    // during a callback.
    SessionHost(data::Mode mode, const time::TimeTables& times, const ResourceMaps& maps, int32_t archetypes,
                uint64_t seed, const StreamStore* streams)
        : mode_(mode), times_(&times), maps_(&maps), archetypes_(archetypes), seed_(seed), streams_(streams) {}

    // The next calls come from `entity` (< 0: a global callback); their side effects go to `out`.
    void begin(int32_t entity, EntityCounters& counters, Effects& out) {
        entity_ = entity < 0 ? kGlobalEntity : entity;
        key_ = entity_key(entity);
        counters_ = &counters;
        out_ = &out;
    }

    void draw(const vm::DrawCall& call) override;
    float audio(const vm::AudioCall& call) override;
    float particle(const vm::ParticleCall& call) override;
    void spawn(int32_t archetype, std::span<const float> data) override;
    float stream(const vm::StreamCall& call) override;
    float time(const vm::TimeCall& call) override;
    float has_skin_sprite(int32_t id) override;
    void add_life_scheduled(float value, float time) override;
    float random() override;
    void debug(const vm::DebugCall& call) override;
    void export_value(int32_t index, float value) override;

private:
    data::Mode mode_;
    const time::TimeTables* times_;
    const ResourceMaps* maps_;
    int32_t archetypes_;
    uint64_t seed_;
    const StreamStore* streams_;
    int32_t entity_ = kGlobalEntity;  // ExportValue's entity
    int32_t key_ = kGlobalEntity;     // entity_key: ids, random, draw order
    EntityCounters* counters_ = nullptr;
    Effects* out_ = nullptr;
};

}  // namespace sr::modes
