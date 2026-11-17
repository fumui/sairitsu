#pragma once

#include <cstdint>
#include <optional>
#include <span>
#include <vector>

namespace sr::modes {

// The stats the built-in UI shows: combo, judgment counts, arcade score, accuracy and life.
// `arcade` is the arcade score as a fraction of the level's maximum (the UI shows it times 1,000,000); `accuracy` is
// 1 - (sum of |accuracy|) / (number of judged entities), 0 before any; `life` is the life value (an integer).
// For the judgment and combo widgets: `judgment` is the last judgment (1 Perfect, 2 Great, 3 Good, 4 Miss, 0 none
// yet), `judgment_accuracy` its accuracy (s, positive late) and `judgment_time` its music time; `combo_time` is the
// music time the combo last changed. `max_life` fills the life bar.
struct Stats {
    int32_t combo = 0, max_combo = 0, perfect = 0, great = 0, good = 0, miss = 0;
    float arcade = 0, accuracy = 0, life = 0, max_life = 0;
    int32_t judgment = 0;
    float judgment_accuracy = 0, judgment_time = 0, combo_time = 0;
};

// The scoring and life rules of one judgment (Level Score / Level Life, watch-blocks__level-score/-life).
struct JudgmentConfig {
    double score = 0, consecutive_score = 0;
    int32_t consecutive_step = 0, consecutive_cap = 0;
    int32_t consecutive_life = 0, consecutive_life_step = 0;
};

// Everything a judgment is counted against, read once after preprocess.
struct StatConfig {
    int32_t initial_life = 1000, max_life = 1000;
    JudgmentConfig perfect, great, good;
    std::vector<float> entity_score;     // 1 per level entity (Entity Score)
    std::vector<float> entity_life;      // 4 per level entity: Perfect, Great, Good, Miss increments (Entity Life)
    std::vector<float> archetype_score;  // 1 per archetype (Archetype Score)
    std::vector<float> archetype_life;   // 4 per archetype (Archetype Life)
};

// Level Score (12 floats) and Level Life (8 floats) as the blocks hold them, plus the per-entity and per-archetype
// blocks; integer fields are converted by truncation toward zero (to_index).
StatConfig stat_config(std::span<const float> level_score, std::span<const float> level_life,
                       std::vector<float> entity_score, std::vector<float> entity_life,
                       std::vector<float> archetype_score, std::vector<float> archetype_life);

// One judged entity: judgment 1 Perfect, 2 Great, 3 Good, anything else Miss.
struct StatInput {
    int32_t entity = 0, archetype = 0;
    float time = 0, judgment = 1, accuracy = 0;
};

// The running counts after some judgments .
struct StatState {
    int32_t perfect = 0, great = 0, good = 0, miss = 0;
    int32_t consecutive_perfect = 0, consecutive_great = 0, consecutive_good = 0;
    double arcade = 0, accuracy_sum = 0;
    int32_t life = 0, combo = 0, max_combo = 0;
    // For the built-in UI only (no rule reads them): see Stats.
    int32_t max_life = 0, last_judgment = 0;
    float last_accuracy = 0, last_time = 0, combo_time = 0;
};

StatState initial_stat(const StatConfig& config);
// Counts one judgment.
void stat_update(StatState& stat, const StatConfig& config, const StatInput& input);

// A life change an engine scheduled with AddLifeScheduled (in preprocess): `value` (truncated to an int) is added to
// the life when the running time reaches `time`.
struct LifeEvent {
    float value = 0, time = 0;
};
// Adds a scheduled life change under the same rules as a judgment's (nothing once life is 0, capped at the
// maximum, never below 0).
void stat_add_life(StatState& stat, const StatConfig& config, float value);

// The judged entities of Watch without a replay: every level entity whose archetype has input, judged Perfect
// with accuracy 0 at its Entity Input target time (index 0), in entity order.
std::vector<StatInput> perfect_inputs(std::span<const float> entity_input, std::span<const uint8_t> has_input,
                                    std::span<const int32_t> archetype_of);

// The Stats the UI shows for a running state; `max` is the all-Perfect state the arcade fraction is taken against.
Stats stats_of(const StatState& state, const StatState& max);

// The stats over a run: the inputs counted in time order, and the stats at a music time are those of every input
// whose time is at or before it. The maximum (for the arcade fraction) is every input counted.
class StatTimeline {
public:
    StatTimeline() = default;
    // `life_events` join the inputs in time order; at equal times the inputs come first.
    // `max` is the all-Perfect state the arcade fraction is taken against (MaxStat); without it the run itself
    // is the maximum (Watch without a replay).
    StatTimeline(StatConfig config, std::vector<StatInput> inputs, std::vector<LifeEvent> life_events = {},
                 std::optional<StatState> max = std::nullopt);
    Stats at(float time) const;
    // The state after every input (the initial one when there are none), and the maximum.
    StatState final_state() const { return after_.empty() ? initial_stat(config_) : after_.back(); }
    const StatState& max_state() const { return max_; }

private:
    StatConfig config_;
    std::vector<float> times_;      // per counted input, ascending
    std::vector<StatState> after_;  // the state after each input
    StatState max_;
};

}  // namespace sr::modes
