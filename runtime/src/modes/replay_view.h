#pragma once

#include <vector>

#include "modes/replay.h"

namespace sr::modes {

// What Watch shows of a replay besides the run itself (saved replays, design sections 5 and 6). Pure.

// A touch marker: where a replay touch is at the current time, in screen units of the current play area (x in
// -aspect..aspect, y up in -1..1).
struct ReplayMarker {
    float x, y;
};

// The touches alive at `time` (first point t <= time <= last point t), each linearly interpolated between the points
// around `time`, and mapped from the replay's play area (aspect W/H) onto one of `aspect` as a replay reader does
// (the play area onto the screen): x' = x * aspect / (W/H), y' = y. A replay without a play area size is not
// mapped.
std::vector<ReplayMarker> replay_markers(const Replay& replay, double time, float aspect);

// A tick on Watch's seek track: a Miss (judgment 0) or a Good (judgment 3) input at its time. Greats and Perfects are
// not ticked.
struct WatchTick {
    float time;
    bool miss;
};

// The replay's Miss and Good inputs, in input order.
std::vector<WatchTick> replay_ticks(const Replay& replay);

// A seek target: `t` clamped to [-kPreRoll, end] (a NaN goes to -kPreRoll).
double clamp_seek(double t, double end);

}  // namespace sr::modes
