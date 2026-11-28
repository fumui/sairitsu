#include "modes/watch_session.h"

#include <algorithm>
#include <map>
#include <string>
#include <utility>

#include "audio/bgm_duration.h"
#include "data/load_error.h"
#include "modes/stats.h"
#include "render/background_layout.h"
#include "vm/blocks.h"
#include "vm/functions.h"

namespace sr::modes {

namespace {

constexpr int32_t kRuntimeEnvironment = 1000;
constexpr int32_t kRuntimeUpdate = 1001;
constexpr int32_t kRuntimeSkinTransform = 1002;
constexpr int32_t kRuntimeParticleTransform = 1003;
constexpr int32_t kRuntimeBackground = 1004;
constexpr int32_t kRuntimeUi = 1005;
constexpr int32_t kRuntimeUiConfiguration = 1006;
constexpr int32_t kLevelOption = 2002;
constexpr int32_t kLevelScore = 2004;
constexpr int32_t kLevelLife = 2005;
constexpr int32_t kEngineRom = 3000;
constexpr int32_t kEntityInput = 4004;
constexpr int32_t kEntityScore = 4005;
constexpr int32_t kEntityLife = 4006;
constexpr int32_t kEntityDataArray = 4101;
constexpr int32_t kEntityInfoArray = 4103;
constexpr int32_t kArchetypeLife = 5000;
constexpr int32_t kArchetypeScore = 5001;
constexpr int32_t kEntityInfoSize = 3;   // index, archetype, state
constexpr int32_t kEntityInputSize = 3;  // target time, bucket, bucket value

// Filled from replay data only (resources__engine-watch-data-archetype); with no replay they keep their defaults.
bool replay_import(const std::string& name) { return name == "#JUDGMENT" || name == "#ACCURACY"; }

void copy_into(std::span<float> to, std::span<const float> from) {
    std::copy_n(from.begin(), std::min(from.size(), to.size()), to.begin());
}

template <size_t N>
void copy_block(float (&to)[N], std::span<const float> from) {
    std::fill(to, to + N, 0.0f);
    std::copy_n(from.begin(), std::min(from.size(), N), to);
}

std::string too_many_entities() {
    return "more than " + std::to_string(kMaxEntities) + " entities (level entities and Spawn-function entities)";
}

}  // namespace

WatchSession::WatchSession(vm::Program program, const vm::MemoryConfig& memory_config, vm::Memory memory,
                           time::TimeTables times, ResourceMaps maps, std::vector<data::Archetype> archetypes,
                           std::vector<int32_t> archetype_of, vm::Entry update_spawn, const WatchConfig& config)
    : core_(data::Mode::Watch, std::move(program), memory_config, std::move(memory), std::move(times),
            std::move(maps), std::move(archetypes), std::move(archetype_of), update_spawn, config.seed,
            config.threads) {
    const size_t n = core_.archetype_of.size();
    has_input_.resize(n);
    for (size_t e = 0; e < n; ++e) has_input_[e] = core_.archetypes[size_t(core_.archetype_of[e])].has_input ? 1 : 0;
}

WatchSession::~WatchSession() = default;

std::unique_ptr<WatchSession> WatchSession::create(const data::LoadedLevel& level, const WatchConfig& config) {
    const data::EngineModeData& watch = level.watch;
    const std::vector<data::LevelEntity>& entities = level.level.entities;

    if (watch.update_spawn < 0) throw data::LoadError("engine.watchData: no updateSpawn callback");
    if (entities.size() > size_t(kMaxEntities))
        throw data::LoadError("level.data: " + std::to_string(entities.size()) + " entities, " + too_many_entities());

    std::vector<data::Archetype> archetypes = watch.archetypes;
    std::vector<int32_t> archetype_of = resolve_archetypes(entities, archetypes);

    // Level Option: the configuration's options, the given values first, defaults for the rest.
    const std::vector<data::EngineOption>& engine_options = level.configuration.options;
    if (config.options.size() > engine_options.size())
        throw data::LoadError("watch config: " + std::to_string(config.options.size()) + " option values for " +
                              std::to_string(engine_options.size()) + " engine options");
    std::vector<float> options(engine_options.size());
    for (size_t i = 0; i < options.size(); ++i)
        options[i] = i < config.options.size() ? config.options[i] : engine_options[i].def;

    vm::Program program = vm::compile(watch, vm::CompileOptions{data::Mode::Watch, config.optimize});
    time::TimeTables times = time::TimeTables::build(level.level, watch);

    vm::MemoryConfig mc;
    mc.entity_count = int32_t(entities.size());
    mc.option_count = int32_t(options.size());
    mc.bucket_count = int32_t(watch.buckets.size());
    mc.archetype_count = int32_t(watch.archetypes.size());
    mc.rom_size = int32_t(level.rom.size());
    mc.entity_memory_length = watch.entity_memory_length.value_or(-1);
    mc.entity_data_length = watch.entity_data_length.value_or(-1);
    mc.entity_shared_memory_length = watch.entity_shared_memory_length.value_or(-1);
    vm::Memory memory(data::Mode::Watch, mc);

    const Environment& env = config.env;
    const float environment[] = {env.debug ? 1.0f : 0.0f, env.aspect, env.audio_offset, env.input_offset,
                                 env.replay || config.replay != nullptr ? 1.0f : 0.0f, env.safe_x_min, env.safe_x_max, env.safe_y_min,
                                 env.safe_y_max};
    copy_into(memory.raw(kRuntimeEnvironment), environment);

    // Runtime Background starts as the background item's quad for this screen, before preprocess
    // can read or overwrite it.
    float background[8];
    render::initial_runtime_background(level.background, level.background_image.width,
                                       level.background_image.height, env.aspect, background);
    copy_into(memory.raw(kRuntimeBackground), background);

    // The engine configuration's UI visibility; whether user UI settings should apply here is
    // part of the built-in UI question.
    const data::Ui& ui = level.configuration.ui;
    const float ui_configuration[] = {
        ui.menu.scale, ui.menu.alpha, ui.judgment.scale, ui.judgment.alpha, ui.combo.scale, ui.combo.alpha,
        ui.primary_metric_visibility.scale, ui.primary_metric_visibility.alpha,
        ui.secondary_metric_visibility.scale, ui.secondary_metric_visibility.alpha, ui.progress.scale,
        ui.progress.alpha};
    copy_into(memory.raw(kRuntimeUiConfiguration), ui_configuration);

    copy_into(memory.raw(kLevelOption), options);
    copy_into(memory.raw(kEngineRom), level.rom);

    // The replay's input of each level entity (the last one if an entity has several), or -1.
    std::vector<int32_t> replay_input(entities.size(), -1);
    if (config.replay != nullptr)
        for (size_t i = 0; i < config.replay->inputs.size(); ++i) {
            const int32_t e = config.replay->inputs[i].entity;
            if (e >= 0 && size_t(e) < entities.size()) replay_input[size_t(e)] = int32_t(i);
        }

    const std::span<float> info = memory.raw(kEntityInfoArray);
    const std::span<float> entity_data = memory.raw(kEntityDataArray);
    const int32_t data_size = memory.entity_stride(kEntityDataArray);
    for (size_t e = 0; e < entities.size(); ++e) {
        const data::Archetype& archetype = archetypes[size_t(archetype_of[e])];
        info[e * kEntityInfoSize + 0] = float(e);
        info[e * kEntityInfoSize + 1] = float(archetype_of[e]);
        info[e * kEntityInfoSize + 2] = 0;  // inactive
        float* slot = entity_data.data() + e * size_t(data_size);
        for (const data::Import& import : archetype.imports) {
            // An index outside Entity Data names no cell; like an out-of-range Set, nothing is written.
            if (import.index < 0 || import.index >= data_size) continue;
            if (replay_import(import.name) && replay_input[e] >= 0) {
                const ReplayInput& in = config.replay->inputs[size_t(replay_input[e])];
                slot[import.index] = import.name == "#JUDGMENT" ? in.judgment : in.accuracy;
                continue;
            }
            const data::EntityDatum* datum = nullptr;
            if (!replay_import(import.name))
                for (const data::EntityDatum& d : entities[e].data)
                    if (d.name == import.name) {
                        datum = &d;
                        break;
                    }
            // Inference, test-only: Play's ExportValue data (the replay's `entities[i].data`) feeds Watch-with-replay
            // imports by name; they fill imports the level doesn't have.
            const float* exported = nullptr;
            if (datum == nullptr && config.replay != nullptr && e < config.replay->exports.size())
                for (const auto& [name, value] : config.replay->exports[e])
                    if (name == import.name) {
                        exported = &value;
                        break;
                    }
            if (datum != nullptr)
                slot[import.index] = datum->is_ref ? float(datum->ref) : datum->value;
            else if (exported != nullptr)
                slot[import.index] = *exported;
            else if (import.def)
                slot[import.index] = *import.def;
        }
    }

    std::unique_ptr<WatchSession> session(new WatchSession(
        std::move(program), mc, std::move(memory), std::move(times), build_resource_maps(level, watch),
        std::move(archetypes), std::move(archetype_of), watch.update_spawn, config));
    session->replay_ = config.replay;
    if (config.replay != nullptr) session->core_.streams.load(config.replay->streams);
    // BGM sample 0 plays at music time -bgmOffset (the end rule is in step()).
    if (const auto length = audio::bgm_length(level.bgm, level.bgm_pcm))
        session->bgm_end_ = float(*length - double(level.level.bgm_offset));
    return session;
}

namespace {

std::vector<int32_t> range(int32_t from, int32_t to) {
    std::vector<int32_t> out;
    for (int32_t e = from; e < to; ++e) out.push_back(e);
    return out;
}

}  // namespace

void WatchSession::spawn_system(int32_t from, int32_t to) {
    const std::vector<int32_t> queued = range(from, to);
    for (const int32_t e : core_.ordered(data::CallbackKind::SpawnTime, queued)) {
        core_.spawn_times[size_t(e)] = core_.run(data::CallbackKind::SpawnTime, e);
        if (core_.faulted()) return;
    }
    for (const int32_t e : core_.ordered(data::CallbackKind::DespawnTime, queued)) {
        core_.despawn_times[size_t(e)] = core_.run(data::CallbackKind::DespawnTime, e);
        if (core_.faulted()) return;
    }
    for (const int32_t e : queued) last_despawn_ = std::max(last_despawn_, core_.despawn_times[size_t(e)]);
}

void WatchSession::set_active(int32_t entity, bool on) {
    core_.active[size_t(entity)] = on ? 1 : 0;
    // Entity Info state (watch-blocks__entity-info); Spawn-function entities have no Entity Info.
    if (entity < core_.level_entities)
        core_.memory.raw(kEntityInfoArray)[size_t(entity) * kEntityInfoSize + 2] = on ? 1.0f : 0.0f;
}

void WatchSession::prepare() {
    if (prepared_) return;
    prepared_ = true;

    for (const int32_t e : core_.ordered(data::CallbackKind::Preprocess, range(0, core_.level_entities))) {
        core_.run(data::CallbackKind::Preprocess, e);
        if (core_.faulted()) return;
    }
    // Spawn system (watch-lifecycle__spawn-system): entities made by the Spawn function join the spawn queue.
    std::vector<SpawnRequest> spawned = std::move(core_.requests);
    core_.requests.clear();
    if (!core_.admit(std::move(spawned), true)) throw data::LoadError("preprocess: " + too_many_entities());
    spawn_system(0, int32_t(core_.archetype_of.size()));
    if (core_.faulted()) return;
    // Spawn calls from spawnTime / despawnTime stay in core_.requests: they join the queue at the first step.
    build_stats();
    // A loop replays the clips preparation scheduled (preprocess itself does not run again).
    preprocess_audio_ = core_.frame.audio;
}

const FrameOutput& WatchSession::stop() {
    core_.frame.draws.clear();
    core_.frame.audio.clear();
    core_.frame.particles.clear();
    return core_.frame;
}

bool WatchSession::begin_frame() {
    if (!prepared_) prepare();
    // The first frame keeps the clips and particle effects of preparation (sonolus.py: clips "may be scheduled in
    // preprocess"); draws belong to the frame that makes them, so preparation's are dropped.
    core_.frame.draws.clear();
    if (!first_frame_) {
        core_.frame.audio.clear();
        core_.frame.particles.clear();
    }
    first_frame_ = false;
    if (core_.faulted()) {
        stop();
        return false;
    }
    for (EntityCounters& c : core_.counters) c.draw = 0;
    core_.global_counters.draw = 0;
    return true;
}

void WatchSession::build_stats() {
    const auto first = [&](int32_t id, size_t n) {
        const std::span<const float> b = core_.memory.raw(id);
        return std::vector<float>(b.begin(), b.begin() + std::ptrdiff_t(std::min(n, b.size())));
    };
    const size_t n = size_t(core_.level_entities), archetypes = core_.archetypes.size();
    StatConfig config = stat_config(core_.memory.raw(kLevelScore), core_.memory.raw(kLevelLife), first(kEntityScore, n),
                                    first(kEntityLife, n * 4), first(kArchetypeScore, archetypes),
                                    first(kArchetypeLife, archetypes * 4));
    const std::span<const float> input = core_.memory.raw(kEntityInput);
    std::vector<StatInput> perfect =
        perfect_inputs(input.first(std::min(input.size(), n * size_t(kEntityInputSize))),
                       std::span<const uint8_t>(has_input_).first(n), std::span<const int32_t>(core_.archetype_of).first(n));
    input_total_ = int32_t(perfect.size());
    if (replay_ == nullptr) {
        stats_ = StatTimeline(std::move(config), std::move(perfect), core_.life_events);
        return;
    }
    // The replay's inputs in time order, entity index breaking ties; the archetype is the entity's. The maximum is
    // the level's inputs all Perfect (MaxStat).
    std::vector<StatInput> inputs;
    for (const ReplayInput& in : replay_->inputs)
        if (in.entity >= 0 && size_t(in.entity) < n)
            inputs.push_back(StatInput{in.entity, core_.archetype_of[size_t(in.entity)], in.time, in.judgment, in.accuracy});
    std::stable_sort(inputs.begin(), inputs.end(), [](const StatInput& a, const StatInput& b) {
        return a.time != b.time ? a.time < b.time : a.entity < b.entity;
    });
    const StatState max = StatTimeline(config, std::move(perfect), core_.life_events).final_state();
    stats_ = StatTimeline(std::move(config), std::move(inputs), core_.life_events, max);
}

GameplayResult WatchSession::result() const {
    const StatState final = stats_.final_state();
    // Failed is a final life of 0 (it can't go below).
    return gameplay_result(final, stats_.max_state(), input_total_, final.life <= 0);
}

void WatchSession::present(float time) {
    copy_block(core_.frame.skin_transform, core_.memory.raw(kRuntimeSkinTransform));
    copy_block(core_.frame.particle_transform, core_.memory.raw(kRuntimeParticleTransform));
    copy_block(core_.frame.background, core_.memory.raw(kRuntimeBackground));
    copy_block(core_.frame.ui, core_.memory.raw(kRuntimeUi));
    // The stats are read off the timeline built after preprocess, at the frame's time.
    core_.frame.stats = stats_.at(time);
}

float WatchSession::end_time() const { return bgm_end_ ? *bgm_end_ : last_despawn_ + 1.0f; }

const FrameOutput& WatchSession::step(const time::FrameTime& t) {
    if (!begin_frame()) return core_.frame;
    // Watch ends on the first frame whose time reaches the BGM's length -
    // bgmOffset, before any system runs, so that frame draws nothing; it then skips to
    // music time -2 s and plays on (a loop), without re-running preprocess or terminating the entities the skip's
    // lifetime system keeps.
    // Without a readable BGM length the end is the latest despawn time plus 1 s.
    if (t.time >= end_time()) {
        stop();
        core_.frame.ended = true;
        return core_.frame;
    }

    const float update[] = {t.time, t.delta, t.scaled, t.skip ? 1.0f : 0.0f};
    copy_into(core_.memory.raw(kRuntimeUpdate), update);

    // Lifetime system (watch-lifecycle__lifetime-system). Spawn calls since the last cycle join the spawn queue
    // first (functions__spawn: "spawned on next update cycle's spawning system").
    const int32_t before = int32_t(core_.archetype_of.size());
    std::vector<SpawnRequest> requests = std::move(core_.requests);
    core_.requests.clear();
    if (!core_.admit(std::move(requests), false)) {
        core_.fault = vm::Fault{vm::Fault::Other, "Spawn: " + too_many_entities()};
        return stop();
    }
    spawn_system(before, int32_t(core_.archetype_of.size()));
    if (core_.faulted()) return stop();

    const float s = core_.run(data::CallbackKind::UpdateSpawn, -1);
    if (core_.faulted()) return stop();
    const auto in_range = [&](size_t e) { return core_.spawn_times[e] <= s && s < core_.despawn_times[e]; };

    std::vector<int32_t> leaving, entering, active;
    for (size_t e = 0; e < core_.active.size(); ++e) {
        if (core_.active[e] && !in_range(e)) leaving.push_back(int32_t(e));
        if (!core_.active[e] && in_range(e)) entering.push_back(int32_t(e));
    }
    core_.run_parallel(data::CallbackKind::Terminate, core_.with_callback(data::CallbackKind::Terminate, leaving));
    if (core_.faulted()) return stop();
    for (const int32_t e : leaving) set_active(e, false);
    for (const int32_t e : entering) set_active(e, true);
    core_.run_parallel(data::CallbackKind::Initialize, core_.with_callback(data::CallbackKind::Initialize, entering));
    if (core_.faulted()) return stop();

    for (size_t e = 0; e < core_.active.size(); ++e)
        if (core_.active[e]) active.push_back(int32_t(e));

    // Sequential update system, then parallel update system.
    for (const int32_t e : core_.ordered(data::CallbackKind::UpdateSequential, active)) {
        core_.run(data::CallbackKind::UpdateSequential, e);
        if (core_.faulted()) return stop();
    }
    core_.run_parallel(data::CallbackKind::UpdateParallel, core_.with_callback(data::CallbackKind::UpdateParallel, active));
    if (core_.faulted()) return stop();

    // Presentation system: the calls above, and the blocks as they stand now.
    present(t.time);
    core_.frame.ended = false;
    return core_.frame;
}

}  // namespace sr::modes
