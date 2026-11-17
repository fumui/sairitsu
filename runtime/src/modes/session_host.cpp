#include "modes/session_host.h"

#include <bit>
#include <map>
#include <string>

#include "vm/convert.h"

namespace sr::modes {

namespace {

// Engine id -> index of the resource with the same name; std::map, not unordered_map (its rehash calls ceilf).
template <class Resource>
std::vector<int32_t> map_by_name(const std::vector<data::NamedId>& ids, const std::vector<Resource>& resources) {
    std::map<std::string, int32_t> index;
    for (size_t i = 0; i < resources.size(); ++i) index.emplace(resources[i].name, int32_t(i));
    std::vector<int32_t> out;
    for (const data::NamedId& id : ids) {
        if (id.id < 0 || id.id > kMaxResourceId) continue;
        if (size_t(id.id) >= out.size()) out.resize(size_t(id.id) + 1, -1);
        const auto it = index.find(id.name);
        if (it != index.end()) out[size_t(id.id)] = it->second;
    }
    return out;
}

// The resource an id argument names, or -1 (only an exact integer id names anything).
int32_t resolve(const std::vector<int32_t>& map, float id) {
    const int32_t i = vm::to_id(id);
    return i == vm::kNoId || i < 0 || size_t(i) >= map.size() ? -1 : map[size_t(i)];
}

uint64_t splitmix64(uint64_t x) {
    x += 0x9E3779B97F4A7C15ull;
    x = (x ^ (x >> 30)) * 0xBF58476D1CE4E5B9ull;
    x = (x ^ (x >> 27)) * 0x94D049BB133111EBull;
    return x ^ (x >> 31);
}

DrawCmd::Curve curve_of(vm::Fn fn) {
    switch (fn) {
        case vm::Fn::DrawCurvedB: return DrawCmd::B;
        case vm::Fn::DrawCurvedT: return DrawCmd::T;
        case vm::Fn::DrawCurvedL: return DrawCmd::L;
        case vm::Fn::DrawCurvedR: return DrawCmd::R;
        case vm::Fn::DrawCurvedBT: return DrawCmd::BT;
        case vm::Fn::DrawCurvedLR: return DrawCmd::LR;
        default: return DrawCmd::None;
    }
}

void corners(std::span<const float> a, size_t at, float (&x)[4], float (&y)[4]) {
    for (size_t i = 0; i < 4; ++i) {
        x[i] = a[at + 2 * i];
        y[i] = a[at + 2 * i + 1];
    }
}

}  // namespace

ResourceMaps build_resource_maps(const data::LoadedLevel& level, const data::EngineModeData& mode) {
    ResourceMaps maps;
    maps.sprite = map_by_name(mode.sprites, level.skin.sprites);
    maps.clip = map_by_name(mode.clips, level.effect.clips);
    maps.effect = map_by_name(mode.effects, level.particle.effects);
    return maps;
}

void Effects::clear() {
    draws.clear();
    audio.clear();
    particles.clear();
    spawns.clear();
    life.clear();
    streams.clear();
    exports.clear();
    log.clear();
    error.clear();
}

namespace {
// The n-th positive integer float (n >= 1): n up to 2^24, then each next float, all of which are integers.
constexpr float nth_integer_float(uint64_t n) {
    constexpr uint64_t kExact = uint64_t(1) << 24;
    return n <= kExact ? float(n) : std::bit_cast<float>(uint32_t(0x4B800000u + (n - kExact)));  // 0x4B800000 = 2^24
}
// The last id of the last entity is still below 2^31, so to_id (int32) hands every id back.
static_assert(nth_integer_float(1 + uint64_t(entity_key(kMaxEntities - 1)) * 256 + 255) < 2147483648.0f);
static_assert(nth_integer_float(1 + uint64_t(entity_key(kMaxEntities)) * 256 + 255) >= 2147483648.0f);
}  // namespace

float instance_id(int32_t key, uint32_t& counter) {
    const uint32_t n = counter++ % 256u;
    return nth_integer_float(1 + uint64_t(uint32_t(key)) * 256 + n);
}

float watch_random(uint64_t seed, int32_t entity, uint64_t count) {
    const uint64_t z = splitmix64(splitmix64(seed ^ (uint64_t(uint32_t(entity)) << 32)) ^ count);
    return float(z >> 40) * (1.0f / 16777216.0f);  // 24 bits: exact in float32 and below 1
}

void SessionHost::draw(const vm::DrawCall& c) {
    const int32_t sprite = resolve(maps_->sprite, c.args[0]);
    if (sprite < 0) return;
    const DrawCmd::Curve curve = curve_of(c.fn);
    // Arguments: id, x1, y1 .. x4, y4, z1, a, then the curve's n and control points, then z2..z4 if given.
    const size_t control = curve == DrawCmd::None ? 0 : (curve == DrawCmd::BT || curve == DrawCmd::LR ? 4 : 2);
    const size_t extra = curve == DrawCmd::None ? 0 : 1 + control;
    DrawCmd d{};
    d.sprite = sprite;
    corners(c.args, 1, d.x, d.y);
    d.z[0] = c.args[9];
    d.a = c.args[10];
    d.curve = curve;
    if (curve != DrawCmd::None) {
        d.segments = c.args[11];
        for (size_t i = 0; i < control; ++i) d.curve_args[i] = c.args[12 + i];
    }
    for (size_t i = 1; i < 4; ++i) {
        const size_t at = 11 + extra + (i - 1);
        d.z[i] = at < c.args.size() ? c.args[at] : d.z[0];
    }
    d.entity = key_;
    d.seq = counters_->draw++;
    out_->draws.push_back(d);
}

float SessionHost::audio(const vm::AudioCall& c) {
    switch (c.fn) {
        case vm::Fn::HasEffectClip: return resolve(maps_->clip, c.args[0]) >= 0 ? 1.0f : 0.0f;
        case vm::Fn::PlayScheduled: {
            const int32_t clip = resolve(maps_->clip, c.args[0]);
            if (clip >= 0) out_->audio.push_back(AudioCmd{AudioCmd::PlayScheduled, clip, c.args[1], c.args[2], 0});
            return 0;
        }
        case vm::Fn::PlayLoopedScheduled: {
            const int32_t clip = resolve(maps_->clip, c.args[0]);
            if (clip < 0) return 0;
            const float handle = instance_id(key_, counters_->loop);
            out_->audio.push_back(AudioCmd{AudioCmd::PlayLoopedScheduled, clip, c.args[1], 0, handle});
            return handle;
        }
        case vm::Fn::StopLoopedScheduled: {
            const int32_t handle = vm::to_id(c.args[0]);
            if (handle != vm::kNoId)
                out_->audio.push_back(AudioCmd{AudioCmd::StopLoopedScheduled, -1, c.args[1], 0, float(handle)});
            return 0;
        }
        case vm::Fn::Play: {
            const int32_t clip = resolve(maps_->clip, c.args[0]);
            if (mode_ == data::Mode::Play && clip >= 0)
                out_->audio.push_back(AudioCmd{AudioCmd::Play, clip, 0, c.args[1], 0});
            return 0;
        }
        case vm::Fn::PlayLooped: {
            const int32_t clip = resolve(maps_->clip, c.args[0]);
            if (mode_ != data::Mode::Play || clip < 0) return 0;
            const float handle = instance_id(key_, counters_->loop);
            out_->audio.push_back(AudioCmd{AudioCmd::PlayLooped, clip, 0, 0, handle});
            return handle;
        }
        case vm::Fn::StopLooped: {
            const int32_t handle = vm::to_id(c.args[0]);
            if (mode_ == data::Mode::Play && handle != vm::kNoId)
                out_->audio.push_back(AudioCmd{AudioCmd::StopLooped, -1, 0, 0, float(handle)});
            return 0;
        }
        default: return 0;
    }
}

float SessionHost::particle(const vm::ParticleCall& c) {
    switch (c.fn) {
        case vm::Fn::HasParticleEffect: return resolve(maps_->effect, c.args[0]) >= 0 ? 1.0f : 0.0f;
        case vm::Fn::SpawnParticleEffect: {
            const int32_t effect = resolve(maps_->effect, c.args[0]);
            if (effect < 0) return 0;
            ParticleCmd p{};
            p.op = ParticleCmd::Spawn;
            p.handle = instance_id(key_, counters_->particle);
            p.effect = effect;
            corners(c.args, 1, p.x, p.y);
            p.duration = c.args[9];
            p.looped = c.args[10] != 0;
            out_->particles.push_back(p);
            return p.handle;
        }
        case vm::Fn::MoveParticleEffect:
        case vm::Fn::DestroyParticleEffect: {
            const int32_t handle = vm::to_id(c.args[0]);
            if (handle == vm::kNoId) return 0;
            ParticleCmd p{};
            p.op = c.fn == vm::Fn::MoveParticleEffect ? ParticleCmd::Move : ParticleCmd::Destroy;
            p.handle = float(handle);
            p.effect = -1;
            if (p.op == ParticleCmd::Move) corners(c.args, 1, p.x, p.y);
            out_->particles.push_back(p);
            return 0;
        }
        default: return 0;
    }
}

void SessionHost::spawn(int32_t archetype, std::span<const float> data) {
    if (archetype < 0 || archetype >= archetypes_) {
        if (out_->error.empty()) out_->error = "Spawn: archetype id " + std::to_string(archetype) + " is out of range";
        return;
    }
    out_->spawns.push_back(SpawnRequest{archetype, std::vector<float>(data.begin(), data.end())});
}

// Streams are read from the store, which a replay fills. Without one it is empty : no key has a value, so the
// next and previous key of a key is the key itself (functions__stream-get-next-key: "key otherwise"). StreamSet is
// a Play function (recorded here, merged into the session's store in entity order).
float SessionHost::stream(const vm::StreamCall& c) {
    const float key = c.args[1];
    if (c.fn == vm::Fn::StreamSet) {
        if (mode_ == data::Mode::Play) out_->streams.push_back(StreamWrite{vm::to_index(c.args[0]), key, c.args[2]});
        return 0;
    }
    if (streams_ == nullptr) return c.fn == vm::Fn::StreamGetNextKey || c.fn == vm::Fn::StreamGetPreviousKey ? key : 0;
    const int32_t id = vm::to_index(c.args[0]);
    switch (c.fn) {
        case vm::Fn::StreamGetNextKey: return streams_->next_key(id, key);
        case vm::Fn::StreamGetPreviousKey: return streams_->previous_key(id, key);
        case vm::Fn::StreamGetValue: return streams_->value(id, key);
        case vm::Fn::StreamHas: return streams_->has(id, key);
        default: return 0;
    }
}

float SessionHost::time(const vm::TimeCall& c) {
    switch (c.fn) {
        case vm::Fn::BeatToBPM: return times_->beat_to_bpm(c.value);
        case vm::Fn::BeatToStartingBeat: return times_->beat_to_starting_beat(c.value);
        case vm::Fn::BeatToStartingTime: return times_->beat_to_starting_time(c.value);
        case vm::Fn::BeatToTime: return times_->beat_to_time(c.value);
        case vm::Fn::TimeToScaledTime: return times_->time_to_scaled_time(c.value);
        case vm::Fn::TimeToStartingScaledTime: return times_->time_to_starting_scaled_time(c.value);
        case vm::Fn::TimeToStartingTime: return times_->time_to_starting_time(c.value);
        case vm::Fn::TimeToTimeScale: return times_->time_to_time_scale(c.value);
        default: return 0;
    }
}

float SessionHost::has_skin_sprite(int32_t id) {
    return id >= 0 && size_t(id) < maps_->sprite.size() && maps_->sprite[size_t(id)] >= 0 ? 1.0f : 0.0f;
}

// The stat job merges the scheduled life changes into its snapshot list; the session keeps the ones
// preprocess made (the job runs right after it).
void SessionHost::add_life_scheduled(float value, float time) { out_->life.push_back(LifeEvent{value, time}); }
void SessionHost::export_value(int32_t index, float value) {
    if (mode_ == data::Mode::Play) out_->exports.push_back(ExportWrite{entity_, index, value});
}

float SessionHost::random() { return watch_random(seed_, key_, counters_->random++); }

void SessionHost::debug(const vm::DebugCall& c) {
    if (c.fn == vm::Fn::DebugLog && !c.args.empty()) out_->log.push_back(c.args[0]);
}

}  // namespace sr::modes
