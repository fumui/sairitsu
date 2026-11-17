#pragma once

#include <cstdint>

#include "audio/mixer.h"

namespace sr::audio {

// The music time heard at a given CLOCK_MONOTONIC time, from the output stream's timestamps
// (AAudioStream_getTimestamp: stream frame `frame_position` is presented at `time_ns`). Music time is the mixer's:
// mixer frame f carries music time `mixer.frame_to_music(f)`, the inverse of where the mixer places commands, so the
// picture and the sound share one offset convention.
//
// Rate-locked: music time advances one second per second; timestamps only move the line's position, smoothed. Each
// output stream counts its frames from 0; `on_stream_start(base)` says the new stream's frame 0 is mixer frame
// `base`. From then until that stream's first timestamp, music time holds at mixer frame `base`'s (where the audio
// resumes), so a device switch pauses the picture with the sound instead of jumping.
//
// A timestamp pair identical to the previous one (a poll between two device updates) is ignored, so the smoothing
// advances once per new timestamp, not once per poll.
//
// When the device never comes back (`on_silent`), music time continues from where it was held, one second per second
// of present time, with no audio.
//
// Not thread-safe: one thread (the render thread) calls everything.
class MusicClock {
public:
    explicit MusicClock(const Mixer& mixer) : mixer_(mixer) {}

    void on_stream_start(int64_t base);
    // `now_ns` is the clock's current time. A sample is dropped when it is implausible: a time of 0 or less, a time
    // more than kMaxAge from `now_ns` (AAudio's first sample after a start carries the stream's creation time: seconds
    // or minutes stale), or a position that does not advance past the stream's previous sample. The overload without
    // `now_ns` skips the age check.
    void on_timestamp(int64_t frame_position, int64_t time_ns, int64_t now_ns);
    void on_timestamp(int64_t frame_position, int64_t time_ns) { on_timestamp(frame_position, time_ns, time_ns); }
    // A loop: the mixer was restarted so that `music` is what plays at `present_ns`. The line moves there at
    // once, with no smoothing, so the picture neither jumps nor waits for the next timestamp. (A timestamp that
    // arrives later maps through the mixer's new zero to the same line.)
    void rebase(int64_t present_ns, double music);
    // The output gave up: from `present_ns` on, music time runs on present time alone. Idempotent.
    void on_silent(int64_t present_ns);
    bool silent() const { return silent_; }
    double music_time_at(int64_t present_ns) const;

private:
    const Mixer& mixer_;
    int64_t base_ = 0;
    bool have_line_ = false;  // a timestamp of the current stream has arrived
    int64_t anchor_ns_ = 0;
    double anchor_music_ = 0;  // music time at anchor_ns_
    bool have_last_ = false;   // last_position_/last_ns_ are the current stream's latest timestamp
    int64_t last_position_ = 0, last_ns_ = 0;
    bool silent_ = false;
    int64_t silent_ns_ = 0;
    double silent_music_ = 0;  // music time at silent_ns_
};

}  // namespace sr::audio
