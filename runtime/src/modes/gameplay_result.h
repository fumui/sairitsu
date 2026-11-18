#pragma once

#include <cstdint>
#include <span>
#include <vector>

#include "modes/stats.h"

namespace sr::modes {

enum class Grade : uint8_t { AllPerfect, FullCombo, Pass, Fail };

// "allPerfect", "fullCombo", "pass", "fail" (the replay file's names).
const char* grade_name(Grade grade);

struct GameplayResult {
    Grade grade = Grade::Fail;
    int32_t arcade_score = 0, accuracy_score = 0, combo = 0, perfect = 0, great = 0, good = 0, miss = 0, total = 0;
};

// The result of a run. `max` is the all-Perfect state (MaxStat); `failed` means the final life is 0 or less.
// arcade = (int)(arcade / max.arcade x 1e6) and accuracy = (int)((1 - sum|accuracy| / judged) x 1e6), in double,
// truncated; either is 0 when its divisor is 0. Grade: AllPerfect when Great + Good + Miss = 0, else FullCombo when
// Good + Miss = 0, else Pass unless `failed`, else Fail. `combo` is the run's maximum combo.
GameplayResult gameplay_result(const StatState& final, const StatState& max, int32_t total, bool failed);

// Each input's judgment and accuracy (s, positive late), flattened in counting order: j0, a0, j1, a1, ...
std::vector<float> judgment_timings(std::span<const StatInput> inputs);

}  // namespace sr::modes
