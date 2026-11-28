#pragma once

#include <cstdint>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "data/engine_data.h"
#include "data/resources.h"
#include "modes/gameplay_result.h"
#include "modes/presentation.h"
#include "modes/replay.h"
#include "modes/streams.h"
#include "modes/session.h"
#include "modes/session_core.h"
#include "modes/session_host.h"
#include "modes/worker_pool.h"
#include "time/frame_time.h"
#include "time/time_tables.h"
#include "vm/interpreter.h"
#include "vm/memory.h"
#include "vm/program.h"

namespace sr::modes {

struct WatchConfig {
    // Level Option values in the engine configuration's option order. Options past the end take the
    // configuration's default; more values than the configuration has options is a load error.
    std::vector<float> options;
    Environment env;
    int threads = 0;  // threads for the parallel callbacks (initialize, updateParallel, terminate); 0: WorkerPool::default_threads()
    bool optimize = true;
    uint64_t seed = 0x536F6E6F6C7573;  // the VM's Random / RandomInteger (watch_random)
    // Test-only (the app never sets it; it must outlive the session): a Play replay. `#JUDGMENT` and `#ACCURACY`
    // imports take the judgment and accuracy of the entity's input (entities without one keep their defaults), the
    // Stream* functions read its streams, Runtime Environment's replay flag is 1, and the stat timeline counts its
    // inputs in time order (entity index breaking ties) instead of perfect_inputs.
    const Replay* replay = nullptr;
};

// One Watch run of a level: the wiki's Watch lifecycle.
//
// Entities are the level's entities (indices [0, entity_count())), then the entities made by the Spawn
// function during preprocess, in call order ([entity_count(), entity_count() + spawned_count())). Those have
// Entity Memory (the Spawn data) and nothing else.
//
// A callback fault stops the session: fault() says what failed (the archetype and callback included), and
// nothing runs afterwards.
//
// Side effects go through a SessionHost: Has* answer from the level's resources (ResourceMaps), Random from the seeded
// generator, and streams are empty (no replay). Entities made by the Spawn function after preprocess (in spawnTime,
// despawnTime or the update cycle) join the spawn queue at the start of the next step: their spawnTime and
// despawnTime run then, in callback order (inference: the wiki runs the spawn system once, but a queued entity
// needs a range), and they may spawn in that same step.
class WatchSession final : public Session {
public:
    // Compiles the watch data and fills the blocks preparation reads: Runtime Environment, Runtime Background (the
    // background item's initial quad for config.env.aspect), Runtime UI Configuration, Level Option, Engine Rom, Entity Info and Entity Data (imports at their index, refs as
    // entity indices, an absent datum as its import's `def` or 0, `#JUDGMENT`/`#ACCURACY` at their
    // defaults unless config.replay gives the entity an input). Throws data::LoadError (vm::CompileError included) when the level
    // names an archetype the watch data doesn't have, the engine can't be compiled or has no updateSpawn, the level
    // has more than kMaxEntities entities, or config.options has more values than the configuration has options.
    static std::unique_ptr<WatchSession> create(const data::LoadedLevel& level, const WatchConfig& config);
    ~WatchSession() override;

    WatchSession(const WatchSession&) = delete;
    WatchSession& operator=(const WatchSession&) = delete;

    // Preparation, once (later calls do nothing): `preprocess` for every level entity in ascending callback
    // order, entity index breaking ties; then `spawnTime` for every queued entity, then `despawnTime`, each in
    // its own callback order. An archetype without the callback has spawn and despawn time 0 (sonolus.py's
    // default). Throws data::LoadError when preprocess spawns past kMaxEntities entities.
    void prepare() override;

    // One update cycle (watch-lifecycle__overview), preparing first if needed: Runtime Update from `frame`;
    // updateSpawn gives the spawn time s; active entities whose range [spawn, despawn) doesn't hold s run terminate
    // (in parallel) and despawn; queued inactive entities whose range holds s spawn and run initialize (in
    // parallel); updateSequential in callback order; updateParallel; presentation. The output stays valid until the
    // next step. After a fault (a spawn past kMaxEntities included) nothing runs and the output is empty.
    const FrameOutput& step(const time::FrameTime& frame) override;

    // The level's end (in step()): a frame at or past the end time runs nothing, and its output is empty with
    // `ended` set. Watch then loops: the driver steps on from time -kPreRoll with `skip` set, which the
    // lifetime system handles like any time jump (entities out of range run terminate, preprocess is not re-run).
    // Music time at which the level ends: the BGM's length - bgmOffset, or without a readable length the latest
    // despawn time plus 1 s.
    float end_time() const override;
    // The audio commands preparation scheduled (the first frame's output holds them once). Watch replays
    // them on each loop, so the driver re-submits them after restarting the mixer.
    const std::vector<AudioCmd>& preprocess_audio() const override { return preprocess_audio_; }

    // The result of the run so far (the stats after every input; after a full run, the run's result). The
    // maximum is every input entity judged Perfect; `failed` is a final life of 0.
    GameplayResult result() const;
    const StreamStore& streams() const { return core_.streams; }

    // Whether a queued entity is active (spawned and not despawned).
    bool active(int32_t entity) const { return core_.active[size_t(entity)] != 0; }
    const ResourceMaps& resources() const override { return core_.maps; }
    // The level's BPM and timescale tables (the frame clock's scaled time uses them).
    const time::TimeTables& times() const override { return core_.times; }

    bool prepared() const { return prepared_; }
    const vm::Fault& fault() const override { return core_.fault; }

    int32_t entity_count() const { return core_.level_entities; }
    int32_t spawned_count() const { return int32_t(core_.archetype_of.size()) - core_.level_entities; }
    int32_t archetype_of(int32_t entity) const { return core_.archetype_of[size_t(entity)]; }
    // One per queued entity (level entities, then spawned ones); 0 until prepare() has run, or until the step
    // that admits a later Spawn-function entity.
    std::span<const float> spawn_times() const { return core_.spawn_times; }
    std::span<const float> despawn_times() const { return core_.despawn_times; }
    // DebugLog values in call order.
    std::span<const float> debug_log() const { return core_.debug_log; }
    // A block's whole storage, as Memory::raw (empty for an unknown id).
    std::span<const float> block(int32_t block_id) { return core_.memory.raw(block_id); }

private:
    WatchSession(vm::Program program, const vm::MemoryConfig& memory_config, vm::Memory memory,
                 time::TimeTables times, ResourceMaps maps, std::vector<data::Archetype> archetypes,
                 std::vector<int32_t> archetype_of, vm::Entry update_spawn, const WatchConfig& config);

    // Runs spawnTime and then despawnTime for queued entities [from, to), each in callback order.
    void spawn_system(int32_t from, int32_t to);
    void set_active(int32_t entity, bool active);
    // Starts a frame's output: clears the last frame's calls (keeping preparation's clips and particle effects on
    // the first frame) and resets the per-frame draw order. Returns false, with the output empty, when nothing may
    // run (a fault).
    bool begin_frame();
    // Empties the output (after a fault, or on the end frame).
    const FrameOutput& stop();
    // Presentation system: the blocks the renderer and UI read, and the stats at music time `time`.
    void present(float time);
    // The stat timeline from the blocks preprocess left, once after preparation.
    void build_stats();

    SessionCore core_;  // the program, memory, queues and callback runners (the session never moves)
    std::vector<uint8_t> has_input_;        // per level entity, for the stats
    StatTimeline stats_;
    const Replay* replay_ = nullptr;
    int32_t input_total_ = 0;  // the level's input entities (the result's total)
    std::vector<AudioCmd> preprocess_audio_;
    bool first_frame_ = true;  // core_.frame still holds preparation's calls
    float last_despawn_ = 0;   // the latest despawn time in the spawn queue
    std::optional<float> bgm_end_;  // music time at which the BGM ends, when its length is readable
    bool prepared_ = false;
};

}  // namespace sr::modes
