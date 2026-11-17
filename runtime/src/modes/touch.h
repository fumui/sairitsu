#pragma once

#include <cstdint>
#include <functional>
#include <map>
#include <set>
#include <vector>

namespace sr::modes {

// One touch as Runtime Touch Array holds it (play-blocks__runtime-touch-array), fields in block order (15 floats per
// touch, `started` and `ended` as 0 or 1).
struct Touch {
    float id;
    bool started, ended;
    float t, st, x, y, sx, sy, dx, dy, vx, vy, vr, vw;
};

// A pointer event. t is music time (input offset already applied), x and y are screen units.
struct TouchEvent {
    int32_t pointer;
    enum Phase : uint8_t { Down, Move, Up, Cancel } phase;
    double t;
    float x, y;
};

// Turns pointer events into each cycle's touches.
class TouchPool {
public:
    explicit TouchPool(int32_t capacity = 32);
    // Any order of pointers; the events of one pointer in time order.
    void push(const TouchEvent& e);
    // The touches of the update cycle at frame_time, ascending id. Events with t > frame_time wait.
    std::vector<Touch> frame(double frame_time);
    // Pause, retry: forget everything pending and down.
    void clear();

private:
    struct Track {
        int32_t id = 0;
        double st = 0, t = 0;
        float sx = 0, sy = 0, x = 0, y = 0;
        bool ended = false;
        bool reported = false;  // present in the previous frame
        double prev_t = 0;
        float prev_x = 0, prev_y = 0;
    };

    void apply(const TouchEvent& e);
    Touch finish(const Track& tr) const;

    int32_t capacity_;
    int32_t next_id_ = 101;
    std::vector<TouchEvent> pending_;
    std::map<int32_t, Track> tracks_;  // by pointer
    std::set<int32_t> ignored_;        // pointers that went down while the pool was full
    std::vector<Touch> done_;          // touches that ended and were replaced by a new Down within this frame
};

// A pointer event as the UI thread queues it: CLOCK_MONOTONIC time, pointer id, phase and surface pixels.
struct RawTouch {
    int64_t ns;
    int32_t pointer;
    TouchEvent::Phase phase;
    float x, y;
};

// The Phase values the UI thread sends (Kotlin's TouchPhase codes): the cast from a float code relies on them.
static_assert(TouchEvent::Down == 0 && TouchEvent::Move == 1 && TouchEvent::Up == 2 && TouchEvent::Cancel == 3);

// X = (px - W/2) / (H/2), y = (H/2 - py) / (H/2) for a W x H surface (Android's y grows downward), and
// t = `heard` (the frame-timeline music time of the event, see drain_touches) minus the input offset.
TouchEvent to_touch_event(const RawTouch& raw, int32_t width, int32_t height, double heard, float input_offset);

// Turns the queued `raw` events into pool events and empties `raw`. `heard_at` maps a CLOCK_MONOTONIC time to the
// music time heard then (the map a frame's time comes from, at its expected present time); `pipeline_ns` is the
// current frame's expected present time minus its vsync time. While `paused` they are dropped and the pool forgets
// everything pending and down.
void drain_touches(std::vector<RawTouch>& raw, TouchPool& pool, bool paused, int32_t width, int32_t height,
                   float input_offset, int64_t pipeline_ns, const std::function<double(int64_t)>& heard_at);

}  // namespace sr::modes
