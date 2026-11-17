#include "audio/music_clock.h"

namespace sr::audio {

namespace {

// Smoothing of the audio timestamps. Each timestamp moves the rate-locked line
// by kGain of its error; an error over kSnap (a glitch or a stream restart) moves it all the way.
constexpr double kGain = 0.125;
constexpr double kSnap = 0.020;  // s
constexpr int64_t kMaxAgeNs = 250'000'000;  // a presented-frame time further than this from now is not a real sample

double seconds(int64_t ns) { return double(ns) * 1e-9; }

}  // namespace

void MusicClock::on_stream_start(int64_t base) {
    base_ = base;
    have_line_ = false;
    have_last_ = false;
}

void MusicClock::on_timestamp(int64_t frame_position, int64_t time_ns, int64_t now_ns) {
    if (silent_) return;
    if (time_ns <= 0 || time_ns > now_ns + kMaxAgeNs || now_ns - time_ns > kMaxAgeNs) return;  // stale or bogus
    if (have_last_ && frame_position <= last_position_) return;  // no new information (or going backwards)
    have_last_ = true;
    last_position_ = frame_position;
    last_ns_ = time_ns;
    const double observed = mixer_.frame_to_music(double(base_ + frame_position));
    if (have_line_) {
        const double predicted = anchor_music_ + seconds(time_ns - anchor_ns_);
        const double error = observed - predicted;
        anchor_music_ = error > kSnap || error < -kSnap ? observed : predicted + error * kGain;
    } else {
        anchor_music_ = observed;
        have_line_ = true;
    }
    anchor_ns_ = time_ns;
}

void MusicClock::rebase(int64_t present_ns, double music) {
    if (silent_) {
        silent_music_ = music;
        silent_ns_ = present_ns;
        return;
    }
    anchor_music_ = music;
    anchor_ns_ = present_ns;
    have_line_ = true;
}

void MusicClock::on_silent(int64_t present_ns) {
    if (silent_) return;
    silent_music_ = music_time_at(present_ns);  // where the clock stands now: continue from it
    silent_ns_ = present_ns;
    silent_ = true;
}

double MusicClock::music_time_at(int64_t present_ns) const {
    if (silent_) return silent_music_ + (present_ns > silent_ns_ ? seconds(present_ns - silent_ns_) : 0.0);
    if (!have_line_) return mixer_.frame_to_music(double(base_));
    return anchor_music_ + seconds(present_ns - anchor_ns_);
}

}  // namespace sr::audio
