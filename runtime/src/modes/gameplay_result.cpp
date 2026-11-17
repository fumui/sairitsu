#include "modes/gameplay_result.h"

namespace sr::modes {

const char* grade_name(Grade grade) {
    switch (grade) {
        case Grade::AllPerfect: return "allPerfect";
        case Grade::FullCombo: return "fullCombo";
        case Grade::Pass: return "pass";
        case Grade::Fail: return "fail";
    }
    return "fail";
}

GameplayResult gameplay_result(const StatState& final, const StatState& max, int32_t total, bool failed) {
    GameplayResult r;
    // The result arcade/accuracy rounding and the grade rule.
    r.arcade_score = max.arcade == 0 ? 0 : int32_t(final.arcade / max.arcade * 1e6);
    const int32_t judged = final.perfect + final.great + final.good + final.miss;
    r.accuracy_score = judged == 0 ? 0 : int32_t((1.0 - final.accuracy_sum / double(judged)) * 1e6);
    if (final.great + final.good + final.miss == 0) r.grade = Grade::AllPerfect;
    else if (final.good + final.miss == 0) r.grade = Grade::FullCombo;
    else r.grade = failed ? Grade::Fail : Grade::Pass;
    r.combo = final.max_combo;
    r.perfect = final.perfect;
    r.great = final.great;
    r.good = final.good;
    r.miss = final.miss;
    r.total = total;
    return r;
}

}  // namespace sr::modes
