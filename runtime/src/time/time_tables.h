#pragma once

#include <vector>

#include "data/engine_data.h"
#include "data/level_data.h"

namespace sr::time {

// BPM and timescale tables built from the level's `#BPM_CHANGE` (`#BEAT`, `#BPM`) and `#TIMESCALE_CHANGE`
// (`#BEAT`, `#TIMESCALE`) entities, as the watch-data archetype wiki page defines them (timescale groups are
// not modelled). Section start times are kept in double and evaluated in double, rounding to float once at
// the end:
//
//   section times   T_0 = 0 at the first BPM change, T_i = T_(i-1) + double(float(b_i - b_(i-1))) * 60 / bpm_(i-1)
//   BeatToTime(x)   float(T + double(float(x - b)) * 60 / double(bpm))
//   scaled times    a timescale change sits at its beat's unrounded double time T'; S_0 = T'_0 (scaled time
//                   runs on from the unscaled time before the first change), S_i = S_(i-1) + (T'_i - T'_(i-1)) * scale_(i-1)
//   TimeToScaledTime(t)  float(S + (double(t) - T') * double(scale))
//
// A section is the last change at or before the argument (a NaN argument, for which no change is "after", falls
// in the last section). Before the first change there is no section: BeatToTime gives float(double(x) * 60 / 0)
// (an infinity, NaN at 0), BeatToBPM, BeatToStartingBeat and BeatToStartingTime give 0, TimeToScaledTime gives t,
// TimeToTimeScale 1, TimeToStartingTime and TimeToStartingScaledTime 0. A level without BPM changes is all
// "before the first change" (BeatToTime is infinite).
class TimeTables {
public:
    // Throws data::LoadError when a change entity has neither the datum nor an archetype import default for it.
    static TimeTables build(const data::LevelData& level, const data::EngineModeData& engine);

    float beat_to_time(float beat) const;
    float beat_to_bpm(float beat) const;
    float beat_to_starting_beat(float beat) const;
    float beat_to_starting_time(float beat) const;
    float time_to_scaled_time(float time) const;
    float time_to_starting_scaled_time(float time) const;
    float time_to_starting_time(float time) const;
    float time_to_time_scale(float time) const;

private:
    struct BpmSection {
        float beat = 0, bpm = 0;  // starting beat, bpm
        double time = 0;          // starting time
    };
    struct ScaleSection {
        double time = 0;  // starting time
        float scale = 1;
        double scaled = 0;  // starting scaled time
    };

    // The section covering x, or nullptr before the first change.
    const BpmSection* bpm_at(float beat) const;
    const ScaleSection* scale_at(float time) const;
    double beat_to_time_double(float beat) const;

    std::vector<BpmSection> bpm_;
    std::vector<ScaleSection> scale_;
};

}  // namespace sr::time
