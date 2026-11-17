// The Play update cycle (PlaySession), on engines built by hand with NodeBuilder.
#include <bit>
#include <cstdint>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <utility>
#include <vector>

#include "data/resources.h"
#include "doctest.h"
#include "modes/play_session.h"
#include "srun/dump.h"
#include "srun/sha256.h"
#include "srun/watch.h"
#include "support/node_builder.h"

using namespace sr;
using modes::AudioCmd;
using modes::FrameOutput;
using modes::PlayConfig;
using modes::PlaySession;
using modes::Touch;
using Ref = test::NodeBuilder::Ref;
using data::CallbackKind;

namespace {

constexpr int32_t kRuntimeUpdate = 1001, kRuntimeTouchArray = 1002, kLevelMemory = 2000, kLevelLife = 2005,
                  kEntityMemory = 4000, kEntityData = 4001, kEntityDespawn = 4004, kEntityInput = 4005,
                  kArchetypeLife = 5000;

struct Ent {
    std::string archetype;
    std::vector<std::pair<std::string, float>> data;
};

data::Archetype& arch(test::NodeBuilder& b, const std::string& name) {
    for (data::Archetype& a : b.data().archetypes)
        if (a.name == name) return a;
    FAIL("no archetype ", name);
    return b.data().archetypes.front();
}

int32_t arch_index(test::NodeBuilder& b, const std::string& name) {
    for (size_t i = 0; i < b.data().archetypes.size(); ++i)
        if (b.data().archetypes[i].name == name) return int32_t(i);
    FAIL("no archetype ", name);
    return -1;
}

// Entity Data slot i is the import named names[i].
void imports(test::NodeBuilder& b, const std::string& name, const std::vector<std::string>& names) {
    data::Archetype& a = arch(b, name);
    for (size_t i = 0; i < names.size(); ++i) a.imports.push_back(data::Import{names[i], int32_t(i), std::nullopt});
}

data::LoadedLevel make_level(test::NodeBuilder& b, const std::vector<Ent>& entities) {
    data::LoadedLevel level;
    level.play = b.data();
    for (const Ent& e : entities) {
        data::LevelEntity le;
        le.archetype = e.archetype;
        for (const auto& [name, value] : e.data) le.data.push_back(data::EntityDatum{name, false, value, -1});
        level.level.entities.push_back(le);
    }
    return level;
}

std::unique_ptr<PlaySession> prepared(const data::LoadedLevel& level, int threads = 1) {
    PlayConfig config;
    config.threads = threads;
    auto session = PlaySession::create(level, config);
    session->prepare();
    REQUIRE_MESSAGE(!session->faulted(), session->fault().detail);
    return session;
}

time::FrameTime frame(float t) { return time::FrameTime{t, 1.0f / 60.0f, t, false}; }

const FrameOutput& step(PlaySession& session, float t) {
    const FrameOutput& out = session.step(frame(t));
    REQUIRE_MESSAGE(!session.faulted(), session.fault().detail);
    return out;
}

std::vector<int32_t> active_entities(const PlaySession& session) {
    std::vector<int32_t> out;
    for (int32_t e = 0; e < session.entity_count() + session.spawned_count(); ++e)
        if (session.active(e)) out.push_back(e);
    return out;
}

float level_memory(PlaySession& session, size_t i) { return session.block(kLevelMemory)[i]; }

}  // namespace

TEST_SUITE("modes") {

TEST_CASE("play spawn order is stable") {
    // Spawn orders 2, 1, 1; each entity's shouldSpawn is true only on its own frame (Entity Data 0), so the queue
    // order 1, 2, 0 shows as one spawn per frame.
    test::NodeBuilder b;
    b.entry(b("Get", kEntityData, 1), CallbackKind::SpawnOrder, "Note");
    b.entry(b("Equal", b("Get", kRuntimeUpdate, 0), b("Get", kEntityData, 0)), CallbackKind::ShouldSpawn, "Note");
    imports(b, "Note", {"frame", "order"});
    auto session = prepared(make_level(b, {{"Note", {{"frame", 0.5f}, {"order", 2}}},
                                           {"Note", {{"frame", 0}, {"order", 1}}},
                                           {"Note", {{"frame", 0.25f}, {"order", 1}}}}));
    CHECK(session->spawn_order(0) == 2);
    CHECK(session->spawn_order(1) == 1);
    CHECK(session->spawn_order(2) == 1);
    step(*session, 0);
    CHECK(active_entities(*session) == std::vector<int32_t>{1});
    step(*session, 0.25f);
    CHECK(active_entities(*session) == std::vector<int32_t>{1, 2});
    step(*session, 0.5f);
    CHECK(active_entities(*session) == std::vector<int32_t>{0, 1, 2});
}

TEST_CASE("play spawning stops at the first falsy shouldSpawn") {
    test::NodeBuilder b;
    b.entry(b("Execute", 0), CallbackKind::SpawnOrder, "Head");
    b.entry(b("Execute", 0), CallbackKind::ShouldSpawn, "Head");
    b.entry(b("Execute", 1), CallbackKind::SpawnOrder, "Next");
    b.entry(b("Execute", 1), CallbackKind::ShouldSpawn, "Next");
    auto session = prepared(make_level(b, {{"Next", {}}, {"Head", {}}}));
    step(*session, 0);
    step(*session, 0.25f);
    CHECK(active_entities(*session).empty());
}

TEST_CASE("play touch runs only on frames with touches") {
    test::NodeBuilder b;
    b.entry(b("Set", kLevelMemory, 0, b("Add", b("Get", kLevelMemory, 0), 1)), CallbackKind::Touch, "T");
    auto session = prepared(make_level(b, {{"T", {}}}));
    const Touch touch{.id = 7, .started = true};
    step(*session, 0);
    session->set_touches(std::span<const Touch>(&touch, 1));
    step(*session, 0.25f);
    step(*session, 0.5f);
    CHECK(level_memory(*session, 0) == 1);
}

TEST_CASE("play runtime touch array holds the set touches") {
    test::NodeBuilder b;
    b.entry(b("Execute", b("Set", kLevelMemory, 0, b("Get", kRuntimeTouchArray, 5)),
              b("Set", kLevelMemory, 1, b("Get", kRuntimeTouchArray, 15 + 0)),
              b("Set", kLevelMemory, 2, b("Get", kRuntimeUpdate, 3))),
            CallbackKind::UpdateSequential, "T");
    auto session = prepared(make_level(b, {{"T", {}}}));
    const Touch touches[] = {Touch{.id = 7, .started = true, .x = 0.25f}, Touch{.id = 9, .ended = true}};
    session->set_touches(touches);
    step(*session, 0);
    CHECK(level_memory(*session, 0) == 0.25f);
    CHECK(level_memory(*session, 1) == 9.0f);
    CHECK(level_memory(*session, 2) == 2.0f);
    // The touches serve one step only.
    step(*session, 0.25f);
    CHECK(level_memory(*session, 2) == 0.0f);
}

TEST_CASE("play despawn flag terminates and counts the input") {
    test::NodeBuilder b;
    b.entry(b("Execute", b("Set", kEntityInput, 0, 2), b("Set", kEntityInput, 1, 0.5f), b("Set", kEntityInput, 4, 3),
              b("Set", kEntityDespawn, 0, 1)),
            CallbackKind::UpdateParallel, "N");
    b.entry(b("Set", kEntityMemory, 0, 5), CallbackKind::Terminate, "N");
    arch(b, "N").has_input = true;
    auto session = prepared(make_level(b, {{"N", {}}}));
    const FrameOutput& out = step(*session, 0.5f);
    CHECK_FALSE(session->active(0));
    CHECK(session->block(kEntityMemory)[0] == 5.0f);  // terminate ran in the same step
    REQUIRE(session->judgments().size() == 1);
    const modes::StatInput& in = session->judgments()[0];
    CHECK(in.entity == 0);
    CHECK(in.archetype == 0);
    CHECK(in.time == 0.5f);
    CHECK(in.judgment == 2.0f);
    CHECK(in.accuracy == 0.5f);
    CHECK(out.haptic == 3);
    CHECK(out.stats.great == 1);
    CHECK(step(*session, 1).haptic == 0);
    CHECK(session->judgments().size() == 1);
}

TEST_CASE("play counts despawning inputs in reverse activation order") {
    // Entities 0 and 2 despawn on the same frame; the queue spawns 1, 2, 0 in activation order (spawn order 2, 0, 1).
    test::NodeBuilder b;
    b.entry(b("Get", kEntityData, 0), CallbackKind::SpawnOrder, "N");
    b.entry(b("Set", kEntityDespawn, 0, b("Equal", b("Get", kRuntimeUpdate, 0), 5)), CallbackKind::UpdateParallel,
            "N");
    imports(b, "N", {"order"});
    arch(b, "N").has_input = true;
    auto session = prepared(make_level(b, {{"N", {{"order", 2}}}, {"N", {{"order", 0}}}, {"N", {{"order", 1}}}}));
    step(*session, 0);
    step(*session, 5);
    REQUIRE(session->judgments().size() == 3);
    // Activation order 1, 2, 0: counted 0, 2, 1.
    CHECK(session->judgments()[0].entity == 0);
    CHECK(session->judgments()[1].entity == 2);
    CHECK(session->judgments()[2].entity == 1);
}

TEST_CASE("play spawn-function entities have no input") {
    test::NodeBuilder b;
    b.entry(b("Set", kEntityDespawn, 0, 1), CallbackKind::UpdateParallel, "Child");
    b.entry(b("Set", kEntityMemory, 0, 5), CallbackKind::Terminate, "Child");
    arch(b, "Child").has_input = true;  // still no input: a Spawn-function entity never has one
    const int32_t child = arch_index(b, "Child");
    b.entry(b("If", b("Equal", b("Get", kLevelMemory, 5), 0),
              b("Execute", b("Set", kLevelMemory, 5, 1), b("Spawn", child)), 0),
            CallbackKind::UpdateSequential, "Spawner");
    auto session = prepared(make_level(b, {{"Spawner", {}}}));
    step(*session, 0);
    CHECK(session->spawned_count() == 0);
    step(*session, 0.25f);
    REQUIRE(session->spawned_count() == 1);
    CHECK_FALSE(session->active(1));  // spawned and despawned in the same step
    CHECK(session->block(kEntityMemory)[64] == 5.0f);
    CHECK(session->judgments().empty());
}

TEST_CASE("play keeps an entity of an undefined archetype as an inert one the engine cannot spawn") {
    // Level: 0 #BPM_CHANGE (not in the engine), 1 Note, 2 Spawner. Spawner spawns archetype id 2 (one past the
    // engine's Spawner and Note: the inert one), which is out of range.
    test::NodeBuilder b;
    b.entry(b("Execute", 0), CallbackKind::Preprocess, "Note");
    b.entry(b("Spawn", 2), CallbackKind::UpdateSequential, "Spawner");
    REQUIRE(b.data().archetypes.size() == 2);
    auto session = PlaySession::create(make_level(b, {{"#BPM_CHANGE", {{"#BEAT", 0}, {"#BPM", 120}}},
                                                      {"Note", {}},
                                                      {"Spawner", {}}}),
                                       PlayConfig{});
    session->prepare();
    REQUIRE_MESSAGE(!session->faulted(), session->fault().detail);
    CHECK(session->entity_count() == 3);
    CHECK(session->archetype_of(0) == 2);
    const int32_t note = arch_index(b, "Note");
    // Entity Info (index, archetype, state): the inert entity 0 shows archetype 2; Note is still entity 1.
    const auto info = session->block(4103);
    CHECK(std::vector<float>(info.begin(), info.begin() + 6) == std::vector<float>{0, 2, 0, 1, float(note), 0});
    session->step(frame(0));
    CHECK(session->faulted());
    CHECK(session->fault().detail.find("out of range") != std::string::npos);
}

TEST_CASE("play stream set is visible to stream get next frame") {
    test::NodeBuilder b;
    b.entry(b("Set", kLevelMemory, 0, b("StreamGetValue", 3, 1.5f)), CallbackKind::UpdateSequential, "S");
    b.entry(b("StreamSet", 3, 1.5f, 9), CallbackKind::UpdateParallel, "S");
    auto session = prepared(make_level(b, {{"S", {}}}));
    step(*session, 0);
    CHECK(level_memory(*session, 0) == 0.0f);
    CHECK(session->streams().has(3, 1.5f) == 1.0f);
    step(*session, 0.25f);
    CHECK(level_memory(*session, 0) == 9.0f);
}

TEST_CASE("play export value is kept per entity") {
    test::NodeBuilder b;
    b.entry(b("ExportValue", 1, b("Get", kEntityData, 0)), CallbackKind::UpdateParallel, "X");
    imports(b, "X", {"v"});
    arch(b, "X").exports = {data::Import{"a", 0, std::nullopt}, data::Import{"b", 1, std::nullopt}};
    auto session = prepared(make_level(b, {{"X", {{"v", 7}}}, {"X", {{"v", 9}}}}));
    step(*session, 0);
    CHECK(session->exported(0, 1) == 7.0f);
    CHECK(session->exported(1, 1) == 9.0f);
    CHECK(session->exported(0, 0) == 0.0f);
    CHECK(session->exported(5, 1) == 0.0f);
}

TEST_CASE("play scheduled outside preprocess is kept") {
    // The rule is Watch's: PlayScheduled is legal in every Play callback.
    test::NodeBuilder b;
    b.entry(b("PlayScheduled", 0, 5, 0), CallbackKind::UpdateSequential, "A");
    b.data().clips = {data::NamedId{"tick", 0}};
    data::LoadedLevel level = make_level(b, {{"A", {}}});
    level.effect.clips = {data::EffectClip{"tick", "tick.mp3"}};
    auto session = prepared(level);
    const FrameOutput& out = step(*session, 0);
    REQUIRE(out.audio.size() == 1);
    CHECK(out.audio[0].op == AudioCmd::PlayScheduled);
    CHECK(out.audio[0].time == 5.0f);
}

TEST_CASE("play life zero grades fail and the run goes on") {
    // Life 1, a Miss costs 1. Entity 0 despawns at once as a Miss (Entity Input 0 = 0); Entity 1 counts frames.
    test::NodeBuilder b;
    b.entry(b("Execute", b("Set", kLevelLife, 6, 1), b("Set", kArchetypeLife, 3, -1)), CallbackKind::Preprocess, "N");
    b.entry(b("Set", kEntityDespawn, 0, 1), CallbackKind::UpdateParallel, "N");
    b.entry(b("Set", kLevelMemory, 0, b("Add", b("Get", kLevelMemory, 0), 1)), CallbackKind::UpdateSequential, "C");
    arch(b, "N").has_input = true;
    REQUIRE(arch_index(b, "N") == 0);
    auto session = prepared(make_level(b, {{"N", {}}, {"C", {}}}));
    const FrameOutput& out = step(*session, 0);
    CHECK(out.stats.miss == 1);
    CHECK(out.stats.life == 0.0f);
    CHECK_FALSE(session->ended());
    step(*session, 0.5f);
    CHECK_FALSE(session->ended());
    CHECK(level_memory(*session, 0) == 2.0f);
    CHECK(session->result().grade == modes::Grade::Fail);
    CHECK(session->result().miss == 1);
    CHECK(session->result().total == 1);
}

TEST_CASE("play ends one second after the last input despawns") {
    // With every input despawned the end becomes max(time + 1, end_time()); the first frame at or past it ends.
    test::NodeBuilder b;
    b.entry(b("Set", kEntityDespawn, 0, 1), CallbackKind::UpdateParallel, "N");
    arch(b, "N").has_input = true;
    auto session = prepared(make_level(b, {{"N", {}}}));
    CHECK_FALSE(step(*session, 0).ended);
    CHECK_FALSE(step(*session, 0.5f).ended);  // the end is set at the start of the despawn step of the frame at 0.5
    CHECK(session->end_time() == 0.0f);
    const FrameOutput& last = session->step(frame(1.4f));
    CHECK_FALSE(last.ended);
    CHECK_FALSE(session->ended());
    const FrameOutput& done = session->step(frame(1.5f));
    CHECK(done.ended);
    CHECK(done.draws.empty());
    CHECK(session->ended());
}

TEST_CASE("play restart returns to the post-preparation state") {
    test::NodeBuilder b;
    b.entry(b("Execute", b("Set", kEntityInput, 0, 1), b("Set", kEntityDespawn, 0, b("Equal", b("Get", kRuntimeUpdate, 0), 2)),
              b("StreamSet", 1, 0, 4), b("ExportValue", 0, 6), b("Set", kLevelMemory, 0, b("Add", b("Get", kLevelMemory, 0), 1))),
            CallbackKind::UpdateSequential, "N");
    b.entry(b("Execute", b("Set", kEntityDespawn, 0, b("Equal", b("Get", kRuntimeUpdate, 0), 2)),
              b("StreamSet", 1, 0, 4), b("ExportValue", 0, 6)),
            CallbackKind::UpdateParallel, "N");
    arch(b, "N").has_input = true;
    arch(b, "N").exports = {data::Import{"a", 0, std::nullopt}};
    // A stream written in preprocess is part of the post-preparation state; a Spawn-function child is admitted
    // in the run and gone after restart.
    b.entry(b("StreamSet", 7, 0, 3), CallbackKind::Preprocess, "N");
    b.entry(b("Set", kEntityDespawn, 0, 1), CallbackKind::UpdateParallel, "Child");
    const int32_t child = arch_index(b, "Child");
    b.entry(b("If", b("Equal", b("Get", kLevelMemory, 5), 0),
              b("Execute", b("Set", kLevelMemory, 5, 1), b("Spawn", child)), 0),
            CallbackKind::UpdateSequential, "Spawner");
    auto session = prepared(make_level(b, {{"N", {}}, {"Spawner", {}}}));
    CHECK(session->streams().has(7, 0) == 1.0f);
    const auto run = [&] {
        std::vector<int32_t> trace;
        for (const float t : {0.0f, 1.0f, 2.0f, 3.0f, 4.0f}) {  // the input despawns at 2, the end is 1 s after (3 -> 4)
            const FrameOutput& out = step(*session, t);
            trace.push_back(out.stats.perfect);
            trace.push_back(session->active(0) ? 1 : 0);
            trace.push_back(int32_t(level_memory(*session, 0)));
            trace.push_back(session->spawned_count());
            trace.push_back(int32_t(session->streams().has(7, 0)));
        }
        return trace;
    };
    const std::vector<int32_t> first = run();
    CHECK(session->ended());  // the run went past the end
    CHECK(session->judgments().size() == 1);
    CHECK(session->exported(0, 0) == 6.0f);
    CHECK(session->spawned_count() == 1);
    session->restart();
    CHECK(session->judgments().empty());
    CHECK(session->exported(0, 0) == 0.0f);
    CHECK(session->streams().has(1, 0) == 0.0f);  // the run's own stream write is gone ...
    CHECK(session->streams().has(7, 0) == 1.0f);  // ... the preparation's stays
    CHECK(session->spawned_count() == 0);
    CHECK_FALSE(session->ended());
    CHECK(run() == first);
    CHECK(session->ended());
}

TEST_CASE("play output is the same for 1 and 4 threads") {
    test::NodeBuilder b;
    b.entry(b("Execute", b("Set", kEntityMemory, 0, b("Add", b("Get", kEntityMemory, 0), b("Get", kEntityData, 0))),
              b("StreamSet", 1, 0, b("Get", kEntityData, 0)),
              b("Draw", 0, 0, 0, 0, 1, 1, 1, 1, 0, b("Get", kEntityData, 0), 1)),
            CallbackKind::UpdateParallel, "P");
    imports(b, "P", {"v"});
    arch(b, "P").has_input = true;  // inputs that never despawn keep the end at +inf
    b.data().sprites = {data::NamedId{"a", 0}};
    std::vector<Ent> entities;
    for (int i = 0; i < 50; ++i) entities.push_back({"P", {{"v", float(i + 1)}}});
    data::LoadedLevel level = make_level(b, entities);
    data::SkinSprite sprite;
    sprite.name = "a";
    level.skin.sprites = {sprite};

    const auto hash = [&](int threads) {
        auto session = prepared(level, threads);
        tools::Sha256 sha;
        std::string line;
        for (int i = 0; i < 200; ++i) {
            const FrameOutput& out = step(*session, float(i) / 60.0f);
            line.clear();
            tools::append_frame(line, float(i) / 60.0f, out);
            sha.update(line);
            for (const float v : session->block(kEntityMemory)) sha.update(std::to_string(std::bit_cast<uint32_t>(v)));
            sha.update(std::to_string(session->streams().value(1, 0)));
        }
        return sha.hex();
    };
    CHECK(hash(1) == hash(4));
}

}
