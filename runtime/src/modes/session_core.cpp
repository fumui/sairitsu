#include "modes/session_core.h"

#include <algorithm>
#include <map>
#include <span>
#include <utility>

#include "vm/blocks.h"

namespace sr::modes {

namespace {

constexpr int32_t kEntityMemory = 4000;

const char* callback_name(data::CallbackKind kind) {
    switch (kind) {
        case data::CallbackKind::Preprocess: return "preprocess";
        case data::CallbackKind::SpawnTime: return "spawnTime";
        case data::CallbackKind::DespawnTime: return "despawnTime";
        case data::CallbackKind::Initialize: return "initialize";
        case data::CallbackKind::UpdateSequential: return "updateSequential";
        case data::CallbackKind::UpdateParallel: return "updateParallel";
        case data::CallbackKind::Terminate: return "terminate";
        case data::CallbackKind::UpdateSpawn: return "updateSpawn";
        default: return "callback";
    }
}

// What a fault message names as the archetype of a global callback.
constexpr const char* kGlobalName = "(global)";

void copy_into(std::span<float> to, std::span<const float> from) {
    std::copy_n(from.begin(), std::min(from.size(), to.size()), to.begin());
}

template <class T>
void append(std::vector<T>& to, std::vector<T>& from) {
    to.insert(to.end(), std::make_move_iterator(from.begin()), std::make_move_iterator(from.end()));
}

}  // namespace

std::vector<int32_t> resolve_archetypes(const std::vector<data::LevelEntity>& entities,
                                        std::vector<data::Archetype>& archetypes) {
    std::map<std::string, int32_t> index;  // not unordered_map: its rehash calls the platform ceilf
    for (size_t i = 0; i < archetypes.size(); ++i) index.emplace(archetypes[i].name, int32_t(i));
    const int32_t inert = int32_t(archetypes.size());
    std::vector<int32_t> out(entities.size());
    for (size_t e = 0; e < entities.size(); ++e) {
        const auto it = index.find(entities[e].archetype);
        out[e] = it == index.end() ? inert : it->second;
    }
    if (std::find(out.begin(), out.end(), inert) != out.end()) {
        data::Archetype a;
        a.name = "(undefined archetype)";
        archetypes.push_back(std::move(a));
    }
    return out;
}

SessionCore::SessionCore(data::Mode mode_, vm::Program program_, const vm::MemoryConfig& memory_config_,
                         vm::Memory memory_, time::TimeTables times_, ResourceMaps maps_,
                         std::vector<data::Archetype> archetypes_, std::vector<int32_t> archetype_of_,
                         vm::Entry update_spawn_, uint64_t seed, int threads)
    : mode(mode_), program(std::move(program_)), update_spawn(update_spawn_), memory_config(memory_config_),
      memory(std::move(memory_)), times(std::move(times_)), maps(std::move(maps_)),
      archetypes(std::move(archetypes_)), level_entities(int32_t(archetype_of_.size())),
      archetype_of(std::move(archetype_of_)),
      host_(mode_, times, maps, memory_config.archetype_count, seed, &streams),
      pool_(threads > 0 ? threads : WorkerPool::default_threads()) {
    const size_t n = archetype_of.size();
    spawn_times.assign(n, 0.0f);
    despawn_times.assign(n, 0.0f);
    active.assign(n, 0);
    counters.assign(n, EntityCounters{});
    for (int w = 0; w < pool_.size(); ++w) {
        lanes_.push_back(memory.make_lane());
        worker_hosts_.emplace_back(mode_, times, maps, memory_config.archetype_count, seed, &streams);
    }
}

void SessionCore::merge(Effects& effects, data::CallbackKind kind, const std::string& archetype) {
    if (!effects.error.empty() && !faulted())
        fault = vm::Fault{vm::Fault::Other,
                          effects.error + " (archetype '" + archetype + "', " + callback_name(kind) + ")"};
    append(frame.draws, effects.draws);
    // Watch plays PlayScheduled only from preprocess; every one made in
    // initialize is ignored. The looped functions are kept: untested, and Next RUSH calls them only in
    // preprocess too. Play has no such rule: PlayScheduled is legal in every Play callback.
    if (mode == data::Mode::Watch && kind != data::CallbackKind::Preprocess)
        std::erase_if(effects.audio, [](const AudioCmd& a) { return a.op == AudioCmd::PlayScheduled; });
    append(frame.audio, effects.audio);
    append(frame.particles, effects.particles);
    append(requests, effects.spawns);
    if (kind == data::CallbackKind::Preprocess) append(life_events, effects.life);  // The stat job runs after it
    append(stream_writes, effects.streams);
    append(export_writes, effects.exports);
    append(debug_log, effects.log);
    effects.clear();
}

float SessionCore::run(data::CallbackKind kind, int32_t entity) {
    vm::Entry node = update_spawn;
    const std::string* name = nullptr;
    static const std::string global = kGlobalName;
    if (entity >= 0) {
        const data::Archetype& archetype = archetypes[size_t(archetype_of[size_t(entity)])];
        const auto it = archetype.callbacks.find(kind);
        if (it == archetype.callbacks.end()) return 0;
        node = it->second.node;
        name = &archetype.name;
    } else {
        name = &global;
    }
    host_.begin(entity, entity >= 0 ? counters[size_t(entity)] : global_counters, effects_);
    vm::ExecContext ctx;
    ctx.memory = &memory;
    ctx.host = &host_;
    ctx.callback = kind;
    ctx.entity = entity;
    ctx.archetype = *name;
    const float result = vm::run(program, node, ctx);
    if (ctx.fault.kind != vm::Fault::None) {
        fault = std::move(ctx.fault);
        effects_.clear();
        return 0;
    }
    merge(effects_, kind, *name);
    return faulted() ? 0 : result;
}

void SessionCore::run_parallel(data::CallbackKind kind, const std::vector<int32_t>& entities) {
    const size_t n = entities.size();
    if (n == 0) return;
    if (parallel_effects_.size() < n) parallel_effects_.resize(n);
    parallel_faults_.assign(n, vm::Fault{});
    // Each item touches only its own entity's memory (the access tables limit these callbacks to Entity Memory,
    // Entity Input and Temporary Memory, which is per lane), counters and Effects.
    pool_.run(n, [&](size_t k, int worker) {
        const int32_t e = entities[k];
        const data::Archetype& archetype = archetypes[size_t(archetype_of[size_t(e)])];
        Effects& out = parallel_effects_[k];
        out.clear();
        SessionHost& host = worker_hosts_[size_t(worker)];
        host.begin(e, counters[size_t(e)], out);
        vm::ExecContext ctx;
        ctx.memory = &memory;
        ctx.lane = &lanes_[size_t(worker)];
        ctx.host = &host;
        ctx.callback = kind;
        ctx.entity = e;
        ctx.archetype = archetype.name;
        vm::run(program, archetype.callbacks.at(kind).node, ctx);
        parallel_faults_[k] = std::move(ctx.fault);
    });
    for (size_t k = 0; k < n; ++k) {
        if (parallel_faults_[k].kind != vm::Fault::None) {
            fault = std::move(parallel_faults_[k]);
            return;
        }
        merge(parallel_effects_[k], kind, archetypes[size_t(archetype_of[size_t(entities[k])])].name);
        if (faulted()) return;
    }
}

std::vector<int32_t> SessionCore::with_callback(data::CallbackKind kind,
                                                const std::vector<int32_t>& candidates) const {
    std::vector<int32_t> out;
    for (const int32_t e : candidates)
        if (archetypes[size_t(archetype_of[size_t(e)])].callbacks.count(kind) != 0) out.push_back(e);
    return out;
}

std::vector<int32_t> SessionCore::ordered(data::CallbackKind kind, const std::vector<int32_t>& candidates) const {
    std::vector<int32_t> out = with_callback(kind, candidates);
    const auto order = [&](int32_t e) {
        return archetypes[size_t(archetype_of[size_t(e)])].callbacks.at(kind).order;
    };
    // Stable over ascending indices: the entity index breaks ties.
    std::stable_sort(out.begin(), out.end(), [&](int32_t a, int32_t b) { return order(a) < order(b); });
    return out;
}

bool SessionCore::admit(std::vector<SpawnRequest> new_requests, bool exact) {
    if (new_requests.empty()) return true;
    const size_t total = archetype_of.size() + new_requests.size();
    if (total > size_t(kMaxEntities)) return false;

    const int32_t needed = int32_t(total) - level_entities;
    if (needed > memory_config.spawn_capacity) {
        // Memory is sized at construction: rebuild it with room for the new entities and carry every block over.
        // Entity Memory is laid out by entity index, so the existing entities' slices keep their place.
        vm::MemoryConfig grown_config = memory_config;
        grown_config.spawn_capacity = exact ? needed : std::max(needed, 2 * memory_config.spawn_capacity);
        vm::Memory grown(mode, grown_config);
        for (const vm::BlockDef& def : vm::block_table(mode)) copy_into(grown.raw(def.id), memory.raw(def.id));
        memory = std::move(grown);
        memory_config = grown_config;
    }

    const int32_t memory_size = memory.entity_stride(kEntityMemory);
    for (SpawnRequest& r : new_requests) {
        const int32_t entity = int32_t(archetype_of.size());
        archetype_of.push_back(r.archetype);
        spawn_times.push_back(0);
        despawn_times.push_back(0);
        active.push_back(0);
        counters.push_back(EntityCounters{});
        // Spawn data beyond Entity Memory names no cell and is dropped.
        float* entity_memory = memory.entity_block(kEntityMemory, entity);
        std::copy_n(r.data.begin(), std::min<size_t>(r.data.size(), size_t(memory_size)), entity_memory);
    }
    return true;
}

}  // namespace sr::modes
