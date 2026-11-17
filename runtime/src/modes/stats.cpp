#include "modes/stats.h"

#include <algorithm>
#include <cmath>
#include <utility>

#include "vm/convert.h"

namespace sr::modes {

namespace {

constexpr float kPerfect = 1, kGreat = 2, kGood = 3;

float at_or_zero(const std::vector<float>& values, size_t i) { return i < values.size() ? values[i] : 0.0f; }

void apply_life(StatState& s, const StatConfig& c, int32_t delta) {
    if (delta == 0) return;
    const int32_t life = std::min(c.max_life, delta + s.life);
    if (life != s.life) s.life = std::max(life, 0);
}

JudgmentConfig judgment(std::span<const float> score, std::span<const float> life, size_t score_at, size_t consecutive_at,
                        size_t life_at) {
    const auto get = [](std::span<const float> v, size_t i) { return i < v.size() ? v[i] : 0.0f; };
    JudgmentConfig j;
    j.score = double(get(score, score_at));
    j.consecutive_score = double(get(score, consecutive_at));
    j.consecutive_step = vm::to_index(get(score, consecutive_at + 1));
    j.consecutive_cap = vm::to_index(get(score, consecutive_at + 2));
    j.consecutive_life = vm::to_index(get(life, life_at));
    j.consecutive_life_step = vm::to_index(get(life, life_at + 1));
    return j;
}

// 1 + multiplier * (min(run, cap) / step), or 1 when any of them is 0 (integer division).
double consecutive_factor(const JudgmentConfig& j, int32_t run) {
    if (j.consecutive_score == 0 || j.consecutive_step == 0) return 1.0;
    const int32_t n = std::min(run, j.consecutive_cap);
    if (n == 0) return 1.0;
    return j.consecutive_score * double(n / j.consecutive_step) + 1.0;
}

// The consecutive life increment when the run has just reached a multiple of the step, else 0.
int32_t consecutive_life(const JudgmentConfig& j, int32_t run) {
    if (j.consecutive_life == 0 || j.consecutive_life_step == 0 || run == 0) return 0;
    return run / j.consecutive_life_step == (run - 1) / j.consecutive_life_step ? 0 : j.consecutive_life;
}

}  // namespace

StatConfig stat_config(std::span<const float> level_score, std::span<const float> level_life,
                       std::vector<float> entity_score, std::vector<float> entity_life,
                       std::vector<float> archetype_score, std::vector<float> archetype_life) {
    StatConfig c;
    // Level Score: Perfect, Great, Good multipliers at 0-2, then (multiplier, step, cap) for each from 3;
    // Level Life: (increment, step) for each from 0, initial life at 6, max life at 7.
    c.perfect = judgment(level_score, level_life, 0, 3, 0);
    c.great = judgment(level_score, level_life, 1, 6, 2);
    c.good = judgment(level_score, level_life, 2, 9, 4);
    c.initial_life = vm::to_index(level_life.size() > 6 ? level_life[6] : 1000.0f);
    c.max_life = vm::to_index(level_life.size() > 7 ? level_life[7] : 1000.0f);
    c.entity_score = std::move(entity_score);
    c.entity_life = std::move(entity_life);
    c.archetype_score = std::move(archetype_score);
    c.archetype_life = std::move(archetype_life);
    return c;
}

StatState initial_stat(const StatConfig& config) {
    StatState s;
    s.life = config.initial_life;
    s.max_life = config.max_life;
    return s;
}

void stat_update(StatState& s, const StatConfig& c, const StatInput& in) {
    // Counts, then arcade score, accuracy, life, combo, in that order.
    const float j = in.judgment;
    const JudgmentConfig* scored = nullptr;
    int32_t life_slot = 3;
    if (j == kPerfect) {
        ++s.perfect;
        ++s.consecutive_perfect;
        ++s.consecutive_great;
        ++s.consecutive_good;
        scored = &c.perfect;
        life_slot = 0;
    } else if (j == kGreat) {
        ++s.great;
        s.consecutive_perfect = 0;
        ++s.consecutive_great;
        ++s.consecutive_good;
        scored = &c.great;
        life_slot = 1;
    } else if (j == kGood) {
        ++s.good;
        s.consecutive_perfect = 0;
        s.consecutive_great = 0;
        ++s.consecutive_good;
        scored = &c.good;
        life_slot = 2;
    } else {
        ++s.miss;
        s.consecutive_perfect = s.consecutive_great = s.consecutive_good = 0;
    }

    // Arcade score: judgment multiplier x (consecutive multiplier + entity + archetype multipliers), where the
    // consecutive multiplier is the product of the three runs' factors minus 1.
    if (scored != nullptr && scored->score != 0) {
        double m = consecutive_factor(c.perfect, s.consecutive_perfect) * consecutive_factor(c.great, s.consecutive_great);
        m = m * consecutive_factor(c.good, s.consecutive_good);
        m = m + double(at_or_zero(c.entity_score, size_t(in.entity)));
        m = m + double(at_or_zero(c.archetype_score, size_t(in.archetype)));
        m = m + -1.0;
        const double score = scored->score * m;
        if (score != 0) s.arcade = score + s.arcade;
    }
    s.accuracy_sum = s.accuracy_sum + double(std::fabs(in.accuracy));

    // Life: the entity's and archetype's increment for the judgment, plus the run's consecutive increment; nothing
    // once life is 0; capped at the maximum, never below 0.
    if (s.life >= 1) {
        int32_t delta = vm::to_index(at_or_zero(c.entity_life, size_t(in.entity) * 4 + size_t(life_slot))) +
                        vm::to_index(at_or_zero(c.archetype_life, size_t(in.archetype) * 4 + size_t(life_slot)));
        if (life_slot == 0) delta += consecutive_life(c.perfect, s.consecutive_perfect);
        if (life_slot == 1) delta += consecutive_life(c.great, s.consecutive_great);
        if (life_slot == 2) delta += consecutive_life(c.good, s.consecutive_good);
        apply_life(s, c, delta);
    }

    // Combo: Perfect and Great extend it, Good and Miss end it.
    const int32_t combo_before = s.combo;
    if (j == kPerfect || j == kGreat) {
        ++s.combo;
        s.max_combo = std::max(s.max_combo, s.combo);
    } else {
        s.combo = 0;
    }

    // The built-in UI's judgment and combo widgets.
    s.last_judgment = j == kPerfect ? 1 : j == kGreat ? 2 : j == kGood ? 3 : 4;
    s.last_accuracy = in.accuracy;
    s.last_time = in.time;
    if (s.combo != combo_before) s.combo_time = in.time;
}

void stat_add_life(StatState& s, const StatConfig& c, float value) {
    // AddLifeScheduled commands are merged into the snapshot list; the life update's rules apply (the same gate
    // as a judgment's).
    if (s.life >= 1) apply_life(s, c, vm::to_index(value));
}

std::vector<StatInput> perfect_inputs(std::span<const float> entity_input, std::span<const uint8_t> has_input,
                                    std::span<const int32_t> archetype_of) {
    // Without a replay every input entity is a Perfect with
    // accuracy 0; its time is Entity Input index 0 (target time) as preprocess left it.
    std::vector<StatInput> out;
    for (size_t e = 0; e < has_input.size(); ++e) {
        if (has_input[e] == 0 || e * 3 >= entity_input.size()) continue;
        out.push_back(StatInput{int32_t(e), e < archetype_of.size() ? archetype_of[e] : 0, entity_input[e * 3], 1, 0});
    }
    return out;
}

StatTimeline::StatTimeline(StatConfig config, std::vector<StatInput> inputs, std::vector<LifeEvent> life_events,
                           std::optional<StatState> max)
    : config_(std::move(config)) {
    // Sorted by time only; ties keep entity order. All-Perfect Watch
    // inputs give the same counts in any order, except where a life cap would cut a consecutive increment.
    std::stable_sort(inputs.begin(), inputs.end(),
                     [](const StatInput& a, const StatInput& b) { return a.time < b.time; });
    std::stable_sort(life_events.begin(), life_events.end(),
                     [](const LifeEvent& a, const LifeEvent& b) { return a.time < b.time; });
    StatState s = initial_stat(config_);
    times_.reserve(inputs.size() + life_events.size());
    after_.reserve(inputs.size() + life_events.size());
    size_t i = 0, l = 0;
    while (i < inputs.size() || l < life_events.size()) {
        // The next event in time order; an input goes first at equal times.
        if (l == life_events.size() || (i < inputs.size() && inputs[i].time <= life_events[l].time)) {
            stat_update(s, config_, inputs[i]);
            times_.push_back(inputs[i++].time);
        } else {
            stat_add_life(s, config_, life_events[l].value);
            times_.push_back(life_events[l++].time);
        }
        after_.push_back(s);
    }
    max_ = max ? *max : s;  // Watch without a replay: the maximum is the run itself
}

Stats StatTimeline::at(float time) const {
    // Every input whose time is at or before `time` (the update advances while the next time <= time).
    const size_t n = size_t(std::upper_bound(times_.begin(), times_.end(), time) - times_.begin());
    const StatState s = n == 0 ? initial_stat(config_) : after_[n - 1];
    return stats_of(s, max_);
}

Stats stats_of(const StatState& s, const StatState& max) {
    Stats out;
    out.combo = s.combo;
    out.max_combo = s.max_combo;
    out.perfect = s.perfect;
    out.great = s.great;
    out.good = s.good;
    out.miss = s.miss;
    out.arcade = max.arcade == 0 ? 0.0f : float(s.arcade / max.arcade);
    const int32_t total = s.perfect + s.great + s.good + s.miss;
    out.accuracy = total == 0 ? 0.0f : float(1.0 - s.accuracy_sum / double(total));
    out.life = float(s.life);
    out.max_life = float(s.max_life);
    out.judgment = s.last_judgment;
    out.judgment_accuracy = s.last_accuracy;
    out.judgment_time = s.last_time;
    out.combo_time = s.combo_time;
    return out;
}

}  // namespace sr::modes
