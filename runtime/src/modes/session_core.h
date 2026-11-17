#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "data/engine_data.h"
#include "modes/presentation.h"
#include "modes/session_host.h"
#include "modes/streams.h"
#include "modes/worker_pool.h"
#include "time/time_tables.h"
#include "vm/interpreter.h"
#include "vm/memory.h"
#include "vm/program.h"

namespace sr::modes {

// Each level entity's index into `archetypes` (the mode's own, by name). An entity whose archetype the mode does not
// define (Bestdori's levels carry #BPM_CHANGE, which its engine leaves out) keeps its place as an inert entity: one
// archetype with no imports, callbacks or input is appended past the engine's own, which the engine cannot Spawn.
std::vector<int32_t> resolve_archetypes(const std::vector<data::LevelEntity>& entities,
                                        std::vector<data::Archetype>& archetypes);

// The machinery Watch and Play share: the compiled program, block memory, the queued entities (archetype, spawn and
// despawn times, active flag, counters), the callback runners (one thread, or the worker pool) and the merge of their
// side effects into the frame output and the queues below. The owning session (WatchSession, PlaySession) decides
// what to run when; the core only runs it.
//
// The merged side effects are public state the owner reads and drains: `frame` (draws, audio, particles), `requests`
// (Spawn calls), `life_events` (Preprocess only), `stream_writes` and `export_writes` (Play), `debug_log`.
// A core never moves: its hosts point into it.
class SessionCore {
public:
    SessionCore(data::Mode mode, vm::Program program, const vm::MemoryConfig& memory_config, vm::Memory memory,
                time::TimeTables times, ResourceMaps maps, std::vector<data::Archetype> archetypes,
                std::vector<int32_t> archetype_of, vm::Entry update_spawn, uint64_t seed, int threads);
    SessionCore(const SessionCore&) = delete;
    SessionCore& operator=(const SessionCore&) = delete;

    // Runs `kind` of `entity` (-1: the global updateSpawn) on this thread and merges its side effects. Records a
    // fault (and returns 0) when it fails.
    float run(data::CallbackKind kind, int32_t entity);
    // Runs `kind` of every listed entity (ascending indices, all with the callback) on the worker pool, then merges
    // their side effects in list order. Records the first fault in list order.
    void run_parallel(data::CallbackKind kind, const std::vector<int32_t>& entities);
    // Moves one callback's side effects into the frame output, the queues and the debug log; records its error as a
    // fault of `kind` in `archetype`.
    void merge(Effects& effects, data::CallbackKind kind, const std::string& archetype);
    // The candidates (ascending entity indices) whose archetype has `kind`, in ascending index order.
    std::vector<int32_t> with_callback(data::CallbackKind kind, const std::vector<int32_t>& candidates) const;
    // The same in callback order, entity index breaking ties.
    std::vector<int32_t> ordered(data::CallbackKind kind, const std::vector<int32_t>& candidates) const;
    // Adds the requested entities to the spawn queue, growing Entity Memory to hold them (exactly, or with room to
    // spare when `exact` is false). Returns false when that would pass kMaxEntities (nothing is added).
    bool admit(std::vector<SpawnRequest> requests, bool exact);

    bool faulted() const { return fault.kind != vm::Fault::None; }

    data::Mode mode;
    vm::Program program;
    vm::Entry update_spawn;
    vm::MemoryConfig memory_config;
    vm::Memory memory;
    time::TimeTables times;
    ResourceMaps maps;
    std::vector<data::Archetype> archetypes;  // the engine's, then the inert one when an entity needs it
    int32_t level_entities = 0;
    std::vector<int32_t> archetype_of;  // per queued entity
    std::vector<float> spawn_times, despawn_times;
    std::vector<uint8_t> active;           // per queued entity
    std::vector<EntityCounters> counters;  // per queued entity
    EntityCounters global_counters;
    StreamStore streams;  // what the hosts' Stream* reads see
    FrameOutput frame;
    std::vector<SpawnRequest> requests;  // Spawn calls waiting for the next spawning
    std::vector<LifeEvent> life_events;  // AddLifeScheduled calls from preprocess
    std::vector<StreamWrite> stream_writes;
    std::vector<ExportWrite> export_writes;
    std::vector<float> debug_log;
    vm::Fault fault;

private:
    // The hosts refer to times, maps and streams above.
    SessionHost host_;   // this thread's
    Effects effects_;    // this thread's callback, merged after each call
    WorkerPool pool_;
    std::vector<vm::Memory::Lane> lanes_;    // per worker
    std::vector<SessionHost> worker_hosts_;  // per worker
    std::vector<Effects> parallel_effects_;  // per entity of the running parallel phase
    std::vector<vm::Fault> parallel_faults_;
};

}  // namespace sr::modes
