#pragma once

#include <cstdint>
#include <filesystem>
#include <functional>
#include <iosfwd>
#include <map>
#include <optional>
#include <string>
#include <vector>

#include "data/resources.h"
#include "modes/watch_session.h"
#include "time/frame_time.h"
#include "time/time_tables.h"

namespace sr::tools {

// The music time of frame `frame` (0, 1, ...), or nullopt to stop. `ended` is FrameOutput::ended of the frame
// before (false for frame 0).
using FrameClock = std::function<std::optional<double>(int64_t frame, bool ended)>;

// -kPreRoll + frame / fps (where live playback starts), until a frame ends (or past an hour of music time).
FrameClock fixed_rate(double fps);
// The listed times in order, then stop (an ended frame doesn't stop it).
FrameClock listed(std::vector<double> times);
// One time per non-blank line. Throws std::runtime_error on a line that isn't a number.
std::vector<double> read_times(std::istream& in);

// Runtime Update for a frame at music time `t` after one at `previous` (none for the first frame).
time::FrameTime frame_time(double t, std::optional<double> previous, const time::TimeTables& times);

struct WatchSummary {
    std::string hash;         // sha256 of every frame line (dump.h), in order
    int64_t frames = 0;       // frame lines (the ended frame's included)
    int64_t spawned = 0;      // spawn events (an entity respawning counts again)
    int64_t despawned = 0;
    int64_t max_active = 0;   // most entities active at once
    int64_t active_at_end = 0;  // entities active after the last frame (the end runs no terminate)
    std::vector<int32_t> still_active;  // those entities, ascending
    float last_time = 0;      // the music time of the last frame that ran (not the ended one)
    bool ended = false;       // the last frame's FrameOutput::ended
    int32_t level_entities = 0;
    std::vector<int32_t> spawns;  // per queued entity (level entities, then Spawn-function ones): spawn events
    std::vector<float> spawn_times, despawn_times;  // per queued entity, at the end
    std::string fault;        // the session's fault, empty if none
};

// Runs a fresh Watch session of `level` frame by frame on `clock`, writing each frame line to `dump` (if any), up to
// the clock's end. The ended frame runs nothing; live Watch would loop from there, srun stops. A fault
// stops the run. Throws data::LoadError when the session can't be
// created or prepared.
WatchSummary run_watch(const data::LoadedLevel& level, const modes::WatchConfig& config, const FrameClock& clock,
                       std::ostream* dump);

// The last dump line: {"hash", "frames", "spawned", "despawned", "max_active", "active_at_end", "fault"} and '\n'.
std::string summary_line(const WatchSummary& summary);

}  // namespace sr::tools
