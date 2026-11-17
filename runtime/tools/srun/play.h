#pragma once

#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <vector>

#include "data/resources.h"
#include "modes/play_session.h"
#include "modes/replay.h"
#include "modes/replay_writer.h"
#include "modes/touch.h"
#include "watch.h"

namespace sr::tools {

// A replay turned into what a Play run needs (the replay holds frame times, not event times).
//
// Replay frame times are float32(unscaledTime) - float32(StartTime) with unscaledTime on a fixed grid (the
// user's replay: 1 / 119.9996 s). The grid and StartTime are fitted to the input times, which are exact frame times,
// so that the clock reproduces every one; each point's millisecond (the frame time truncated) then names its grid
// frame.
struct ReplayFeed {
    double t0 = 0, period = 0;          // grid frame k is at t0 + k period
    float start = 0;                    // the replay's StartTime (float); 0 = frame times unquantised
    int64_t reproduced = 0;             // input times the clock reproduces exactly
    int32_t stream_frames = 0;          // frames whose time a stream key corrects (a rounding tie of the grid)
    int64_t first = 0;                  // the first frame at or after -kPreRoll
    std::map<int64_t, float> exact;     // frames whose time an input gives exactly
    // Every point as a pointer event at its recorded time (the first a Down, the last an Up, the rest Move; pointer =
    // touch index), no later than its frame, merged in time order with ties in touch order (TouchPool applies events
    // in push order).
    std::vector<modes::TouchEvent> events;
    int32_t unmatched_points = 0;       // points whose millisecond names no grid frame (fed at their own time)
    double max_residual = 0;            // the largest |input time - grid| (s)
};
// `fps` is the nominal rate the grid is fitted around.
ReplayFeed replay_feed(const modes::Replay& replay, double fps = 120);

// Frame time of grid frame k (an input's exact float where there is one).
double feed_frame(const ReplayFeed& feed, int64_t k);

// The Play config of the replay's run: `options`, and the Runtime Environment of its play area (aspect W / H, the
// full screen as the safe area, its input offset).
modes::PlayConfig replay_play_config(const modes::Replay& replay, std::vector<float> options);

// The feed's grid from its first frame, until a frame ends (or past an hour).
FrameClock replay_clock(const ReplayFeed& feed);

struct ExportDiff {
    int32_t entity;
    std::string name;
    float ours, theirs;
};

struct PlayReport {
    std::vector<modes::StatInput> judgments;  // counting order
    modes::GameplayResult result;
    bool ended = false;
    std::string fault;  // empty if none
    int64_t frames = 0;
    std::vector<modes::ReplayStream> streams;  // the session's StreamStore at the end (dump())
    bool streams_equal = false;
    int64_t exports_compared = 0;
    std::vector<ExportDiff> export_diffs;
    std::vector<std::string> skipped_exports;  // replay names not compared (entity 0's replayRevision, unexported)
    // Inputs whose judgment differs from the replay's or whose accuracy is more than 1 ulp of float32 away, and
    // replay inputs we never counted.
    int32_t mismatches = 0;
    // With a recorder: the run's replay (duration = the last frame's time, the ending frame's when it ended; the play
    // area and input offset of `replay` and the config).
    std::optional<modes::ReplayData> written;
};

// Runs a fresh Play session of `level` on `clock`, pushing `events` into a TouchPool and giving each frame the
// pool's touches, until the session ends; then compares judgments, streams and exports with `replay`. A `recorder`
// gets the touches of every frame that ran (not the ending one) and fills PlayReport::written.
PlayReport run_play(const data::LoadedLevel& level, const modes::Replay& replay, const modes::PlayConfig& config,
                    const FrameClock& clock, const std::vector<modes::TouchEvent>& events,
                    modes::TouchRecorder* recorder = nullptr);

// `values` as a replay file gives them back: each the float difference from the one before (from 0), summed again
// in float.
std::vector<float> delta_round_trip(const std::vector<float>& values);

// True when |ours - theirs| is at most one float32 ulp at `theirs`.
bool within_ulp(float ours, float theirs);

// {"entity", "time", "judgment", "accuracy"} and '\n' (floats in hex_float form).
std::string judgment_line(const modes::StatInput& input);
// {"result": {...}, "streams_equal", "exports_equal", "mismatches"} and '\n'.
std::string play_summary_line(const PlayReport& report);

}  // namespace sr::tools
