#pragma once

#include <cstdint>
#include <limits>
#include <map>
#include <memory>
#include <optional>
#include <span>
#include <utility>
#include <vector>

#include "data/engine_data.h"
#include "data/resources.h"
#include "modes/gameplay_result.h"
#include "modes/session.h"
#include "modes/session_core.h"
#include "modes/stats.h"
#include "modes/streams.h"
#include "modes/touch.h"
#include "time/frame_time.h"

namespace sr::modes {

struct PlayConfig {
    // Level Option values in the engine configuration's option order (as WatchConfig::options).
    std::vector<float> options;
    Environment env;
    int threads = 0;
    bool optimize = true;
    uint64_t seed = 0x536F6E6F6C7573;
};

// One Play run of a level: the wiki's Play lifecycle, headless (the gameplay loop supplies the clock and the touches).
//
// Entities are the level's entities, then the Spawn-function entities in admission order. The latter have Entity
// Memory and Entity Despawn only: no input, no Entity Info, nothing counted when they despawn.
//
// A callback fault stops the session (fault() says what failed), and nothing runs afterwards.
class PlaySession final : public Session {
public:
    // Compiles the play data and fills the blocks preparation reads (Runtime Environment, Runtime Background, Runtime
    // UI Configuration, Level Option, Engine Rom, Entity Info, Entity Data). Throws data::LoadError as
    // WatchSession::create does (the play data in place of the watch data; play data has no updateSpawn).
    static std::unique_ptr<PlaySession> create(const data::LoadedLevel& level, const PlayConfig& config);
    ~PlaySession() override;
    PlaySession(const PlaySession&) = delete;
    PlaySession& operator=(const PlaySession&) = delete;

    // Preparation, once (later calls do nothing): preprocess in callback order (index ties), then spawnOrder for every
    // level entity in callback order (an entity without the callback has 0), the spawn queue (stable by spawn order),
    // the stat configuration and the all-Perfect maximum, and a snapshot of every block for restart().
    void prepare() override;

    // The next step's Runtime Touch Array; its size is the touch count (at most the memory's touch capacity, 32).
    void set_touches(std::span<const Touch> touches);

    // One update cycle (the wiki's Play update cycle), preparing first if needed: Runtime Update; spawning (shouldSpawn
    // of the queue head until it is falsy, then the Spawn-function queue); initialize; updateSequential; touch (only
    // with a touch count above 0); updateParallel; despawning (terminate, then stats); presentation. The first frame
    // at or past the end runs nothing and returns an empty output with `ended` set. The output stays valid until the
    // next step.
    const FrameOutput& step(const time::FrameTime& frame) override;

    // Restores the post-preparation snapshot of every block, the streams and the spawn requests (what preparation
    // wrote or queued is kept), rebuilds the spawn queue, sets the end back to +inf and clears stats, exports and the
    // judgments. The driver starts the clock again at -kPreRoll and re-submits preprocess_audio().
    void restart();

    // True once a step returned `ended`.
    bool ended() const { return ended_; }
    // The result of the run so far; failed is a life of 0.
    GameplayResult result() const;
    // Every counted input, in counting order.
    const std::vector<StatInput>& judgments() const { return judgments_; }
    const StreamStore& streams() const { return core_.streams; }
    // The value `entity` last exported at `index` (ExportValue), 0 if it never did. The index is named through
    // the archetype's `exports` list.
    float exported(int32_t entity, int32_t index) const;
    // Every ExportValue so far: entity -> (export index -> value).
    const std::map<int32_t, std::map<int32_t, float>>& exports() const { return exports_; }

    // The BGM end, as Watch (the end rule uses it).
    float end_time() const override { return bgm_end_ ? *bgm_end_ : 0.0f; }
    const std::vector<AudioCmd>& preprocess_audio() const override { return preprocess_audio_; }
    const time::TimeTables& times() const override { return core_.times; }
    const ResourceMaps& resources() const override { return core_.maps; }
    const vm::Fault& fault() const override { return core_.fault; }

    bool prepared() const { return prepared_; }
    int32_t entity_count() const { return core_.level_entities; }
    int32_t spawned_count() const { return int32_t(core_.archetype_of.size()) - core_.level_entities; }
    int32_t archetype_of(int32_t entity) const { return core_.archetype_of[size_t(entity)]; }
    float spawn_order(int32_t entity) const { return spawn_order_[size_t(entity)]; }
    bool active(int32_t entity) const { return core_.active[size_t(entity)] != 0; }
    std::span<const float> debug_log() const { return core_.debug_log; }
    // A block's whole storage, as Memory::raw (empty for an unknown id).
    std::span<const float> block(int32_t block_id) { return core_.memory.raw(block_id); }

private:
    PlaySession(vm::Program program, const vm::MemoryConfig& memory_config, vm::Memory memory,
                time::TimeTables times, ResourceMaps maps, std::vector<data::Archetype> archetypes,
                std::vector<int32_t> archetype_of, const PlayConfig& config);

    struct Snapshot {
        std::vector<std::pair<int32_t, std::vector<float>>> blocks;
        std::vector<EntityCounters> counters;
        EntityCounters global_counters;
        std::vector<SpawnRequest> requests;
        StreamStore streams;
    };

    bool begin_frame();
    const FrameOutput& stop();
    void activate(int32_t entity);
    // Applies the stream and export writes the core collected.
    void drain_writes();
    // The stat configuration, the all-Perfect maximum and the input total (after preprocess).
    void build_stats();

    SessionCore core_;
    std::vector<uint8_t> has_input_;  // per level entity
    std::vector<float> spawn_order_;  // per queued entity (0 for Spawn-function entities)
    std::vector<int32_t> queue_;      // level entities by spawn order
    size_t queue_pos_ = 0;            // the head of queue_
    std::vector<int32_t> active_list_;  // active entities in activation order
    std::vector<Touch> touches_;
    StatConfig stat_config_;
    StatState state_, max_state_;
    int32_t input_total_ = 0, completed_ = 0;
    std::vector<StatInput> judgments_;
    std::map<int32_t, std::map<int32_t, float>> exports_;
    std::vector<AudioCmd> preprocess_audio_;
    std::optional<float> bgm_end_;
    float end_ = std::numeric_limits<float>::infinity();  // +inf until every input has despawned
    Snapshot snapshot_;
    bool first_frame_ = true;
    bool ended_ = false;
    bool prepared_ = false;
};

}  // namespace sr::modes
