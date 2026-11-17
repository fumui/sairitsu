#include "time/frame_clock.h"

#include <algorithm>

namespace sr::time {

FrameClock::FrameClock(const TimeTables& times, float start) : times_(times), last_(start) {}

FrameTime FrameClock::next(double music_time_at_present, float frame_period) {
    // Time is the music time at the frame's present time, rounded to float; delta is the
    // difference of consecutive frame times and the frame period on the first frame (exactly 1/120 s per
    // frame at 120 Hz, first frame included). Held when the audio clock steps back; skip is never set (dropped
    // frames are not handled specially).
    FrameTime f;
    f.time = std::max(float(music_time_at_present), last_);
    f.delta = first_ ? frame_period : f.time - last_;
    f.scaled = times_.time_to_scaled_time(f.time);
    f.skip = false;
    last_ = f.time;
    first_ = false;
    return f;
}

FrameTime FrameClock::skip_to(float time) {
    FrameTime f;
    f.time = time;
    f.delta = 0;
    f.scaled = times_.time_to_scaled_time(time);
    f.skip = true;
    last_ = time;
    first_ = false;
    return f;
}

}  // namespace sr::time
