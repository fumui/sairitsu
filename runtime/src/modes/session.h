#pragma once

#include <vector>

#include "modes/presentation.h"
#include "modes/session_host.h"
#include "time/frame_time.h"
#include "time/time_tables.h"
#include "vm/interpreter.h"

namespace sr::modes {

// Runtime Environment (watch-blocks__runtime-environment), in block order.
// The app fills these with the device's values; the defaults are a full 16:9
// screen with no inset. Index 4 is `replay` in Watch; Play writes Multiplayer there, always 0.
struct Environment {
    bool debug = false;
    float aspect = 16.0f / 9.0f;
    float audio_offset = 0, input_offset = 0;
    bool replay = false;
    float safe_x_min = -16.0f / 9.0f, safe_x_max = 16.0f / 9.0f, safe_y_min = -1, safe_y_max = 1;
};

// What the gameplay loop, render-at-time and srun drive: one run of a level in Watch or Play. WatchSession is the
// Watch implementation; PlaySession is the Play one. Both hold a SessionCore.
class Session {
public:
    virtual ~Session() = default;

    // Preparation, once (later calls do nothing).
    virtual void prepare() = 0;
    // One update cycle, preparing first if needed. The output stays valid until the next step.
    virtual const FrameOutput& step(const time::FrameTime& frame) = 0;
    // Music time at which the level ends.
    virtual float end_time() const = 0;
    // The audio commands preparation scheduled (a loop re-submits them).
    virtual const std::vector<AudioCmd>& preprocess_audio() const = 0;
    virtual const time::TimeTables& times() const = 0;
    virtual const ResourceMaps& resources() const = 0;
    virtual const vm::Fault& fault() const = 0;
    bool faulted() const { return fault().kind != vm::Fault::None; }
};

}  // namespace sr::modes
