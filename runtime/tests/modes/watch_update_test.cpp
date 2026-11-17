// The Watch update cycle (WatchSession::step) and its presentation commands, on the mini engines in
// fixtures/engines (tools/fixtures/engines/*.py, built by `python -m sairitsu_tools build-engines`).
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <set>
#include <sstream>
#include <string>
#include <vector>

#include "data/configuration.h"
#include "data/load_error.h"
#include "data/resources.h"
#include "doctest.h"
#include "modes/session_host.h"
#include "modes/watch_session.h"
#include "support/doctest_vectors.h"
#include "support/engine_fixture.h"
#include "vm/convert.h"

using namespace sr;
using modes::AudioCmd;
using modes::DrawCmd;
using modes::FrameOutput;
using modes::ParticleCmd;
using modes::WatchConfig;
using modes::WatchSession;

namespace {

constexpr int32_t kLevelOption = 2002, kEntityMemory = 4000, kEntityInfoArray = 4103;

std::unique_ptr<WatchSession> prepared(const data::LoadedLevel& level, const WatchConfig& config = {}) {
    auto session = WatchSession::create(level, config);
    session->prepare();
    REQUIRE_MESSAGE(!session->faulted(), session->fault().detail);
    return session;
}

time::FrameTime frame(float t) { return time::FrameTime{t, 1.0f / 60.0f, t, false}; }

const FrameOutput& step(WatchSession& session, float t) {
    const FrameOutput& out = session.step(frame(t));
    REQUIRE_MESSAGE(!session.faulted(), session.fault().detail);
    return out;
}

std::vector<float> vec(std::span<const float> s) { return {s.begin(), s.end()}; }

std::vector<float> active_entities(const WatchSession& session) {
    std::vector<float> out;
    for (int32_t e = 0; e < session.entity_count() + session.spawned_count(); ++e)
        if (session.active(e)) out.push_back(float(e));
    return out;
}

// The debug log from `from` on.
std::vector<float> log_since(const WatchSession& session, size_t from) {
    const auto log = session.debug_log();
    REQUIRE(from <= log.size());
    return {log.begin() + std::ptrdiff_t(from), log.end()};
}

// A PCM WAV file of `seconds` of silence (8 kHz mono 8-bit: 8,000 bytes per second).
std::vector<uint8_t> wav(double seconds) {
    const uint32_t size = uint32_t(seconds * 8000);
    std::vector<uint8_t> out;
    const auto text = [&](const char* s) { out.insert(out.end(), s, s + 4); };
    const auto u32 = [&](uint32_t v) {
        for (int i = 0; i < 4; ++i) out.push_back(uint8_t(v >> (8 * i)));
    };
    const auto u16 = [&](uint16_t v) {
        out.push_back(uint8_t(v));
        out.push_back(uint8_t(v >> 8));
    };
    text("RIFF");
    u32(36 + size);
    text("WAVE");
    text("fmt ");
    u32(16);
    u16(1), u16(1), u32(8000), u32(8000), u16(1), u16(8);
    text("data");
    u32(size);
    out.resize(out.size() + size, 0x80);
    return out;
}

data::SkinSprite skin_sprite(const std::string& name) {
    data::SkinSprite s;
    s.name = name;
    return s;
}

// draw_order with a skin of "lane" (resource 0) and "note" (resource 1); "absent" is missing.
data::LoadedLevel draw_order() {
    data::LoadedLevel level = test::engine_fixture("draw_order");
    level.skin.sprites = {skin_sprite("lane"), skin_sprite("note")};
    return level;
}

// particles_and_audio with clips "hold" (resource 0), "tick" (resource 1) and the particle effect "spark"
// (resource 0); "absent" is missing.
data::LoadedLevel particles_and_audio() {
    data::LoadedLevel level = test::engine_fixture("particles_and_audio");
    level.effect.clips = {data::EffectClip{"hold", "hold.mp3"}, data::EffectClip{"tick", "tick.mp3"}};
    data::ParticleEffect spark;
    spark.name = "spark";
    level.particle.effects = {spark};
    return level;
}

// Every field of a frame as text, NaNs printed alike, floats by their bits otherwise.
void put(std::ostringstream& out, float v) {
    uint32_t bits;
    std::memcpy(&bits, &v, sizeof bits);
    if (std::isnan(v)) bits = 0x7FC00000u;
    out << bits << ' ';
}

std::string dump(const FrameOutput& f) {
    std::ostringstream out;
    for (const DrawCmd& d : f.draws) {
        out << "D " << d.sprite << ' ' << int(d.curve) << ' ' << d.entity << ' ' << d.seq << ' ';
        for (int i = 0; i < 4; ++i) put(out, d.x[i]), put(out, d.y[i]), put(out, d.z[i]), put(out, d.curve_args[i]);
        put(out, d.a);
        put(out, d.segments);
        out << '\n';
    }
    for (const AudioCmd& a : f.audio) {
        out << "A " << int(a.op) << ' ' << a.clip << ' ';
        put(out, a.time), put(out, a.distance), put(out, a.handle);
        out << '\n';
    }
    for (const ParticleCmd& p : f.particles) {
        out << "P " << int(p.op) << ' ' << p.effect << ' ' << p.looped << ' ';
        put(out, p.handle), put(out, p.duration);
        for (int i = 0; i < 4; ++i) put(out, p.x[i]), put(out, p.y[i]);
        out << '\n';
    }
    for (float v : f.skin_transform) put(out, v);
    for (float v : f.particle_transform) put(out, v);
    for (float v : f.background) put(out, v);
    for (float v : f.ui) put(out, v);
    out << f.ended << '\n';
    return out.str();
}

std::string dump(std::span<const float> values) {
    std::ostringstream out;
    for (float v : values) put(out, v);
    return out.str();
}

}  // namespace

TEST_SUITE("modes") {
TEST_CASE("entity spawns and despawns by range") {
    // Note a (entity 1): [2, 3.25). Note b (entity 2): [3, 3.5). Initialize logs 1000 + index, terminate 2000 + index.
    auto session = prepared(test::engine_fixture("spawn_basic"));
    const auto state = [&](int32_t e) { return session->block(kEntityInfoArray)[size_t(e) * 3 + 2]; };

    step(*session, 1.9f);
    CHECK(active_entities(*session).empty());
    step(*session, 2.0f);  // the spawn time is inside the range
    CHECK(active_entities(*session) == std::vector<float>{1});
    CHECK(state(1) == 1);
    step(*session, 3.0f);
    CHECK(active_entities(*session) == std::vector<float>{1, 2});
    CHECK(state(2) == 1);
    step(*session, 3.25f);  // the despawn time is outside
    CHECK(active_entities(*session) == std::vector<float>{2});
    CHECK(state(1) == 0);
    step(*session, 3.5f);
    CHECK(active_entities(*session).empty());
    CHECK(vec(session->debug_log()) == std::vector<float>{1001, 1002, 2001, 2002});

    // Without a readable BGM length the level ends at the last despawn time (3.5) plus 1 s.
    CHECK_FALSE(step(*session, 4.5f - 1.0f / 64).ended);
    CHECK(step(*session, 4.5f).ended);
}

TEST_CASE("watch ends at the BGM end") {
    // Watch ends on the first frame at or past bgmOffset + the BGM's length; that frame
    // runs no system and draws nothing; then it loops from -2 s with a skip, and only the lifetime system's usual
    // rule terminates entities (no terminate-everything at the end, no second preprocess).
    data::LoadedLevel level = test::engine_fixture("spawn_basic");
    level.bgm = wav(2.0);
    level.level.bgm_offset = 0.5f;
    auto session = prepared(level);
    CHECK(session->end_time() == 2.5f);
    const FrameOutput& before = step(*session, 2.5f - 1.0f / 64);  // entity 1 ([2, 3.25)) is active
    CHECK_FALSE(before.ended);
    REQUIRE(active_entities(*session) == std::vector<float>{1});
    const size_t mark = session->debug_log().size();

    const FrameOutput& end = step(*session, 2.5f);
    CHECK(end.ended);
    CHECK(end.draws.empty());
    CHECK(end.audio.empty());
    CHECK(active_entities(*session) == std::vector<float>{1});  // nothing terminated
    CHECK(log_since(*session, mark).empty());

    // The loop: a skip back to -2 s runs the lifetime system, which terminates entity 1 (out of range).
    const FrameOutput& again = session->step(time::FrameTime{-2.0f, 0.0f, -2.0f, true});
    REQUIRE_MESSAGE(!session->faulted(), session->fault().detail);
    CHECK_FALSE(again.ended);
    CHECK(active_entities(*session).empty());
    CHECK(log_since(*session, mark) == std::vector<float>{2001});
    step(*session, 2.0f);  // and the level plays again
    CHECK(active_entities(*session) == std::vector<float>{1});
}

TEST_CASE("terminate before initialize on respawn") {
    auto session = prepared(test::engine_fixture("spawn_basic"));
    step(*session, 3.0f);  // a and b spawn
    step(*session, 3.3f);  // a despawns
    const size_t mark = session->debug_log().size();
    step(*session, 2.5f);  // time jumps backwards: b despawns, a spawns again
    CHECK(active_entities(*session) == std::vector<float>{1});
    CHECK(log_since(*session, mark) == std::vector<float>{2002, 1001});
    step(*session, 3.1f);  // b spawns again; a stays
    CHECK(active_entities(*session) == std::vector<float>{1, 2});
    CHECK(log_since(*session, mark) == std::vector<float>{2002, 1001, 1002});
}

TEST_CASE("spawn function entity spawns next frame") {
    // Emitter 0 is active in [2, 6); its first updateSequential spawns an Effect (start = time - 0.5, length 1).
    // Emitter 1's despawnTime spawned an Effect at 20 during preparation. Effect's spawnTime logs 100 + start and
    // its initialize 300 + start.
    auto session = prepared(test::engine_fixture("spawn_function"));
    REQUIRE(session->spawned_count() == 4);
    const size_t mark = session->debug_log().size();

    step(*session, 2.0f);
    // The despawnTime spawn joins the queue at this step's spawning: its spawnTime runs, then entity 2 spawns.
    REQUIRE(session->spawned_count() == 5);
    CHECK(session->archetype_of(6) == 0);
    CHECK(session->spawn_times()[6] == 20);
    CHECK(session->despawn_times()[6] == 21);
    CHECK(active_entities(*session) == std::vector<float>{0, 2});
    CHECK(log_since(*session, mark) == std::vector<float>{120, 302});

    step(*session, 2.125f);  // the updateSequential spawn of the last frame: start 1.5, active now
    REQUIRE(session->spawned_count() == 6);
    CHECK(session->spawn_times()[7] == 1.5f);
    CHECK(session->despawn_times()[7] == 2.5f);
    CHECK(active_entities(*session) == std::vector<float>{0, 2, 7});
    CHECK(log_since(*session, mark) == std::vector<float>{120, 302, 101.5f, 301.5f});
    CHECK(session->block(kEntityMemory)[7 * 64] == 1.5f);  // Spawn data in its Entity Memory

    step(*session, 2.25f);  // Emitter 0 spawns once
    CHECK(session->spawned_count() == 6);
}

TEST_CASE("draws merged in entity order") {
    WatchConfig config;
    config.threads = 4;
    auto session = prepared(draw_order(), config);
    // Drawer's preprocess sees the level's skin: note and lane exist, absent doesn't, 0.5 names nothing.
    CHECK(vec(session->debug_log()) == std::vector<float>{1, 1, 0, 0});

    const FrameOutput& out = step(*session, 1.0f);
    REQUIRE(out.draws.size() == 1 + 12 * 2);  // absent and id 0.5 draw nothing

    const DrawCmd& sky = out.draws[0];  // updateSequential before updateParallel
    CHECK(sky.entity == 0);
    CHECK(sky.seq == 0);
    CHECK(sky.sprite == 0);  // "lane" is resource 0
    CHECK(sky.curve == DrawCmd::BT);
    CHECK(sky.segments == 8);
    CHECK(std::vector<float>(sky.curve_args, sky.curve_args + 4) == std::vector<float>{0.25f, -1, -0.25f, 1});
    CHECK(std::vector<float>(sky.x, sky.x + 4) == std::vector<float>{-2, -2, -1, -1});
    CHECK(std::vector<float>(sky.y, sky.y + 4) == std::vector<float>{-1, 0, 0, -1});
    CHECK(std::vector<float>(sky.z, sky.z + 4) == std::vector<float>{5, 0, 0, 0});  // sonolus.py pads z with 0
    CHECK(sky.a == 1);

    for (int32_t e = 1; e <= 12; ++e) {
        CAPTURE(e);
        for (uint32_t j = 0; j < 2; ++j) {
            CAPTURE(j);
            const DrawCmd& d = out.draws[size_t(1 + (e - 1) * 2) + j];
            CHECK(d.entity == e);
            CHECK(d.seq == j);
            CHECK(d.sprite == 1);  // "note"
            CHECK(d.curve == DrawCmd::None);
            CHECK(d.segments == 0);
            const float x = float(e), y = float(j);
            CHECK(std::vector<float>(d.x, d.x + 4) == std::vector<float>{x, x, x + 1, x + 1});
            CHECK(std::vector<float>(d.y, d.y + 4) == std::vector<float>{y, y + 1, y + 1, y});
            if (j == 0) {
                CHECK(d.a >= 0);
                CHECK(d.a < 1);
                CHECK(std::vector<float>(d.z, d.z + 4) == std::vector<float>{d.a, 0, 0, 0});
            } else {
                CHECK(d.a == 0.5f);
                CHECK(std::vector<float>(d.z, d.z + 4) == std::vector<float>{1, 2, 3, 4});
            }
        }
    }

    // The next frame starts a new draw order.
    const FrameOutput& next = step(*session, 1.0f + 1.0f / 60);
    REQUIRE(next.draws.size() == 25);
    CHECK(next.draws[1].seq == 0);
    CHECK(next.draws[0].a == 1);
}

TEST_CASE("output identical for 1 and 4 threads") {
    for (const char* name : {"draw_order", "particles_and_audio"}) {
        CAPTURE(name);
        const data::LoadedLevel level = std::string(name) == "draw_order" ? draw_order() : particles_and_audio();
        std::string runs[2];
        for (const int threads : {1, 4}) {
            WatchConfig config;
            config.threads = threads;
            auto session = prepared(level, config);
            std::string& text = runs[threads == 1 ? 0 : 1];
            // Forward over both notes, a jump back (respawns), forward again.
            for (int i = 0; i <= 240; ++i) text += dump(step(*session, float(i) / 60));
            for (int i = 60; i <= 120; ++i) text += dump(step(*session, float(i) / 60));
            text += dump(session->debug_log());
            text += dump(session->block(kEntityMemory));
        }
        CHECK(runs[0].size() > 1000);
        CHECK(runs[0] == runs[1]);
    }
}

TEST_CASE("particle ids deterministic") {
    // Note 0: [1, 3). Note 1: [1.5, 3.5). Initialize logs its looped spark's id and what spawning "absent" returns;
    // updateParallel logs a one-shot spark's id; terminate destroys the looped spark.
    WatchConfig config;
    config.threads = 4;
    auto session = prepared(particles_and_audio(), config);
    step(*session, 1.0f);
    CHECK(vec(session->debug_log()) == std::vector<float>{1, 0, 2});

    const FrameOutput& out = step(*session, 1.5f);
    CHECK(vec(session->debug_log()) == std::vector<float>{1, 0, 2, 257, 0, 3, 258});
    REQUIRE(out.particles.size() == 5);
    const ParticleCmd& spawn = out.particles[0];  // initialize of note 1
    CHECK(spawn.op == ParticleCmd::Spawn);
    CHECK(spawn.handle == 257);
    CHECK(spawn.effect == 0);
    CHECK(spawn.duration == 0.5f);
    CHECK(spawn.looped);
    CHECK(std::vector<float>(spawn.x, spawn.x + 4) == std::vector<float>{1, 1, 2, 2});
    CHECK(std::vector<float>(spawn.y, spawn.y + 4) == std::vector<float>{0, 1, 1, 0});
    // updateParallel by entity index: note 0 moves 1 and spawns 3, note 1 moves 257 and spawns 258.
    CHECK(out.particles[1].op == ParticleCmd::Move);
    CHECK(out.particles[1].handle == 1);
    CHECK(out.particles[1].effect == -1);
    CHECK(std::vector<float>(out.particles[1].y, out.particles[1].y + 4) == std::vector<float>{1, 2, 2, 1});
    CHECK(out.particles[2].op == ParticleCmd::Spawn);
    CHECK(out.particles[2].handle == 3);
    CHECK_FALSE(out.particles[2].looped);
    CHECK(out.particles[3].handle == 257);
    CHECK(out.particles[4].handle == 258);

    const FrameOutput& later = step(*session, 3.0f);  // note 0 despawns
    REQUIRE(later.particles.size() >= 1);
    CHECK(later.particles[0].op == ParticleCmd::Destroy);
    CHECK(later.particles[0].handle == 1);

    // Counters persist over respawns: note 0 spawns again and continues from its fourth id.
    const size_t mark = session->debug_log().size();
    step(*session, 1.0f);
    CHECK(log_since(*session, mark) == std::vector<float>{4, 0, 5});

    // The host directly: a global callback uses entity 65,535, and the counter wraps at 256.
    const time::TimeTables times = time::TimeTables::build(data::LevelData{}, data::EngineModeData{});
    modes::ResourceMaps maps;
    maps.effect = {0};
    modes::SessionHost host(data::Mode::Watch, times, maps, 1, 1, nullptr);
    modes::EntityCounters counters;
    modes::Effects effects;
    const float args[11] = {0, 0, 0, 0, 1, 1, 1, 1, 0, 1, 0};
    host.begin(-1, counters, effects);
    CHECK(host.particle(vm::ParticleCall{vm::Fn::SpawnParticleEffect, args}) == 1 + 65535.0f * 256);
    host.begin(3, counters, effects);
    for (int i = 1; i < 256; ++i) host.particle(vm::ParticleCall{vm::Fn::SpawnParticleEffect, args});
    CHECK(host.particle(vm::ParticleCall{vm::Fn::SpawnParticleEffect, args}) == 1 + 3 * 256);  // wrapped
    const float fractional[11] = {0.5f, 0, 0, 0, 1, 1, 1, 1, 0, 1, 0};
    const size_t recorded = effects.particles.size();
    CHECK(host.particle(vm::ParticleCall{vm::Fn::SpawnParticleEffect, fractional}) == 0);
    CHECK(effects.particles.size() == recorded);
    const float has[1] = {0.5f};
    CHECK(host.particle(vm::ParticleCall{vm::Fn::HasParticleEffect, has}) == 0);
}

TEST_CASE("the host records AddLifeScheduled for the stats") {
    const time::TimeTables times = time::TimeTables::build(data::LevelData{}, data::EngineModeData{});
    const modes::ResourceMaps maps;
    modes::SessionHost host(data::Mode::Watch, times, maps, 1, 1, nullptr);
    modes::EntityCounters counters;
    modes::Effects effects;
    host.begin(0, counters, effects);
    host.add_life_scheduled(-250.0f, 12.5f);
    host.add_life_scheduled(40.0f, 3.0f);
    REQUIRE(effects.life.size() == 2);
    CHECK(effects.life[0].value == -250.0f);
    CHECK(effects.life[0].time == 12.5f);
    CHECK(effects.life[1].time == 3.0f);
    effects.clear();
    CHECK(effects.life.empty());
}

TEST_CASE("streams empty without replay") {
    auto session = prepared(test::engine_fixture("streams_empty"));
    step(*session, 1.0f);
    // StreamHas 0, StreamGetValue 0, next and previous key are the query key (5.5).
    CHECK(vec(session->debug_log()) == std::vector<float>{0, 0, 5.5f, 5.5f});
}

TEST_CASE("play scheduled outside preprocess is ignored, looped clips recorded") {
    // Watch plays no PlayScheduled made outside preprocess (none of the
    // six from initialize, leads -0.1 to 1 s, plays). The fixture's initialize schedules "tick" at its hit, so
    // only the looped clip's commands remain.
    auto session = prepared(particles_and_audio());
    const FrameOutput& out = step(*session, 1.0f);  // note 0 (hit 2) initializes
    REQUIRE(out.audio.size() == 2);
    CHECK(out.audio[0].op == AudioCmd::PlayLoopedScheduled);
    CHECK(out.audio[0].clip == 0);  // "hold"
    CHECK(out.audio[0].time == 2);
    CHECK(out.audio[0].handle == 1);
    CHECK(out.audio[1].op == AudioCmd::StopLoopedScheduled);
    CHECK(out.audio[1].clip == -1);
    CHECK(out.audio[1].time == 2.5f);
    CHECK(out.audio[1].handle == 1);
    CHECK(step(*session, 1.25f).audio.empty());  // nothing new this frame
}

TEST_CASE("level option defaults from the configuration") {
    data::LoadedLevel level = test::engine_fixture("spawn_basic");
    level.configuration.options.resize(3);
    level.configuration.options[0].def = 1;
    level.configuration.options[1].def = 9.8f;
    level.configuration.options[2].def = 0.5f;
    WatchConfig config;
    config.options = {7};
    CHECK(vec(WatchSession::create(level, config)->block(kLevelOption)) == std::vector<float>{7, 9.8f, 0.5f});
    config.options = {1, 2, 3, 4};
    CHECK_THROWS_AS(WatchSession::create(level, config), data::LoadError);
}

TEST_CASE("levels past 65,535 entities load; past kMaxEntities is a load error") {
    // UntitledCharts gimmick levels have 103k and 129k entities.
    data::LoadedLevel level = test::engine_fixture("spawn_basic");
    level.level.entities.resize(65537, level.level.entities[3]);  // Layout
    auto session = WatchSession::create(level, {});
    session->prepare();
    CHECK_FALSE(session->faulted());
    level.level.entities.resize(size_t(modes::kMaxEntities) + 1, level.level.entities[3]);
    CHECK_THROWS_AS(WatchSession::create(level, {}), data::LoadError);
}

TEST_CASE("engine-declared entityDataLength lays out Entity Data and keeps imports past 32") {
    // Next SEKAI declares entityDataLength 38 and imports #JUDGMENT / #ACCURACY at 36 / 37.
    data::LoadedLevel level = test::engine_fixture("spawn_basic");
    level.watch.entity_data_length = 38;
    level.watch.entity_memory_length = 43;
    level.watch.entity_shared_memory_length = 20;
    const int32_t a = [&] {
        for (size_t i = 0; i < level.watch.archetypes.size(); ++i)
            if (level.watch.archetypes[i].name == level.level.entities[3].archetype) return int32_t(i);
        return -1;
    }();
    REQUIRE(a >= 0);
    level.watch.archetypes[size_t(a)].imports.push_back(data::Import{"far", 37, std::nullopt});
    level.level.entities[3].data.push_back(data::EntityDatum{"far", false, 7.5f, -1});
    auto session = WatchSession::create(level, {});
    const std::span<const float> entity_data = session->block(4101);
    CHECK(entity_data.size() == level.level.entities.size() * 38);
    CHECK(entity_data[3 * 38 + 37] == 7.5f);
    CHECK(session->block(4102).size() == level.level.entities.size() * 20);
}

TEST_CASE("entities from 65,535 get instance ids apart from the global callbacks'") {
    const time::TimeTables times = time::TimeTables::build(data::LevelData{}, data::EngineModeData{});
    modes::ResourceMaps maps;
    maps.effect = {0};
    modes::SessionHost host(data::Mode::Watch, times, maps, 1, 1, nullptr);
    modes::Effects effects;
    const float args[11] = {0, 0, 0, 0, 1, 1, 1, 1, 0, 1, 0};
    auto ids_of = [&](int32_t entity) {
        modes::EntityCounters counters;
        host.begin(entity, counters, effects);
        std::vector<float> ids;
        for (int i = 0; i < 256; ++i) ids.push_back(host.particle(vm::ParticleCall{vm::Fn::SpawnParticleEffect, args}));
        return ids;
    };
    std::set<float> seen;
    for (const int32_t entity : {-1, 65534, 65535, 65536, 129000, modes::kMaxEntities - 1}) {
        for (const float id : ids_of(entity)) {
            CHECK(vm::to_id(id) != vm::kNoId);          // an integer in int32 range: Move/Destroy/StopLooped find it
            CHECK(float(vm::to_id(id)) == id);
            CHECK_MESSAGE(seen.insert(id).second, "entity ", entity, " repeats id ", id);
        }
    }
    CHECK(ids_of(-1).front() == 1 + 65535.0f * 256);  // unchanged for levels within 65,535 entities
    CHECK(ids_of(65534).back() == 65534.0f * 256 + 256);
}
}  // TEST_SUITE
