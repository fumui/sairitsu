// Watch preparation (WatchSession::create + prepare) on the mini engines in fixtures/engines, built by
// `python -m sairitsu_tools build-engines` from tools/fixtures/engines/*.py.
#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

#include "data/configuration.h"
#include "data/load_error.h"
#include "data/resources.h"
#include "doctest.h"
#include "modes/watch_session.h"
#include "support/doctest_vectors.h"
#include "support/engine_fixture.h"

using namespace sr;
using modes::WatchConfig;
using modes::WatchSession;

namespace {

constexpr int32_t kRuntimeEnvironment = 1000, kRuntimeUi = 1005, kRuntimeUiConfiguration = 1006,
                  kLevelOption = 2002, kEngineRom = 3000, kEntityMemory = 4000, kEntityDataArray = 4101,
                  kEntityInfoArray = 4103;

data::LoadedLevel fixture(const std::string& name) { return test::engine_fixture(name); }

std::unique_ptr<WatchSession> prepared(const data::LoadedLevel& level, const WatchConfig& config = {}) {
    auto session = WatchSession::create(level, config);
    session->prepare();
    REQUIRE_MESSAGE(!session->faulted(), session->fault().detail);
    return session;
}

std::vector<float> vec(std::span<const float> s) { return {s.begin(), s.end()}; }

std::vector<float> slice(std::span<const float> s, size_t from, size_t n) {
    REQUIRE(from + n <= s.size());
    return {s.begin() + std::ptrdiff_t(from), s.begin() + std::ptrdiff_t(from + n)};
}

}  // namespace

TEST_SUITE("modes") {
TEST_CASE("preprocess runs in order") {
    // Entities: 0 First, 1 Second, 2 First, 3 Silent, 4 Second. Orders: preprocess First 2, Second 1;
    // spawnTime First -1, Second 1; despawnTime First 0, Second -2. Silent has no callbacks.
    for (const bool optimize : {true, false}) {
        CAPTURE(optimize);
        WatchConfig config;
        config.optimize = optimize;
        auto session = prepared(fixture("preprocess_order"), config);
        CHECK(vec(session->debug_log()) ==
              std::vector<float>{1, 4, 0, 2, 100, 102, 101, 104, 201, 204, 200, 202});
    }
}

TEST_CASE("spawn and despawn times recorded") {
    auto order = prepared(fixture("preprocess_order"));
    CHECK(order->entity_count() == 5);
    CHECK(order->spawned_count() == 0);
    CHECK(vec(order->spawn_times()) == std::vector<float>{10, 11, 12, 0, 14});
    CHECK(vec(order->despawn_times()) == std::vector<float>{20, 21, 22, 0, 24});

    // 120 BPM from beat 0. Note a: beat 4, duration 2.5 (import default). Note b: beat 6, duration 1.
    auto basic = prepared(fixture("spawn_basic"));
    CHECK(vec(basic->spawn_times()) == std::vector<float>{0, 2.0f, 3.0f, 0});
    CHECK(vec(basic->despawn_times()) == std::vector<float>{0, 3.25f, 3.5f, 0});
}

TEST_CASE("times are zero before prepare") {
    auto session = WatchSession::create(fixture("preprocess_order"), {});
    CHECK_FALSE(session->prepared());
    CHECK(vec(session->spawn_times()) == std::vector<float>(5, 0.0f));
    CHECK(session->debug_log().empty());
}

TEST_CASE("prepare runs once") {
    auto session = prepared(fixture("preprocess_order"));
    session->prepare();
    CHECK(session->prepared());
    CHECK(session->debug_log().size() == 12);
}

TEST_CASE("imports land at their index with defaults") {
    // Note imports: #BEAT 0, duration 1 (def 2.5), #JUDGMENT 2 (def 3), #ACCURACY 3, prev 4.
    auto session = prepared(fixture("spawn_basic"));
    const auto data = session->block(kEntityDataArray);
    REQUIRE(data.size() == 4 * 32);
    CHECK(slice(data, 0, 3) == std::vector<float>{0, 120, 0});                // #BPM_CHANGE
    CHECK(slice(data, 32, 5) == std::vector<float>{4, 2.5f, 3, 0, 0});        // a: defaults
    CHECK(slice(data, 64, 4) == std::vector<float>{6, 1, 3, 0});              // b: #JUDGMENT/#ACCURACY ignored
    CHECK(slice(data, 69, 27) == std::vector<float>(27, 0.0f));               // datum "unknown" not imported

    const auto info = session->block(kEntityInfoArray);
    REQUIRE(info.size() == 4 * 3);
    CHECK(vec(info) == std::vector<float>{0, 0, 0, 1, 1, 0, 2, 1, 0, 3, 2, 0});  // index, archetype, inactive
}

TEST_CASE("refs resolve") {
    auto session = prepared(fixture("spawn_basic"));
    CHECK(session->block(kEntityDataArray)[64 + 4] == 1);  // b.prev = entity "a", index 1
}

TEST_CASE("preprocess may write runtime ui") {
    WatchConfig config;
    config.env.aspect = 2.0f;
    auto session = WatchSession::create(fixture("spawn_basic"), config);
    CHECK(slice(session->block(kRuntimeUi), 0, 10) == std::vector<float>(10, 0.0f));
    session->prepare();
    REQUIRE_FALSE(session->faulted());
    // Layout: anchor (aspect, 0.5), pivot (1, 1), size (0.25, 0.125), rotation 0, alpha 0.75, align 0, background.
    CHECK(slice(session->block(kRuntimeUi), 0, 10) ==
          std::vector<float>{2.0f, 0.5f, 1, 1, 0.25f, 0.125f, 0, 0.75f, 0, 1});
}

TEST_CASE("environment, options, ui configuration and rom fill their blocks") {
    data::LoadedLevel level = fixture("spawn_basic");
    level.configuration.ui.menu = {0.5f, 0.25f};
    level.configuration.ui.combo = {2.0f, 0.75f};
    level.configuration.options.resize(3);
    WatchConfig config;
    config.options = {0.5f, 9.8f, 1};
    config.env = {true, 2.25f, 0.125f, -0.25f, false, -2.0f, 2.0f, -0.875f, 1};
    auto session = WatchSession::create(level, config);
    CHECK(vec(session->block(kRuntimeEnvironment)) ==
          std::vector<float>{1, 2.25f, 0.125f, -0.25f, 0, -2.0f, 2.0f, -0.875f, 1});
    CHECK(vec(session->block(kLevelOption)) == std::vector<float>{0.5f, 9.8f, 1});
    CHECK(vec(session->block(kRuntimeUiConfiguration)) ==
          std::vector<float>{0.5f, 0.25f, 1, 1, 2.0f, 0.75f, 1, 1, 1, 1, 1, 1});
    const auto rom = session->block(kEngineRom);
    REQUIRE(rom.size() == level.rom.size());
    CHECK(std::isnan(rom[0]));  // sonolus.py's ROM starts with NaN, +Inf, -Inf
    CHECK(rom[1] == level.rom[1]);
    CHECK(rom[2] == level.rom[2]);
}

TEST_CASE("spawn function entities join the spawn queue") {
    // Emitters at 2 and 5 each spawn Effects at origin and origin + 1 (length 0.5). Effect's spawnTime
    // (order -1) runs before Emitter's; its preprocess never runs.
    auto session = prepared(fixture("spawn_function"));
    CHECK(session->entity_count() == 2);
    REQUIRE(session->spawned_count() == 4);
    CHECK(vec(session->spawn_times()) == std::vector<float>{2, 5, 2, 3, 5, 6});
    CHECK(vec(session->despawn_times()) == std::vector<float>{6, 9, 2.5f, 3.5f, 5.5f, 6.5f});
    CHECK(vec(session->debug_log()) == std::vector<float>{102, 103, 105, 106, 102, 105});
    for (int32_t e = 2; e < 6; ++e) CHECK(session->archetype_of(e) == 0);  // Effect
    const auto memory = session->block(kEntityMemory);
    REQUIRE(memory.size() == 6 * 64);
    CHECK(slice(memory, 3 * 64, 3) == std::vector<float>{3, 0.5f, 0});  // Spawn data injected into Entity Memory
}

TEST_CASE("a fault stops preparation and names the archetype") {
    data::LoadedLevel level = fixture("spawn_function");
    data::LevelEntity spin;
    spin.archetype = "Spin";
    spin.data.push_back(data::EntityDatum{"forever", false, 1, -1});
    level.level.entities.insert(level.level.entities.begin(), spin);
    auto session = WatchSession::create(level, {});
    session->prepare();
    REQUIRE(session->faulted());
    CHECK(session->fault().kind == vm::Fault::RunawayLoop);
    CHECK(session->fault().detail.find("'Spin'") != std::string::npos);
    CHECK(session->fault().detail.find("preprocess") != std::string::npos);
    CHECK(session->spawned_count() == 0);  // nothing after the fault ran
    CHECK(session->debug_log().empty());
}

TEST_CASE("an entity whose archetype the engine does not define is inert") {
    // Bestdori's engine defines no #BPM_CHANGE archetype while its levels carry one. The entity keeps its place
    // (later indices do not move) with an archetype past the engine's own, no imports and no callbacks; the
    // tempo still comes from it.
    data::LoadedLevel level = fixture("spawn_basic");
    REQUIRE(level.watch.archetypes[0].name == "#BPM_CHANGE");
    level.watch.archetypes.erase(level.watch.archetypes.begin());  // Note 0, Layout 1
    auto session = prepared(level);
    CHECK(session->entity_count() == 4);
    CHECK(session->archetype_of(0) == 2);
    CHECK(vec(session->spawn_times()) == std::vector<float>{0, 2.0f, 3.0f, 0});
    CHECK(slice(session->block(kEntityInfoArray), 0, 6) == std::vector<float>{0, 2, 0, 1, 0, 0});
    CHECK(slice(session->block(kEntityDataArray), 0, 32) == std::vector<float>(32, 0.0f));
    CHECK(session->block(kEntityDataArray)[64 + 4] == 1);  // b.prev is still entity "a", index 1
}
}  // TEST_SUITE
