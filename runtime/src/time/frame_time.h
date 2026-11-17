#pragma once

namespace sr::time {

// One frame's Runtime Update values (watch-blocks__runtime-update): time, delta time, scaled time and skip.
// The frame clock produces them; the modes write them into Runtime Update before the frame's callbacks.
struct FrameTime {
    float time = 0;
    float delta = 0;
    float scaled = 0;
    bool skip = false;
};

}  // namespace sr::time
