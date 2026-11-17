#pragma once

#include <cstdint>
#include <span>
#include <string>
#include <map>
#include <utility>
#include <vector>

#include "data/resources.h"
#include "modes/gameplay_result.h"
#include "modes/play_session.h"
#include "modes/replay.h"
#include "modes/stats.h"
#include "modes/touch.h"

namespace sr::modes {

// One recorded touch point: ms of Play time (input offset removed), integer px from the play-area centre, y up.
struct RecordedPoint {
    int32_t t_ms, x, y;
};

struct RecordedTouch {
    std::vector<RecordedPoint> points;
};

// The touches of a Play run, as a replay records them.
class TouchRecorder {
public:
    // The play area in px (x and y in screen units are px / (height / 2), so only the height scales) and the input
    // offset (s) the run's Runtime Environment holds.
    TouchRecorder(int32_t width, int32_t height, float input_offset);
    // Every touch of a frame that ran its systems (not the ending frame, nothing while paused) adds the point
    // {trunc((frame_time - input_offset) x 1000), trunc(x px), trunc(y px)} to its touch id's list; when the list's
    // last two points have the new (x, y), the last point is replaced instead. The px are those the screen units came
    // from: a product within float rounding of a whole px is that px.
    void frame(double frame_time, std::span<const Touch> touches);
    // Retry: forget every touch.
    void clear();
    // In first-seen touch-id order.
    const std::vector<RecordedTouch>& touches() const { return touches_; }

private:
    float half_height_, input_offset_;
    std::vector<RecordedTouch> touches_;
    // Touch id -> index in touches_. std::map, not unordered_map: its rehash calls the platform ceilf.
    std::map<int32_t, size_t> by_id_;
};

// Everything a replay file holds about one Play run.
struct ReplayData {
    double duration = 0;
    float input_offset = 0;
    int32_t width = 0, height = 0;
    GameplayResult result;
    std::vector<StatInput> inputs;                                     // counting order
    std::vector<std::vector<std::pair<std::string, float>>> entities;  // one per level entity: export name, value
    std::vector<ReplayStream> streams;                                 // the StreamStore's keys and values (dump())
    std::vector<RecordedTouch> touches;
};

// The replay of `session`'s run so far: its result, judgments, streams and touches, and each level entity's exports
// named through its archetype's `exports` list (an index the list doesn't name is left out).
ReplayData replay_data(const PlaySession& session, const data::LoadedLevel& level, const TouchRecorder& touches,
                       double duration, int32_t width, int32_t height, float input_offset);

// The gzip JSON replay file (parse_replay reads it back). Throws std::runtime_error if a
// value can't be written (NaN or infinity).
std::vector<uint8_t> encode_replay(const ReplayData& data);

// The gzip JSON replay config: {"options": [...]}.
std::vector<uint8_t> encode_replay_config(std::span<const float> options);

}  // namespace sr::modes
