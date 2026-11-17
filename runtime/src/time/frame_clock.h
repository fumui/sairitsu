#pragma once

#include "time/frame_time.h"
#include "time/time_tables.h"

namespace sr::time {

// Watch starts kPreRoll seconds before music time 0, and the
// picture runs through those negative times. Its end skips back to exactly -2 s.
constexpr double kPreRoll = 2.0;  // s

// Turns the music time at each frame's expected present time into the frame's Runtime Update values. `time` never
// decreases (a backwards step of the audio clock repeats the last time) and starts no lower than `start`; `delta`
// is the difference from the previous frame's `time`, and on the first frame the display's frame period (1/120 s
// at 120 Hz); `scaled` comes from the level's timescale table.
class FrameClock {
public:
    // `times` must outlive the clock.
    explicit FrameClock(const TimeTables& times, float start = float(-kPreRoll));

    // `frame_period`: the display's frame period in seconds, used as the first frame's delta.
    FrameTime next(double music_time_at_present, float frame_period = 0);

    // A loop: the frame at exactly `time`: delta 0 and skip set. The next
    // `next` goes on from `time`.
    FrameTime skip_to(float time);

    // Play's retry: the clock starts again at `start`, as a new run's does (the first frame's delta is the frame
    // period).
    void restart(float start) {
        last_ = start;
        first_ = true;
    }

private:
    const TimeTables& times_;
    float last_;
    bool first_ = true;
};

}  // namespace sr::time
