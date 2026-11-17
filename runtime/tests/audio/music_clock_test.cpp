// MusicClock (audio/music_clock.h): music time at a present time, from the output stream's timestamps, on the
// mixer's own frame <-> music time convention.
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <random>

#include "audio/mixer.h"
#include "audio/music_clock.h"
#include "doctest.h"

using sr::audio::Mixer;
using sr::audio::MusicClock;

namespace {

constexpr int32_t kRate = 48000;
constexpr int64_t kSecond = 1'000'000'000;

// The stream presents its frame `position` at `t0 + position / rate` (exact, no jitter).
int64_t presented_at(int64_t t0, int64_t position) { return t0 + position * kSecond / kRate; }

}  // namespace

TEST_SUITE("audio") {

TEST_CASE("music clock follows timestamps") {
    Mixer mixer(kRate);
    mixer.set_audio_offset(0.05);
    mixer.start(24000);  // music time 0 is mixer frame 24,000 (+ the offset)
    MusicClock clock(mixer);
    const int64_t t0 = 5 * kSecond;
    for (int64_t k = 0; k <= 100; ++k) clock.on_timestamp(k * 480, presented_at(t0, k * 480));
    // Mixer frame f is heard at t0 + f / rate; the mixer puts music time t on frame 24,000 + (t + 0.05) * rate.
    const int64_t at = presented_at(t0, 60000);
    CHECK(clock.music_time_at(at) == doctest::Approx((60000.0 - 24000.0) / kRate - 0.05).epsilon(1e-9));

}

TEST_CASE("music clock rebases at a loop without a jump") {
    Mixer mixer(kRate);
    mixer.start(48000);  // music time 0 is mixer frame 48,000
    MusicClock clock(mixer);
    const int64_t t0 = 5 * kSecond;
    for (int64_t k = 0; k <= 100; ++k) clock.on_timestamp(k * 480, presented_at(t0, k * 480));
    const int64_t at = presented_at(t0, 100000);  // heard at mixer frame 100,000: music time 1.0833
    CHECK(clock.music_time_at(at) == doctest::Approx(52000.0 / kRate).epsilon(1e-9));

    // The loop: music time -2 s is what plays at mixer frame 100,000 from now on.
    mixer.restart_loop(100000, -2.0);
    clock.rebase(at, -2.0);
    CHECK(clock.music_time_at(at) == -2.0);
    CHECK(clock.music_time_at(at + kSecond / 100) == doctest::Approx(-1.99).epsilon(1e-9));
    // A timestamp from the new mapping agrees with the rebased line (no snap, no kink).
    clock.on_timestamp(100000 + 480, presented_at(t0, 100000 + 480));
    CHECK(clock.music_time_at(presented_at(t0, 100000 + 480)) == doctest::Approx(-2.0 + 0.01).epsilon(1e-9));
}

TEST_CASE("music clock smooths jittered timestamps") {
    Mixer mixer(kRate);
    mixer.set_audio_offset(0.05);
    mixer.start(24000);
    const int64_t t0 = 5 * kSecond;
    auto truth = [&](int64_t position) { return (double(position) - 24000.0) / kRate - 0.05; };

    // Timestamps every 10 ms, each late or early by up to 1 ms (uniform: RMS 0.577 ms). After every timestamp, the
    // clock read at that timestamp's true time is the line's error. Smoothing must cut the RMS error well below the
    // raw jitter, keep every error under an absolute bound, and keep music time monotonic in 8.33 ms frames.
    MusicClock clock(mixer);
    std::mt19937 rng(6);
    std::uniform_int_distribution<int64_t> jitter(-1'000'000, 1'000'000);
    double raw_sq = 0, smooth_sq = 0, worst = 0;
    int n = 0;
    int64_t query_ns = t0;
    double last_query = -1e9;
    for (int64_t k = 0; k <= 800; ++k) {
        const int64_t position = k * 480;
        const int64_t true_ns = presented_at(t0, position);
        const int64_t j = jitter(rng);
        clock.on_timestamp(position, true_ns + j);
        for (; query_ns <= true_ns; query_ns += 8'333'333) {
            const double t = clock.music_time_at(query_ns);
            CHECK(t >= last_query);  // never backwards
            last_query = t;
        }
        if (k >= 200) {  // settled
            const double error = clock.music_time_at(true_ns) - truth(position);
            smooth_sq += error * error;
            raw_sq += double(j) * 1e-9 * double(j) * 1e-9;
            worst = std::max(worst, std::abs(error));
            ++n;
        }
    }
    const double smooth_rms = std::sqrt(smooth_sq / n), raw_rms = std::sqrt(raw_sq / n);
    CHECK(std::abs(raw_rms - 0.000577) < 0.00005);
    CHECK(smooth_rms < 0.5 * raw_rms);  // gain 1/8: about 0.26 of the raw RMS
    CHECK(worst < 0.0005);              // absolute: half a millisecond
}

TEST_CASE("music clock snaps on a large error and not on a small one") {
    Mixer mixer(kRate);
    mixer.start(0);
    const int64_t t0 = kSecond;
    MusicClock clock(mixer);
    for (int64_t k = 0; k <= 10; ++k) clock.on_timestamp(k * 480, presented_at(t0, k * 480));
    // A timestamp 15 ms late moves the line by 1/8 of that.
    clock.on_timestamp(11 * 480, presented_at(t0, 11 * 480) + 15'000'000);
    CHECK(clock.music_time_at(presented_at(t0, 11 * 480)) == doctest::Approx(11 * 480.0 / kRate - 0.015 / 8).epsilon(1e-9));
    // One 30 ms late (over the 20 ms limit) moves it all the way.
    const int64_t late = presented_at(t0, 12 * 480) + 30'000'000;
    clock.on_timestamp(12 * 480, late);
    CHECK(clock.music_time_at(late) == doctest::Approx(12 * 480.0 / kRate).epsilon(1e-9));
}

TEST_CASE("music clock applies smoothing once per new timestamp") {
    Mixer mixer(kRate);
    mixer.start(0);
    const int64_t t0 = kSecond;
    MusicClock clock(mixer);
    for (int64_t k = 0; k <= 10; ++k) clock.on_timestamp(k * 480, presented_at(t0, k * 480));
    const int64_t late = presented_at(t0, 11 * 480) + 8'000'000;  // 8 ms late: inside the snap limit
    for (int i = 0; i < 20; ++i) clock.on_timestamp(11 * 480, late);  // the device has not updated: 20 polls
    CHECK(clock.music_time_at(late) == doctest::Approx(11 * 480.0 / kRate + 0.008 * 7 / 8).epsilon(1e-9));
}

TEST_CASE("music clock rejects implausible timestamps") {
    Mixer mixer(kRate);
    mixer.start(24000);  // music time 0 at frame 24,000; the stream holds at -0.5 s until a sample arrives
    const int64_t now = 2'125'447'000'000'000;  // device uptime, like AAudio's CLOCK_MONOTONIC
    {
        // Seen on a device: the stream's first sample is position 0 stamped with its creation time, seconds old.
        MusicClock clock(mixer);
        clock.on_stream_start(0);
        clock.on_timestamp(0, now - 6'380'000'000, now);
        CHECK(clock.music_time_at(now) == doctest::Approx(-0.5).epsilon(1e-9));  // still held, not +5.88 s
        clock.on_timestamp(0, now - 649'000'000'000, now);                       // minutes old
        clock.on_timestamp(480, 0, now);                                          // no time at all
        clock.on_timestamp(480, now + 5'000'000'000, now);                        // from the future
        CHECK(clock.music_time_at(now) == doctest::Approx(-0.5).epsilon(1e-9));
        // The first real sample takes over.
        clock.on_timestamp(768, now - 16'000'000, now);
        CHECK(clock.music_time_at(now - 16'000'000) == doctest::Approx((768.0 - 24000) / kRate).epsilon(1e-9));
    }
    {
        // A position that stalls or goes back (same position, later time) does not drag the line.
        MusicClock clock(mixer);
        clock.on_stream_start(24000);
        clock.on_timestamp(960, now, now);
        const double before = clock.music_time_at(now);
        clock.on_timestamp(960, now + 16'000'000, now + 16'000'000);
        clock.on_timestamp(480, now + 32'000'000, now + 32'000'000);
        CHECK(clock.music_time_at(now) == doctest::Approx(before).epsilon(1e-12));
    }
}

TEST_CASE("music clock runs on present time once silent, from where it was held") {
    Mixer mixer(kRate);
    mixer.start(0);
    MusicClock clock(mixer);
    const int64_t t0 = kSecond;
    for (int64_t k = 0; k <= 100; ++k) clock.on_timestamp(k * 480, presented_at(t0, k * 480));
    // The stream fails; the mixer had rendered to frame 49,920 (1.04 s). The clock holds there for 2 s ...
    clock.on_stream_start(49920);
    double last = clock.music_time_at(t0 + kSecond);
    CHECK(last == doctest::Approx(1.04).epsilon(1e-9));
    const int64_t gave_up = t0 + 6 * kSecond;
    for (int64_t ns = t0 + kSecond; ns < gave_up; ns += 8'333'333) {
        const double t = clock.music_time_at(ns);
        CHECK(t >= last);
        CHECK(t == doctest::Approx(1.04).epsilon(1e-9));
        last = t;
    }
    // ... and then, the device not coming back, it runs on from 1.04 s at one second per second.
    clock.on_silent(gave_up);
    CHECK(clock.silent());
    CHECK(clock.music_time_at(gave_up) == doctest::Approx(1.04).epsilon(1e-9));
    for (int64_t ns = gave_up; ns < gave_up + 10 * kSecond; ns += 8'333'333) {
        const double t = clock.music_time_at(ns);
        CHECK(t >= last);
        CHECK(t == doctest::Approx(1.04 + double(ns - gave_up) * 1e-9).epsilon(1e-9));
        last = t;
    }
    clock.on_silent(gave_up + 5 * kSecond);  // idempotent: the origin stays
    clock.on_timestamp(0, gave_up);          // a stale timestamp changes nothing
    CHECK(clock.music_time_at(gave_up + 2 * kSecond) == doctest::Approx(3.04).epsilon(1e-9));
}

TEST_CASE("music clock continuous across reopen") {
    Mixer mixer(kRate);
    mixer.start(0);
    MusicClock clock(mixer);
    const int64_t t0 = kSecond;
    for (int64_t k = 0; k <= 100; ++k) clock.on_timestamp(k * 480, presented_at(t0, k * 480));
    const double before = clock.music_time_at(presented_at(t0, 48000));  // 1.0 s
    CHECK(before == doctest::Approx(1.0).epsilon(1e-9));

    // The device goes away. The mixer had rendered 960 frames (20 ms) past the last frame heard; the new stream's
    // frame 0 is mixer frame 48,960, and it starts presenting 300 ms later.
    const int64_t base = 48960;
    clock.on_stream_start(base);
    double last = before;
    for (int64_t ns = presented_at(t0, 48000); ns < t0 + 1300'000'000; ns += 8'333'333) {
        const double t = clock.music_time_at(ns);
        CHECK(t >= last);
        CHECK(t == doctest::Approx(48960.0 / kRate).epsilon(1e-9));  // holds where the audio resumes
        last = t;
    }
    const int64_t t1 = t0 + 1300'000'000;
    for (int64_t k = 0; k <= 50; ++k) clock.on_timestamp(k * 480, presented_at(t1, k * 480));
    // Music time runs on from the resume point: no jump back by the gap, no jump forward.
    CHECK(clock.music_time_at(t1) == doctest::Approx(48960.0 / kRate).epsilon(1e-9));
    CHECK(clock.music_time_at(presented_at(t1, 24000)) == doctest::Approx((48960.0 + 24000) / kRate).epsilon(1e-9));
}

}  // TEST_SUITE
