// Watch stats (modes/stats.h).
#include <cmath>
#include <cstdint>
#include <vector>

#include "doctest.h"
#include "modes/stats.h"

using namespace sr;
using modes::StatConfig;
using modes::StatInput;
using modes::StatState;
using modes::Stats;
using modes::StatTimeline;

namespace {

StatConfig one_archetype(int32_t entities) {
    StatConfig c;
    c.perfect.score = 1;
    c.great.score = 0.5;
    c.good.score = 0.25;
    c.entity_score.assign(size_t(entities), 0.0f);
    c.entity_life.assign(size_t(entities) * 4, 0.0f);
    c.archetype_score = {1.0f};
    c.archetype_life = {0, 0, 0, 0};
    return c;
}

}  // namespace

TEST_SUITE("modes") {

TEST_CASE("stats count judged entities") {
    // Watch without a replay judges every level entity with input Perfect (accuracy 0) at its Entity Input
    // target time; an entity without input is not counted.
    const std::vector<float> input = {2.0f, -1, 0, 9.0f, -1, 0, 1.0f, -1, 0, 3.0f, -1, 0};
    const std::vector<uint8_t> has_input = {1, 0, 1, 1};
    const std::vector<int32_t> archetype_of = {0, 0, 0, 0};
    const std::vector<StatInput> inputs = modes::perfect_inputs(input, has_input, archetype_of);
    REQUIRE(inputs.size() == 3);
    CHECK(inputs[0].entity == 0);
    CHECK(inputs[1].entity == 2);
    CHECK(inputs[1].time == 1.0f);
    CHECK(inputs[2].judgment == 1.0f);
    CHECK(inputs[2].accuracy == 0.0f);

    // Consecutive Perfect: +0.5 for every 2 in a row, up to 4.
    StatConfig config = one_archetype(4);
    config.perfect.consecutive_score = 0.5;
    config.perfect.consecutive_step = 2;
    config.perfect.consecutive_cap = 4;
    const StatTimeline timeline(config, inputs);

    const Stats before = timeline.at(0.5f);
    CHECK(before.perfect == 0);
    CHECK(before.combo == 0);
    CHECK(before.arcade == 0);
    CHECK(before.accuracy == 0);
    CHECK(before.life == 1000);

    // Counted at the target time itself (time >= target), in time order: 2 (t 1), 0 (t 2), 3 (t 3).
    const Stats first = timeline.at(1.0f);
    CHECK(first.perfect == 1);
    CHECK(first.combo == 1);
    CHECK(first.arcade == doctest::Approx(1.0 / 4.0));  // scores 1, 1.5, 1.5 of 4
    const Stats second = timeline.at(2.5f);
    CHECK(second.perfect == 2);
    CHECK(second.max_combo == 2);
    CHECK(second.arcade == doctest::Approx(2.5 / 4.0));
    const Stats all = timeline.at(100.0f);
    CHECK(all.perfect == 3);
    CHECK(all.great + all.good + all.miss == 0);
    CHECK(all.combo == 3);
    CHECK(all.arcade == 1.0f);
    CHECK(all.accuracy == 1.0f);
    CHECK(all.life == 1000);
    // Back in time (a skip) the earlier stats return.
    CHECK(timeline.at(1.5f).perfect == 1);
}

TEST_CASE("stat update follows the judgment rules") {
    StatConfig config = one_archetype(1);
    config.archetype_life = {0, 0, -10, -100};
    StatState s = modes::initial_stat(config);
    CHECK(s.life == 1000);
    const auto judge = [&](float judgment, float accuracy) {
        modes::stat_update(s, config, StatInput{0, 0, 0, judgment, accuracy});
    };

    judge(1, 0);
    CHECK(s.perfect == 1);
    CHECK(s.consecutive_perfect == 1);
    CHECK(s.consecutive_great == 1);
    CHECK(s.consecutive_good == 1);
    CHECK(s.arcade == 1);

    judge(2, 0.02f);  // Great: breaks the Perfect run, keeps the combo
    CHECK(s.great == 1);
    CHECK(s.consecutive_perfect == 0);
    CHECK(s.consecutive_great == 2);
    CHECK(s.combo == 2);
    CHECK(s.arcade == 1.5);

    judge(3, -0.05f);  // Good: breaks the combo and the Great run, costs 10 life
    CHECK(s.good == 1);
    CHECK(s.consecutive_great == 0);
    CHECK(s.consecutive_good == 3);
    CHECK(s.combo == 0);
    CHECK(s.max_combo == 2);
    CHECK(s.arcade == 1.75);
    CHECK(s.life == 990);

    judge(0, 0);  // Miss: no score, every run broken, costs 100 life
    CHECK(s.miss == 1);
    CHECK(s.consecutive_good == 0);
    CHECK(s.arcade == 1.75);
    CHECK(s.life == 890);
    CHECK(s.accuracy_sum == doctest::Approx(0.07));

    judge(1, 0);
    CHECK(s.combo == 1);
    CHECK(s.max_combo == 2);
    CHECK(s.arcade == 2.75);
}

TEST_CASE("scheduled life changes join the stat timeline in time order") {
    StatConfig config = one_archetype(2);
    config.initial_life = 500;
    config.max_life = 1000;
    const std::vector<StatInput> inputs = {{0, 0, 2.0f, 1, 0}, {1, 0, 4.0f, 1, 0}};
    // Given out of order; the value is truncated to an int; one lands exactly on an input's time.
    const std::vector<modes::LifeEvent> events = {{900.0f, 4.0f}, {-100.9f, 1.0f}, {50.0f, 2.0f}};
    const StatTimeline timeline(config, inputs, events);
    CHECK(timeline.at(0.5f).life == 500);
    CHECK(timeline.at(1.0f).life == 400);   // -100
    CHECK(timeline.at(2.0f).life == 450);   // the input at 2 s first (no life change), then +50
    CHECK(timeline.at(2.0f).perfect == 1);
    CHECK(timeline.at(4.0f).life == 1000);  // +900 capped at the maximum
    CHECK(timeline.at(4.0f).perfect == 2);
    // Without events the timeline is as before.
    CHECK(StatTimeline(config, inputs).at(4.0f).life == 500);

    // A dead run stays dead.
    StatState dead = modes::initial_stat(config);
    dead.life = 0;
    modes::stat_add_life(dead, config, 300.0f);
    CHECK(dead.life == 0);
}

TEST_CASE("stat life steps, caps and stops at zero") {
    StatConfig config = one_archetype(1);
    config.perfect.consecutive_life = 5;
    config.perfect.consecutive_life_step = 2;
    config.initial_life = 994;
    StatState s = modes::initial_stat(config);
    const StatInput perfect{0, 0, 0, 1, 0};
    modes::stat_update(s, config, perfect);
    CHECK(s.life == 994);  // 1 in a row
    modes::stat_update(s, config, perfect);
    CHECK(s.life == 999);  // 2 in a row: +5
    modes::stat_update(s, config, perfect);
    modes::stat_update(s, config, perfect);
    CHECK(s.life == 1000);  // capped at the maximum

    config.archetype_life = {0, 0, 0, -600};
    const StatInput miss{0, 0, 0, 0, 0};
    modes::stat_update(s, config, miss);
    modes::stat_update(s, config, miss);
    CHECK(s.life == 0);  // never below 0
    modes::stat_update(s, config, perfect);
    modes::stat_update(s, config, perfect);
    CHECK(s.life == 0);  // and nothing changes it after
}

TEST_CASE("stat config reads the level blocks") {
    const std::vector<float> score = {1, 0.7f, 0.5f, 0.01f, 10, 50.9f, 0, 0, 0, 0, 0, 0};
    const std::vector<float> life = {10, 50, 0, 0, 0, 0, 900, 1200};
    const StatConfig c = modes::stat_config(score, life, {}, {}, {}, {});
    CHECK(c.perfect.score == 1);
    CHECK(c.great.score == double(0.7f));
    CHECK(c.good.score == 0.5);
    CHECK(c.perfect.consecutive_score == double(0.01f));
    CHECK(c.perfect.consecutive_step == 10);
    CHECK(c.perfect.consecutive_cap == 50);  // truncated
    CHECK(c.perfect.consecutive_life == 10);
    CHECK(c.perfect.consecutive_life_step == 50);
    CHECK(c.initial_life == 900);
    CHECK(c.max_life == 1200);
}

TEST_CASE("stats carry what the built-in hud shows") {
    // The judgment and combo widgets animate from the last judgment and the last combo change; the life bar fills
    // against the maximum life.
    StatConfig config = one_archetype(1);
    config.max_life = 1200;
    StatState s = modes::initial_stat(config);
    StatState max = s;
    Stats out = modes::stats_of(s, max);
    CHECK(out.judgment == 0);  // nothing judged yet
    CHECK(out.max_life == 1200);

    modes::stat_update(s, config, StatInput{0, 0, 2.5f, 2, 0.03f});  // a late Great
    out = modes::stats_of(s, max);
    CHECK(out.judgment == 2);
    CHECK(out.judgment_accuracy == doctest::Approx(0.03f));
    CHECK(out.judgment_time == 2.5f);
    CHECK(out.combo_time == 2.5f);

    modes::stat_update(s, config, StatInput{0, 0, 3.0f, 0, -0.1f});  // a Miss ends the combo
    out = modes::stats_of(s, max);
    CHECK(out.judgment == 4);
    CHECK(out.combo == 0);
    CHECK(out.combo_time == 3.0f);

    modes::stat_update(s, config, StatInput{0, 0, 4.0f, 3, 0});  // a Good at combo 0: the combo does not change
    out = modes::stats_of(s, max);
    CHECK(out.judgment == 3);
    CHECK(out.judgment_time == 4.0f);
    CHECK(out.combo_time == 3.0f);
}

}  // TEST_SUITE
