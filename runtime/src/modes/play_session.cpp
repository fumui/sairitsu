#include "modes/play_session.h"

#include <algorithm>
#include <cmath>
#include <map>
#include <string>
#include <utility>

#include "audio/bgm_duration.h"
#include "data/load_error.h"
#include "render/background_layout.h"
#include "vm/blocks.h"
#include "vm/convert.h"
#include "vm/functions.h"

namespace sr::modes {

namespace {

constexpr int32_t kRuntimeEnvironment = 1000;
constexpr int32_t kRuntimeUpdate = 1001;
constexpr int32_t kRuntimeTouchArray = 1002;
constexpr int32_t kRuntimeSkinTransform = 1003;
constexpr int32_t kRuntimeParticleTransform = 1004;
constexpr int32_t kRuntimeBackground = 1005;
constexpr int32_t kRuntimeUi = 1006;
constexpr int32_t kRuntimeUiConfiguration = 1007;
constexpr int32_t kLevelOption = 2002;
constexpr int32_t kLevelScore = 2004;
constexpr int32_t kLevelLife = 2005;
constexpr int32_t kEngineRom = 3000;
constexpr int32_t kEntityDespawn = 4004;
constexpr int32_t kEntityInput = 4005;
constexpr int32_t kEntityScore = 4006;
constexpr int32_t kEntityLife = 4007;
constexpr int32_t kEntityDataArray = 4101;
constexpr int32_t kEntityInfoArray = 4103;
constexpr int32_t kArchetypeLife = 5000;
constexpr int32_t kArchetypeScore = 5001;
constexpr int32_t kEntityInfoSize = 3;  // index, archetype, state
constexpr int32_t kTouchSize = 15;

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

std::vector<int32_t> range(int32_t from, int32_t to) {
    std::vector<int32_t> out;
    for (int32_t e = from; e < to; ++e) out.push_back(e);
    return out;
}

}  // namespace

PlaySession::PlaySession(vm::Program program, const vm::MemoryConfig& memory_config, vm::Memory memory,
                         time::TimeTables times, ResourceMaps maps, std::vector<data::Archetype> archetypes,
                         std::vector<int32_t> archetype_of, const PlayConfig& config)
    : core_(data::Mode::Play, std::move(program), memory_config, std::move(memory), std::move(times), std::move(maps),
            std::move(archetypes), std::move(archetype_of), /*update_spawn=*/-1, config.seed, config.threads) {
    const size_t n = core_.archetype_of.size();
    has_input_.resize(n);
    for (size_t e = 0; e < n; ++e) has_input_[e] = core_.archetypes[size_t(core_.archetype_of[e])].has_input ? 1 : 0;
    spawn_order_.assign(n, 0.0f);
}

PlaySession::~PlaySession() = default;

std::unique_ptr<PlaySession> PlaySession::create(const data::LoadedLevel& level, const PlayConfig& config) {
    const data::EngineModeData& play = level.play;
    const std::vector<data::LevelEntity>& entities = level.level.entities;

    if (entities.size() > size_t(kMaxEntities))
        throw data::LoadError("level.data: " + std::to_string(entities.size()) + " entities, " + too_many_entities());

    std::vector<data::Archetype> archetypes = play.archetypes;
    std::vector<int32_t> archetype_of = resolve_archetypes(entities, archetypes);

    const std::vector<data::EngineOption>& engine_options = level.configuration.options;
    if (config.options.size() > engine_options.size())
        throw data::LoadError("play config: " + std::to_string(config.options.size()) + " option values for " +
                              std::to_string(engine_options.size()) + " engine options");
    std::vector<float> options(engine_options.size());
    for (size_t i = 0; i < options.size(); ++i)
        options[i] = i < config.options.size() ? config.options[i] : engine_options[i].def;

    vm::Program program = vm::compile(play, vm::CompileOptions{data::Mode::Play, config.optimize});
    time::TimeTables times = time::TimeTables::build(level.level, play);

    vm::MemoryConfig mc;
    mc.entity_count = int32_t(entities.size());
    mc.option_count = int32_t(options.size());
    mc.bucket_count = int32_t(play.buckets.size());
    mc.archetype_count = int32_t(play.archetypes.size());
    mc.rom_size = int32_t(level.rom.size());
    mc.entity_memory_length = play.entity_memory_length.value_or(-1);
    mc.entity_data_length = play.entity_data_length.value_or(-1);
    mc.entity_shared_memory_length = play.entity_shared_memory_length.value_or(-1);
    vm::Memory memory(data::Mode::Play, mc);

    const Environment& env = config.env;
    // Index 4 is Multiplayer (play-blocks__runtime-environment): always 0.
    const float environment[] = {env.debug ? 1.0f : 0.0f, env.aspect, env.audio_offset, env.input_offset, 0.0f,
                                 env.safe_x_min, env.safe_x_max, env.safe_y_min, env.safe_y_max};
    copy_into(memory.raw(kRuntimeEnvironment), environment);

    float background[8];
    render::initial_runtime_background(level.background, level.background_image.width,
                                       level.background_image.height, env.aspect, background);
    copy_into(memory.raw(kRuntimeBackground), background);

    // Play's UI configuration is the first five (scale, alpha) pairs; Watch's sixth (progress) has no cell here.
    const data::Ui& ui = level.configuration.ui;
    const float ui_configuration[] = {
        ui.menu.scale, ui.menu.alpha, ui.judgment.scale, ui.judgment.alpha, ui.combo.scale, ui.combo.alpha,
        ui.primary_metric_visibility.scale, ui.primary_metric_visibility.alpha,
        ui.secondary_metric_visibility.scale, ui.secondary_metric_visibility.alpha};
    copy_into(memory.raw(kRuntimeUiConfiguration), ui_configuration);

    copy_into(memory.raw(kLevelOption), options);
    copy_into(memory.raw(kEngineRom), level.rom);

    const std::span<float> info = memory.raw(kEntityInfoArray);
    const std::span<float> entity_data = memory.raw(kEntityDataArray);
    const int32_t data_size = memory.entity_stride(kEntityDataArray);
    for (size_t e = 0; e < entities.size(); ++e) {
        const data::Archetype& archetype = archetypes[size_t(archetype_of[e])];
        info[e * kEntityInfoSize + 0] = float(e);
        info[e * kEntityInfoSize + 1] = float(archetype_of[e]);
        info[e * kEntityInfoSize + 2] = 0;  // waiting
        float* slot = entity_data.data() + e * size_t(data_size);
        for (const data::Import& import : archetype.imports) {
            if (import.index < 0 || import.index >= data_size) continue;
            const data::EntityDatum* datum = nullptr;
            for (const data::EntityDatum& d : entities[e].data)
                if (d.name == import.name) {
                    datum = &d;
                    break;
                }
            if (datum != nullptr)
                slot[import.index] = datum->is_ref ? float(datum->ref) : datum->value;
            else if (import.def)
                slot[import.index] = *import.def;
        }
    }

    std::unique_ptr<PlaySession> session(new PlaySession(std::move(program), mc, std::move(memory), std::move(times),
                                                          build_resource_maps(level, play), std::move(archetypes),
                                                          std::move(archetype_of), config));
    // End_time() is the BGM end, as in Watch .
    if (const auto length = audio::bgm_length(level.bgm, level.bgm_pcm))
        session->bgm_end_ = float(double(level.level.bgm_offset) + *length);
    return session;
}

void PlaySession::build_stats() {
    const auto first = [&](int32_t id, size_t n) {
        const std::span<const float> b = core_.memory.raw(id);
        return std::vector<float>(b.begin(), b.begin() + std::ptrdiff_t(std::min(n, b.size())));
    };
    const size_t n = size_t(core_.level_entities), archetypes = core_.archetypes.size();
    stat_config_ = stat_config(core_.memory.raw(kLevelScore), core_.memory.raw(kLevelLife), first(kEntityScore, n),
                               first(kEntityLife, n * 4), first(kArchetypeScore, archetypes),
                               first(kArchetypeLife, archetypes * 4));
    // The maximum (MaxStat) is every input entity judged Perfect with accuracy 0.
    max_state_ = initial_stat(stat_config_);
    input_total_ = 0;
    for (size_t e = 0; e < n; ++e) {
        if (has_input_[e] == 0) continue;
        stat_update(max_state_, stat_config_, StatInput{int32_t(e), core_.archetype_of[e], 0, 1, 0});
        ++input_total_;
    }
    state_ = initial_stat(stat_config_);
}

void PlaySession::drain_writes() {
    for (const StreamWrite& w : core_.stream_writes) core_.streams.set(w.id, w.key, w.value);
    for (const ExportWrite& w : core_.export_writes) exports_[w.entity][w.index] = w.value;
    core_.stream_writes.clear();
    core_.export_writes.clear();
}

float PlaySession::exported(int32_t entity, int32_t index) const {
    const auto e = exports_.find(entity);
    if (e == exports_.end()) return 0;
    const auto v = e->second.find(index);
    return v == e->second.end() ? 0 : v->second;
}

void PlaySession::prepare() {
    if (prepared_) return;
    prepared_ = true;

    const std::vector<int32_t> level = range(0, core_.level_entities);
    for (const int32_t e : core_.ordered(data::CallbackKind::Preprocess, level)) {
        core_.run(data::CallbackKind::Preprocess, e);
        if (core_.faulted()) return;
    }
    // Spawn ordering system: spawnOrder in callback order; the queue keeps the level order among equal values.
    for (const int32_t e : core_.ordered(data::CallbackKind::SpawnOrder, level)) {
        spawn_order_[size_t(e)] = core_.run(data::CallbackKind::SpawnOrder, e);
        if (core_.faulted()) return;
    }
    queue_ = level;
    const auto key = [&](int32_t e) {
        const float v = spawn_order_[size_t(e)];
        return std::isnan(v) ? 0.0f : v;  // keeps the comparison a strict weak ordering
    };
    std::stable_sort(queue_.begin(), queue_.end(), [&](int32_t a, int32_t b) { return key(a) < key(b); });
    queue_pos_ = 0;

    build_stats();
    drain_writes();
    // Spawn calls made by preprocess stay in core_.requests: they join the queue at the first step.
    preprocess_audio_ = core_.frame.audio;

    // The post-preparation state every restart returns to.
    for (const vm::BlockDef& def : vm::block_table(data::Mode::Play)) {
        const std::span<const float> raw = core_.memory.raw(def.id);
        snapshot_.blocks.emplace_back(def.id, std::vector<float>(raw.begin(), raw.end()));
    }
    snapshot_.counters = core_.counters;
    snapshot_.global_counters = core_.global_counters;
    snapshot_.requests = core_.requests;
    snapshot_.streams = core_.streams;
}

void PlaySession::set_touches(std::span<const Touch> touches) {
    const size_t capacity = size_t(core_.memory_config.touch_capacity);
    touches_.assign(touches.begin(), touches.begin() + std::ptrdiff_t(std::min(touches.size(), capacity)));
}

const FrameOutput& PlaySession::stop() {
    core_.frame.draws.clear();
    core_.frame.audio.clear();
    core_.frame.particles.clear();
    return core_.frame;
}

bool PlaySession::begin_frame() {
    if (!prepared_) prepare();
    // The first frame keeps the clips and particle effects of preparation; draws belong to the frame that makes them.
    core_.frame.draws.clear();
    if (!first_frame_) {
        core_.frame.audio.clear();
        core_.frame.particles.clear();
    }
    first_frame_ = false;
    core_.frame.haptic = 0;  // Reset every step
    if (core_.faulted()) {
        stop();
        return false;
    }
    for (EntityCounters& c : core_.counters) c.draw = 0;
    core_.global_counters.draw = 0;
    return true;
}

void PlaySession::activate(int32_t entity) {
    core_.active[size_t(entity)] = 1;
    active_list_.push_back(entity);
    if (entity < core_.level_entities)  // Entity Info state: 1 = active
        core_.memory.raw(kEntityInfoArray)[size_t(entity) * kEntityInfoSize + 2] = 1.0f;
}

GameplayResult PlaySession::result() const {
    // Failed is a final life of 0 (it can't go below).
    return gameplay_result(state_, max_state_, input_total_, state_.life <= 0);
}

const FrameOutput& PlaySession::step(const time::FrameTime& t) {
    if (!begin_frame()) return core_.frame;
    // The first frame at or past the end runs nothing; Play doesn't loop.
    if (t.time >= end_) {
        stop();
        core_.frame.ended = true;
        ended_ = true;
        return core_.frame;
    }

    // Runtime Update: time, delta, scaled, touch count, skip.
    const size_t touch_count = touches_.size();
    const float update[] = {t.time, t.delta, t.scaled, float(touch_count), t.skip ? 1.0f : 0.0f};
    copy_into(core_.memory.raw(kRuntimeUpdate), update);
    {
        const std::span<float> block = core_.memory.raw(kRuntimeTouchArray);
        std::fill(block.begin(), block.end(), 0.0f);
        for (size_t i = 0; i < touches_.size(); ++i) {
            const Touch& k = touches_[i];
            const float v[kTouchSize] = {k.id, k.started ? 1.0f : 0.0f, k.ended ? 1.0f : 0.0f, k.t, k.st, k.x, k.y,
                                         k.sx, k.sy, k.dx, k.dy, k.vx, k.vy, k.vr, k.vw};
            copy_into(block.subspan(i * kTouchSize), v);
        }
        touches_.clear();  // set_touches serves the next step only
    }

    // Spawning system. Spawn calls since the last cycle join the Spawn-function queue (functions__spawn: "spawned on
    // next update cycle's spawning system").
    const int32_t before = int32_t(core_.archetype_of.size());
    {
        std::vector<SpawnRequest> requests = std::move(core_.requests);
        core_.requests.clear();
        if (!core_.admit(std::move(requests), false)) {
            core_.fault = vm::Fault{vm::Fault::Other, "Spawn: " + too_many_entities()};
            return stop();
        }
    }
    const int32_t after = int32_t(core_.archetype_of.size());
    spawn_order_.resize(size_t(after), 0.0f);
    std::vector<int32_t> entering;
    while (queue_pos_ < queue_.size()) {
        const int32_t head = queue_[queue_pos_];
        // An archetype without shouldSpawn spawns at once (sonolus.py's default is True).
        const data::Archetype& a = core_.archetypes[size_t(core_.archetype_of[size_t(head)])];
        const bool should = a.callbacks.count(data::CallbackKind::ShouldSpawn) == 0 ||
                            core_.run(data::CallbackKind::ShouldSpawn, head) != 0.0f;
        if (core_.faulted()) return stop();
        if (!should) break;
        ++queue_pos_;
        entering.push_back(head);
        activate(head);
    }
    for (int32_t e = before; e < after; ++e) {
        entering.push_back(e);
        activate(e);
    }

    // Initialization system.
    std::sort(entering.begin(), entering.end());
    core_.run_parallel(data::CallbackKind::Initialize, core_.with_callback(data::CallbackKind::Initialize, entering));
    if (core_.faulted()) return stop();

    // Callbacks see the active entities in ascending index order (callback order breaks ties as in Watch).
    std::vector<int32_t> active = active_list_;
    std::sort(active.begin(), active.end());

    for (const int32_t e : core_.ordered(data::CallbackKind::UpdateSequential, active)) {
        core_.run(data::CallbackKind::UpdateSequential, e);
        if (core_.faulted()) return stop();
    }
    if (touch_count > 0) {
        for (const int32_t e : core_.ordered(data::CallbackKind::Touch, active)) {
            core_.run(data::CallbackKind::Touch, e);
            if (core_.faulted()) return stop();
        }
    }
    core_.run_parallel(data::CallbackKind::UpdateParallel, core_.with_callback(data::CallbackKind::UpdateParallel, active));
    if (core_.faulted()) return stop();

    // Despawning system. While the end is +inf and every input entity has despawned, the end is set first.
    if (std::isinf(end_) && completed_ >= input_total_) end_ = std::max(t.time + 1.0f, end_time());
    std::vector<int32_t> leaving;
    std::vector<uint8_t> is_leaving(core_.active.size(), 0);
    for (const int32_t e : active) {
        const float* flag = core_.memory.entity_block(kEntityDespawn, e);
        if (flag != nullptr && *flag != 0.0f) {
            leaving.push_back(e);
            is_leaving[size_t(e)] = 1;
        }
    }
    core_.run_parallel(data::CallbackKind::Terminate, core_.with_callback(data::CallbackKind::Terminate, leaving));
    if (core_.faulted()) return stop();
    // Counted after terminate, in reverse activation order, at this frame's time with Entity Input 0 and 1.
    int32_t haptic = 0;
    for (size_t i = active_list_.size(); i-- > 0;) {
        const int32_t e = active_list_[i];
        if (is_leaving[size_t(e)] == 0 || e >= core_.level_entities || has_input_[size_t(e)] == 0) continue;
        const float* in = core_.memory.entity_block(kEntityInput, e);
        if (in == nullptr) continue;
        const StatInput input{e, core_.archetype_of[size_t(e)], t.time, in[0], in[1]};
        stat_update(state_, stat_config_, input);
        judgments_.push_back(input);
        ++completed_;
        haptic = std::max(haptic, vm::to_index(in[4]));
    }
    core_.frame.haptic = uint8_t(std::clamp(haptic, 0, 255));
    for (const int32_t e : leaving) {
        core_.active[size_t(e)] = 0;
        if (e < core_.level_entities)  // Entity Info state: 2 = despawned
            core_.memory.raw(kEntityInfoArray)[size_t(e) * kEntityInfoSize + 2] = 2.0f;
    }
    std::erase_if(active_list_, [&](int32_t e) { return is_leaving[size_t(e)] != 0; });

    drain_writes();

    // Presentation system.
    copy_block(core_.frame.skin_transform, core_.memory.raw(kRuntimeSkinTransform));
    copy_block(core_.frame.particle_transform, core_.memory.raw(kRuntimeParticleTransform));
    copy_block(core_.frame.background, core_.memory.raw(kRuntimeBackground));
    copy_block(core_.frame.ui, core_.memory.raw(kRuntimeUi));
    core_.frame.stats = stats_of(state_, max_state_);
    core_.frame.ended = false;
    return core_.frame;
}

void PlaySession::restart() {
    if (!prepared_) prepare();
    if (core_.faulted()) return;
    // Every block back to the post-preparation snapshot. Blocks that grew for Spawn-function entities are
    // zeroed first (their snapshot is shorter; the grown tail starts at 0 as it did the first time).
    for (const auto& [id, values] : snapshot_.blocks) {
        const std::span<float> raw = core_.memory.raw(id);
        std::fill(raw.begin(), raw.end(), 0.0f);
        copy_into(raw, values);
    }
    const size_t n = size_t(core_.level_entities);
    core_.archetype_of.resize(n);
    core_.spawn_times.assign(n, 0.0f);
    core_.despawn_times.assign(n, 0.0f);
    core_.active.assign(n, 0);
    core_.counters = snapshot_.counters;
    core_.global_counters = snapshot_.global_counters;
    core_.requests = snapshot_.requests;
    core_.life_events.clear();
    core_.stream_writes.clear();
    core_.export_writes.clear();
    core_.debug_log.clear();
    core_.streams = snapshot_.streams;  // what preparation wrote stays
    core_.frame.draws.clear();
    core_.frame.audio.clear();
    core_.frame.particles.clear();
    core_.frame.haptic = 0;
    core_.frame.ended = false;
    spawn_order_.resize(n);
    active_list_.clear();
    touches_.clear();
    queue_pos_ = 0;  // queue_ is the post-preparation order of level entities and never changes
    end_ = std::numeric_limits<float>::infinity();
    state_ = initial_stat(stat_config_);
    completed_ = 0;
    judgments_.clear();
    exports_.clear();
    first_frame_ = false;  // the driver re-submits preprocess_audio() itself
    ended_ = false;
}

}  // namespace sr::modes
