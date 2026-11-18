// GameplayResult and the StreamStore (wiki functions__stream-*).
#include <string>
#include <vector>

#include "doctest.h"
#include "modes/gameplay_result.h"
#include "modes/streams.h"

using namespace sr;
using modes::Grade;

namespace {

modes::StatState state(int p, int g, int good, int miss) {
    modes::StatState s;
    s.perfect = p;
    s.great = g;
    s.good = good;
    s.miss = miss;
    s.arcade = 1;
    s.max_combo = p + g;
    return s;
}

modes::StatState max_state() {
    modes::StatState m;
    m.arcade = 1;
    return m;
}

}  // namespace

TEST_CASE("judgment_timings flattens judgment and accuracy in order") {
    const modes::StatInput inputs[] = {{0, 0, 1.0f, 1.0f, -0.01f}, {1, 0, 2.0f, 0.0f, 0.1167f}};
    const std::vector<float> timings = modes::judgment_timings(inputs);
    REQUIRE(timings.size() == 4);
    CHECK(timings[0] == 1.0f);
    CHECK(timings[1] == -0.01f);
    CHECK(timings[2] == 0.0f);
    CHECK(timings[3] == 0.1167f);
    CHECK(modes::judgment_timings({}).empty());
}

TEST_CASE("all perfect grades allPerfect") {
    const modes::GameplayResult r = modes::gameplay_result(state(10, 0, 0, 0), max_state(), 10, false);
    CHECK(r.grade == Grade::AllPerfect);
    CHECK(std::string(modes::grade_name(r.grade)) == "allPerfect");
    CHECK(r.perfect == 10);
    CHECK(r.combo == 10);
    CHECK(r.total == 10);
    CHECK(r.arcade_score == 1000000);
    CHECK(r.accuracy_score == 1000000);
}

TEST_CASE("no miss or good grades fullCombo") {
    const modes::GameplayResult r = modes::gameplay_result(state(8, 2, 0, 0), max_state(), 10, false);
    CHECK(r.grade == Grade::FullCombo);
    CHECK(std::string(modes::grade_name(r.grade)) == "fullCombo");
}

TEST_CASE("a miss grades pass") {
    const modes::GameplayResult r = modes::gameplay_result(state(8, 1, 0, 1), max_state(), 10, false);
    CHECK(r.grade == Grade::Pass);
    CHECK(std::string(modes::grade_name(r.grade)) == "pass");
    CHECK(modes::gameplay_result(state(8, 1, 1, 0), max_state(), 10, false).grade == Grade::Pass);
}

TEST_CASE("failed grades fail") {
    const modes::GameplayResult r = modes::gameplay_result(state(8, 1, 0, 1), max_state(), 10, true);
    CHECK(r.grade == Grade::Fail);
    CHECK(std::string(modes::grade_name(r.grade)) == "fail");
}

TEST_CASE("scores round to the result rules") {
    // Both truncate in double, never round to nearest.
    modes::StatState f = state(10, 0, 0, 0);
    f.arcade = 0.9640404;
    CHECK(modes::gameplay_result(f, max_state(), 10, false).arcade_score == 964040);
    f.arcade = 0.99999999;
    CHECK(modes::gameplay_result(f, max_state(), 10, false).arcade_score == 999999);
    // accuracy: 1 - sum / 10 = 0.99999999 needs sum = 1e-7
    f.accuracy_sum = 1e-7;
    CHECK(modes::gameplay_result(f, max_state(), 10, false).accuracy_score == 999999);
    // a zero divisor gives 0
    modes::StatState zero;
    CHECK(modes::gameplay_result(zero, modes::StatState{}, 0, false).arcade_score == 0);
    CHECK(modes::gameplay_result(zero, modes::StatState{}, 0, false).accuracy_score == 0);
}

TEST_CASE("stream store follows the wiki") {
    modes::StreamStore s;
    s.set(1, 2, 3);
    s.set(1, 1, 5);
    CHECK(s.next_key(1, 1) == 2);
    CHECK(s.previous_key(1, 2) == 1);
    CHECK(s.next_key(1, 2) == 2);      // none after: the key itself
    CHECK(s.previous_key(1, 1) == 1);  // none before: the key itself
    CHECK(s.next_key(7, 4) == 4);      // unknown stream
    CHECK(s.value(1, 1.5f) == 4);      // between (1, 5) and (2, 3)
    CHECK(s.value(1, 0) == 5);         // before the first key: the first value
    CHECK(s.value(1, 9) == 3);         // after the last: the last value
    CHECK(s.value(1, 1) == 5);
    CHECK(s.value(7, 1) == 0);
    CHECK(s.has(1, 2) == 1);
    CHECK(s.has(1, 3) == 0);
    CHECK(s.has(7, 1) == 0);
    s.set(1, 2, 8);  // replaces
    CHECK(s.value(1, 2) == 8);
    s.set(0, 0.5f, 1);
    const auto dump = s.dump();
    REQUIRE(dump.size() == 2);
    CHECK(dump[0].id == 0);
    CHECK(dump[1].id == 1);
    CHECK(dump[1].keys == std::vector<float>{1, 2});
    CHECK(dump[1].values == std::vector<float>{5, 8});
    s.clear();
    CHECK(s.dump().empty());
}
