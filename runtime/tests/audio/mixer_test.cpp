// Mixer scheduling semantics (audio/mixer.h): sample-exact starts, the distance rule, late commands, loops, the BGM
// offset, volumes, and that render() never allocates.
#include <atomic>
#include <cstdlib>
#include <new>
#include <vector>

#include "audio/mixer.h"
#include "doctest.h"

using sr::audio::Mixer;
using sr::audio::Pcm;
using sr::modes::AudioCmd;

// Counting allocator: while armed, every operator new is counted (the "render does not allocate" test).
namespace {
std::atomic<bool> g_count_allocs{false};
std::atomic<int> g_allocs{0};
}  // namespace

void* operator new(std::size_t size) {
    if (g_count_allocs.load(std::memory_order_relaxed)) g_allocs.fetch_add(1, std::memory_order_relaxed);
    void* p = std::malloc(size != 0 ? size : 1);
    if (p == nullptr) throw std::bad_alloc();
    return p;
}
void operator delete(void* p) noexcept { std::free(p); }
void operator delete(void* p, std::size_t) noexcept { std::free(p); }

namespace {

constexpr int32_t kRate = 48000;

// A stereo clip at the mixer's rate: sample i = value(i).
template <typename F>
Pcm clip_of(size_t frames, F value) {
    Pcm pcm;
    pcm.rate = kRate, pcm.channels = 2;
    for (size_t i = 0; i < frames; ++i) pcm.samples.insert(pcm.samples.end(), 2, value(i));
    return pcm;
}

Pcm constant(size_t frames, float v) {
    return clip_of(frames, [=](size_t) { return v; });
}

AudioCmd play(int clip, float t, float distance = 0) {
    return AudioCmd{AudioCmd::PlayScheduled, clip, t, distance, 0};
}
AudioCmd loop(int clip, float t, float handle) {
    return AudioCmd{AudioCmd::PlayLoopedScheduled, clip, t, 0, handle};
}
AudioCmd stop(float t, float handle) { return AudioCmd{AudioCmd::StopLoopedScheduled, -1, t, 0, handle}; }

// Left channel of the next `frames` frames, rendered in blocks of `block`.
std::vector<float> render(Mixer& m, int32_t frames, int32_t block = 480) {
    std::vector<float> left;
    std::vector<float> buf(size_t(block) * 2);
    for (int32_t done = 0; done < frames;) {
        const int32_t n = std::min(block, frames - done);
        m.render(buf.data(), n);
        for (int32_t i = 0; i < n; ++i) left.push_back(buf[size_t(i) * 2]);
        done += n;
    }
    return left;
}

int onsets(const std::vector<float>& s) {
    int count = 0;
    for (size_t i = 0; i < s.size(); ++i)
        if (s[i] != 0 && (i == 0 || s[i - 1] == 0)) ++count;
    return count;
}

}  // namespace

TEST_SUITE("audio") {
TEST_CASE("scheduled clip starts on exact sample") {
    for (const int32_t block : {480, 997, 1}) {
        CAPTURE(block);
        Mixer m(kRate);
        m.set_clips({constant(100, 1.0f)});
        m.start(1000);
        m.submit(play(0, 0.5f));
        const auto out = render(m, 26000, block);
        const size_t at = 1000 + 24000;
        CHECK(out[at - 1] == 0.0f);
        CHECK(out[at] == 1.0f);
        CHECK(out[at + 99] == 1.0f);
        CHECK(out[at + 100] == 0.0f);
        CHECK(onsets(out) == 1);
        CHECK(m.frames_rendered() == 26000);
    }
}

TEST_CASE("a loop restart replays the BGM and the same onsets)") {
    Mixer m(kRate);
    m.set_clips({constant(100, 1.0f)});
    m.set_bgm(constant(30000, 0.25f), 0.0);
    m.set_audio_offset(0.01);  // the restart honours the offset like every other placement
    m.start(0);                // music time 0 is frame 480 (the offset)
    const std::vector<AudioCmd> preprocess = {play(0, 0.5f), play(0, 1.0f)};
    for (const AudioCmd& c : preprocess) m.submit(c);
    const auto first = render(m, 60000);
    CHECK(first[480] == 0.25f);            // the BGM's first sample
    CHECK(first[480 + 24000] == 1.25f);    // 0.5 s: clip over the BGM
    CHECK(first[480 + 48000] == 1.0f);     // 1 s: the clip alone
    CHECK(onsets(first) == 2);             // the BGM run (the 0.5 s clip is inside it) and the 1 s clip

    // The loop: music time -2 is output frame 60,000 (the end of the first pass), so music time 0 is 96,000 frames
    // later. The second pass puts the BGM and the clips exactly where the first did, shifted by that.
    m.restart_loop(60000, -2.0);
    for (const AudioCmd& c : preprocess) m.submit(c);
    const auto second = render(m, 150000);
    const size_t at = 96000;  // second[] starts at output frame 60,000; music time 0 is frame 156,000
    const std::vector<float> a(first.begin() + 480, first.end());
    const std::vector<float> b(second.begin() + std::ptrdiff_t(at), second.begin() + std::ptrdiff_t(at + a.size()));
    CHECK(a == b);
    CHECK(second[at] == 0.25f);
    CHECK(onsets(second) == 2);
    CHECK(m.dropped() == 0);
}

TEST_CASE("a loop restart keeps playing voices and forgets pending plays") {
    Mixer m(kRate);
    m.set_clips({constant(2000, 1.0f)});
    m.start(0);
    m.submit(play(0, 0.0f));
    m.submit(play(0, 10.0f));  // pending (not yet started) when the loop restarts
    const auto before = render(m, 1000);
    CHECK(before[999] == 1.0f);
    m.restart_loop(1000, -2.0);
    const auto after = render(m, 3000);
    CHECK(after[0] == 1.0f);     // the running voice's tail goes on ...
    CHECK(after[999] == 1.0f);
    CHECK(after[1000] == 0.0f);  // ... and ends at its own end (frame 2000)
    CHECK(onsets(after) == 1);   // the 10 s play is gone
}

TEST_CASE("audio offset moves the start") {
    Mixer m(kRate);
    m.set_clips({constant(10, 1.0f)});
    m.set_audio_offset(0.01);  // 480 frames; a positive offset delays audio
    m.start(0);
    m.submit(play(0, 0.5f));
    const auto out = render(m, 25000);
    CHECK(out[24480 - 1] == 0.0f);
    CHECK(out[24480] == 1.0f);
}

TEST_CASE("play starts at the next render") {
    Mixer m(kRate);
    m.set_clips({constant(100, 1.0f)});
    m.start(0);
    CHECK(onsets(render(m, 1000)) == 0);
    m.submit(AudioCmd{AudioCmd::Play, 0, 0, 0, 0});
    const auto out = render(m, 300);
    CHECK(out[0] == 1.0f);
    CHECK(out[99] == 1.0f);
    CHECK(out[100] == 0.0f);
    CHECK(onsets(out) == 1);
}

TEST_CASE("looped play starts at the next render and stop looped stops at the next render") {
    Mixer m(kRate);
    m.set_clips({constant(50, 1.0f)});
    m.start(0);
    render(m, 1000);
    m.submit(AudioCmd{AudioCmd::PlayLooped, 0, 0, 0, 7});
    const auto running = render(m, 480);
    CHECK(running[0] == 1.0f);
    CHECK(running[479] == 1.0f);  // wraps over the 50-frame clip
    m.submit(AudioCmd{AudioCmd::StopLooped, -1, 0, 0, 7});
    const auto stopped = render(m, 480);
    CHECK(stopped[0] == 0.0f);
    CHECK(onsets(stopped) == 0);
}

TEST_CASE("play honours distance") {
    Mixer m(kRate);
    m.set_clips({constant(10, 1.0f)});
    m.start(0);
    const AudioCmd first{AudioCmd::Play, 0, 0, 0.5f, 0};
    m.submit(first);
    CHECK(onsets(render(m, 480)) == 1);
    m.submit(first);  // 0.01 s after: dropped
    CHECK(onsets(render(m, 480)) == 0);
    render(m, 48000);
    m.submit(first);  // over 0.5 s after: kept
    CHECK(onsets(render(m, 480)) == 1);
}

TEST_CASE("distance drops close repeats") {
    Mixer m(kRate);
    m.set_clips({constant(100, 1.0f), constant(100, 0.5f)});
    m.start(0);
    m.submit(play(0, 0.5f, 0.02f));
    m.submit(play(0, 0.51f, 0.02f));  // 0.01 s after: dropped
    m.submit(play(1, 0.51f, 0.02f));  // another clip: kept
    m.submit(play(0, 0.54f, 0.02f));  // 0.04 s after the one that played: kept
    m.submit(play(0, 0.54f));         // no distance: kept (overlaps)
    const auto out = render(m, 28000);
    CHECK(out[24000] == 1.0f);
    CHECK(out[24480] == 0.5f);            // clip 1 only
    CHECK(out[25920] == 2.0f);            // 0.54 s: two overlapping plays
    CHECK(out[24480 - 1] == 0.0f);        // the dropped 0.51 s repeat of clip 0 left no onset of its own
    CHECK(onsets(out) == 3);              // 0.5 s, 0.51 s (clip 1), 0.54 s (both plays overlap)
}

TEST_CASE("distance is decided in time order, edge included") {
    // Commands issued out of time order are judged in time order, a
    // play exactly `distance` after the last one is dropped, a dropped play doesn't count, and distance 0 never drops.
    Mixer m(kRate);
    m.set_clips({constant(10, 1.0f)});
    m.start(0);
    m.submit(play(0, 0.5f));
    m.submit(play(0, 0.75f, 0.25f));    // exactly 0.25 after: dropped
    m.submit(play(0, 1.75f, 0.5f));     // issued first, but 0.25 after the 1.5 one below: dropped
    m.submit(play(0, 1.5f));            // plays
    m.submit(play(0, 2.25f));           // issued first
    m.submit(play(0, 2.0f, 0.5f));      // 0.5 s after 1.5 (edge): dropped; the later 2.25 one doesn't count
    m.submit(play(0, 2.875f, 0.5f));    // 0.625 after 2.25: plays
    m.submit(play(0, 3.0f));
    m.submit(play(0, 3.0f));            // same time, distance 0: both play
    m.submit(play(0, 3.125f, 0.25f));   // dropped
    m.submit(play(0, 3.3125f, 0.25f));  // 0.3125 after the last play (3.0): plays
    const auto out = render(m, 4 * kRate);
    const auto at = [](double t) { return size_t(t * kRate); };
    CHECK(out[at(0.5)] == 1.0f);
    CHECK(out[at(0.75)] == 0.0f);
    CHECK(out[at(1.5)] == 1.0f);
    CHECK(out[at(1.75)] == 0.0f);
    CHECK(out[at(2.0)] == 0.0f);
    CHECK(out[at(2.25)] == 1.0f);
    CHECK(out[at(2.875)] == 1.0f);
    CHECK(out[at(3.0)] == 2.0f);
    CHECK(out[at(3.125)] == 0.0f);
    CHECK(out[at(3.3125)] == 1.0f);
}

TEST_CASE("plays scheduled up front beyond the voice table all play") {
    // Next RUSH schedules every clip in preprocess: the benchmark sends 764 PlayScheduled on the first frame.
    Mixer m(kRate);
    m.set_clips({constant(10, 1.0f)});
    m.start(0);
    for (int i = 999; i >= 0; --i) m.submit(play(0, 0.5f + 0.01f * float(i)));  // newest first
    const auto out = render(m, 11 * kRate);
    CHECK(onsets(out) == 1000);
    CHECK(m.dropped() == 0);
}

TEST_CASE("a burst of kMaxScheduled plays submitted at once is never dropped") {
    // A big level sends every PlayScheduled on frame 1, in microseconds, before the audio thread drains anything.
    Mixer m(kRate);
    m.set_clips({constant(10, 1.0f)});
    m.start(0);
    constexpr int kN = 20000;  // well past the old 4,096-command ring
    for (int i = 0; i < kN; ++i) m.submit(play(0, 0.5f + 0.001f * float(i)));
    CHECK(m.dropped() == 0);
    const auto out = render(m, (kN / 1000 + 2) * kRate);
    CHECK(onsets(out) == kN);
    CHECK(m.dropped() == 0);
    // The full table: kMaxScheduled commands in one burst.
    Mixer full(kRate);
    full.set_clips({constant(10, 1.0f)});
    full.start(0);
    for (size_t i = 0; i < Mixer::kMaxScheduled; ++i) full.submit(play(0, 0.5f + 0.001f * float(i)));
    CHECK(full.dropped() == 0);
}

TEST_CASE("mono effect clips are panned to the centre at -3 dB") {
    // SFX 25 %: a mono clip plays at 0.1767 (0.25 / sqrt 2).
    Pcm mono;
    mono.rate = kRate, mono.channels = 1, mono.samples = {1.0f, -0.5f};
    const Pcm m = sr::audio::effect_clip(mono, kRate);
    REQUIRE(m.channels == 2);
    CHECK(m.samples[0] == doctest::Approx(0.70710678f));
    CHECK(m.samples[1] == doctest::Approx(0.70710678f));
    CHECK(m.samples[2] == doctest::Approx(-0.35355339f));
    const Pcm s = sr::audio::effect_clip(constant(2, 0.5f), kRate);
    CHECK(s.samples == std::vector<float>{0.5f, 0.5f, 0.5f, 0.5f});
}

TEST_CASE("late command plays from offset") {
    Mixer m(kRate);
    m.set_clips({clip_of(2000, [](size_t i) { return float(i) / 1000.0f; })});
    m.start(0);
    render(m, 1000);
    m.submit(play(0, 500.0f / kRate));  // its start frame (500) is already behind us
    const auto out = render(m, 1000);
    CHECK(out[0] == doctest::Approx(0.5f));     // clip sample 500 at output frame 1000
    CHECK(out[999] == doctest::Approx(1.499f));
}

TEST_CASE("looped clip stops at scheduled time") {
    Mixer m(kRate);
    m.set_clips({clip_of(10, [](size_t i) { return float(i + 1); })});
    m.start(0);
    m.submit(loop(0, 0.1f, 7));
    m.submit(stop(0.2f, 7));
    const auto out = render(m, 12000);
    const size_t begin = 4800, end = 9600;
    CHECK(out[begin - 1] == 0.0f);
    CHECK(out[begin] == 1.0f);
    CHECK(out[begin + 9] == 10.0f);
    CHECK(out[begin + 10] == 1.0f);  // wraps
    CHECK(out[end - 1] != 0.0f);
    CHECK(out[end] == 0.0f);
    CHECK(out[end + 100] == 0.0f);
}

TEST_CASE("looped clip stopped before it starts never plays") {
    Mixer m(kRate);
    m.set_clips({constant(10, 1.0f)});
    m.start(0);
    m.submit(loop(0, 0.2f, 3));
    m.submit(stop(0.1f, 3));
    CHECK(onsets(render(m, 12000)) == 0);
}

// bgmOffset is the BGM time at music time 0, so BGM sample 0 plays at music time -bgmOffset. Sekai Viewer levels use it to skip the silent lead-in of the song file (issue #1).
TEST_CASE("a positive bgm offset skips into the bgm") {
    Mixer m(kRate);
    m.set_bgm(clip_of(30000, [](size_t i) { return float(i + 1) * 1e-6f; }), 0.25);
    m.start(30000);
    const auto out = render(m, 48000);
    CHECK(out[30000 - 12000 - 1] == 0.0f);
    CHECK(out[30000 - 12000] == 1e-6f);                   // BGM sample 0 at music time -0.25
    CHECK(out[30000] == float(12000 + 1) * 1e-6f);        // music time 0 plays BGM time 0.25
    CHECK(out[30000 + 17999] == float(30000) * 1e-6f);    // the last sample
    CHECK(out[30000 + 18000] == 0.0f);
}

TEST_CASE("a negative bgm offset starts the bgm after music time zero") {
    Mixer m(kRate);
    m.set_bgm(constant(100, 0.25f), -0.5);
    m.start(500);
    const auto out = render(m, 25000);
    CHECK(out[500 + 24000 - 1] == 0.0f);
    CHECK(out[500 + 24000] == 0.25f);
    CHECK(out[500 + 24099] == 0.25f);
    CHECK(out[500 + 24100] == 0.0f);
}

TEST_CASE("volumes scale") {
    Mixer m(kRate);
    m.set_clips({constant(100, 1.0f)});
    m.set_bgm(constant(100, 0.8f), 0.0);
    m.set_volumes(0.5f, 0.25f);
    m.start(0);
    m.submit(play(0, 0.0f));
    const auto out = render(m, 200);
    CHECK(out[0] == doctest::Approx(0.5f + 0.2f));
    CHECK(out[99] == doctest::Approx(0.7f));
    CHECK(out[100] == 0.0f);
}

TEST_CASE("pause freezes music time and stepping") {
    // A pause cuts all audio at once and freezes the music time; the voices keep their positions.
    Mixer m(kRate);
    m.set_clips({clip_of(100, [](size_t i) { return float(i + 1); })});
    m.set_bgm(clip_of(200000, [](size_t i) { return float(i + 1) * 1e-6f; }), 0.0);
    m.start(0);
    m.submit(loop(0, 0.0f, 3));
    const auto before = render(m, 1030);
    CHECK(before[1029] == doctest::Approx(30.0f + 1030e-6f));  // clip sample 29, BGM sample 1029
    const double paused_at = m.frame_to_music(1030);
    m.pause(1030);
    const auto quiet = render(m, 48000, 997);
    for (const float s : quiet) REQUIRE(s == 0.0f);
    CHECK(m.frames_rendered() == 49030);
    // Resumed 48000 frames later, music time carries on from the paused time.
    m.resume(49030);
    CHECK(m.frame_to_music(49030) == paused_at);
    const auto after = render(m, 200);
    CHECK(after[0] == doctest::Approx(31.0f + 1031e-6f));  // the voice and the BGM go on where they were
    CHECK(after[69] == doctest::Approx(100.0f + 1100e-6f));
    CHECK(after[70] == doctest::Approx(1.0f + 1101e-6f));  // the looped clip wraps
}

TEST_CASE("pause and resume are sample exact inside a block") {
    Mixer m(kRate);
    m.set_clips({constant(5000, 1.0f)});
    m.start(0);
    m.submit(loop(0, 0.0f, 1));
    m.pause(500);
    const auto out = render(m, 1000);
    CHECK(out[499] == 1.0f);
    CHECK(out[500] == 0.0f);
    CHECK(out[999] == 0.0f);
    m.resume(2300);
    const auto next = render(m, 1500);  // frames 1000 .. 2500
    CHECK(next[1299] == 0.0f);
    CHECK(next[1300] == 1.0f);  // frame 2300
    CHECK(next[1499] == 1.0f);
}

TEST_CASE("resume keeps pending scheduled plays") {
    Mixer m(kRate);
    m.set_clips({constant(100, 1.0f)});
    m.start(0);
    m.submit(play(0, 1.0f));  // frame 48000
    render(m, 24000);
    m.pause(24000);
    for (const float s : render(m, 100000)) REQUIRE(s == 0.0f);  // 48000 passes during the pause: silent, not consumed
    m.resume(124000);
    const auto out = render(m, 30000);
    CHECK(out[23999] == 0.0f);
    CHECK(out[24000] == 1.0f);  // 0.5 s after the resume, as it was 0.5 s after the pause
    CHECK(out[24099] == 1.0f);
    CHECK(out[24100] == 0.0f);
    CHECK(onsets(out) == 1);
}

TEST_CASE("commands submitted right after a resume are not shifted by the pause") {
    // The producer resumes and submits that frame's audio before the audio thread renders again: the new zero already
    // places those commands, so only what was there before the pause moves.
    Mixer m(kRate);
    m.set_clips({constant(100, 1.0f), constant(100, 0.5f)});
    m.start(0);
    m.submit(play(0, 0.5f));  // before the pause: frame 24000, shifts with the resume
    render(m, 1000);
    m.pause(1000);
    render(m, 5000);
    m.resume(6000);           // the pause was 5000 frames
    m.submit(play(1, 0.25f)); // music 0.25 s: frame 12000 + 5000 under the new zero
    m.submit(AudioCmd{AudioCmd::Play, 1, 0, 0, 0});  // immediately: at the resume frame
    const auto out = render(m, 30000);                // frames 6000 .. 35999
    CHECK(out[0] == 0.5f);                            // the immediate play, at frame 6000
    CHECK(out[99] == 0.5f);
    CHECK(out[100] == 0.0f);
    CHECK(out[17000 - 6000 - 1] == 0.0f);
    CHECK(out[17000 - 6000] == 0.5f);                 // 0.25 s: frame 17000
    CHECK(out[29000 - 6000 - 1] == 0.0f);
    CHECK(out[29000 - 6000] == 1.0f);                 // 0.5 s: frame 29000 (24000 + 5000)
    CHECK(onsets(out) == 3);
}

TEST_CASE("an immediate play submitted before the render reaches a later resume frame starts at the resume frame") {
    // The resume frame is the frame heard at the present time, normally a little ahead of what the audio thread has
    // rendered: the first frames after a resume submit their Play and PlayLooped while the audio thread is still
    // paused. They start at the resume frame, not one pause later, and the distance rule sees their new music time.
    for (const int32_t block : {480, 997, 2000}) {
        CAPTURE(block);
        Mixer m(kRate);
        m.set_clips({constant(100, 0.25f), constant(100, 0.5f)});
        m.start(0);
        render(m, 1000, block);
        m.pause(1000);
        render(m, 5000, block);  // rendered: 6000
        m.resume(6960);          // 960 frames ahead of the audio thread; the pause is 5960 frames
        m.submit(AudioCmd{AudioCmd::Play, 1, 0, 0, 0});        // music 1000 / 48000 s, at frame 6960
        m.submit(AudioCmd{AudioCmd::PlayLooped, 0, 0, 0, 4});  // at frame 6960
        m.submit(play(1, 0.03f, 0.05f));  // 0.03 s, frame 7400: within 0.05 s of the Play, so dropped
        const auto out = render(m, 3000, block);  // frames 6000 .. 8999
        const auto at = [&](int64_t frame) { return out[size_t(frame - 6000)]; };
        CHECK(at(6959) == 0.0f);
        CHECK(at(6960) == 0.75f);  // the Play and the looped clip start at the resume frame
        CHECK(at(7059) == 0.75f);
        CHECK(at(7060) == 0.25f);
        CHECK(at(7400) == 0.25f);  // the scheduled play was dropped by the distance rule
        CHECK(at(8999) == 0.25f);
        CHECK(onsets(out) == 1);
        CHECK(m.frame_to_music(6960) == double(1000) / kRate);
    }
}

TEST_CASE("a pause and its resume inside one render block delay exactly once") {
    Mixer m(kRate);
    m.set_clips({constant(2000, 0.25f), constant(100, 0.5f)});
    m.start(0);
    m.submit(play(0, 0.0f));  // a voice: frames 0 .. 2000
    m.submit(play(1, 0.5f));  // pending: frame 24000
    render(m, 1000);
    // One call renders frames 1000 .. 40999; the pause (1530) and the resume (4530) both fall inside it.
    m.pause(1530);
    m.resume(4530);  // the pause is 3000 frames
    m.submit(play(1, 0.25f));  // after the resume: music 0.25 s is frame 12000 + 3000
    const auto out = render(m, 40000, 40000);
    const auto at = [&](int64_t frame) { return out[size_t(frame - 1000)]; };
    CHECK(at(1529) == 0.25f);  // the voice plays up to the pause ...
    CHECK(at(1530) == 0.0f);   // ... silence ...
    CHECK(at(4529) == 0.0f);
    CHECK(at(4530) == 0.25f);  // ... and goes on from where it stopped, 470 frames left
    CHECK(at(4999) == 0.25f);
    CHECK(at(5000) == 0.0f);   // 2000 + 3000
    CHECK(at(14999) == 0.0f);
    CHECK(at(15000) == 0.5f);  // submitted after the resume: placed by the new zero, not delayed again
    CHECK(at(26999) == 0.0f);
    CHECK(at(27000) == 0.5f);  // pending 24000 + 3000
    CHECK(at(27099) == 0.5f);
    CHECK(at(27100) == 0.0f);
    CHECK(onsets(out) == 4);  // the voice (twice: before and after the pause), the two plays
    CHECK(m.frame_to_music(4530) == double(1530) / kRate);  // music time went on from the paused time
}

TEST_CASE("stop_looped ends every looped voice and leaves the others") {
    Mixer m(kRate);
    m.set_clips({constant(50, 1.0f), constant(3000, 0.5f)});
    m.start(0);
    m.submit(AudioCmd{AudioCmd::PlayLooped, 0, 0, 0, 1});
    m.submit(AudioCmd{AudioCmd::PlayLooped, 0, 0, 0, 2});
    m.submit(play(1, 0.0f));
    render(m, 480);
    m.stop_looped();
    const auto out = render(m, 480);
    CHECK(out[0] == 0.5f);  // only the one-shot, which is still playing
    CHECK(out[479] == 0.5f);
}

TEST_CASE("a restart that drops the voices also leaves a pause") {
    // Retry from the pause menu: the BGM starts over, the frozen voices are gone, the output is live again.
    Mixer m(kRate);
    m.set_clips({constant(100000, 1.0f)});
    m.set_bgm(constant(100000, 0.25f), 0.0);
    m.start(0);
    m.submit(loop(0, 0.0f, 1));
    render(m, 1000);
    m.pause(1000);
    render(m, 1000);
    m.restart_loop(2000, -2.0, /*keep_voices=*/false);
    const auto out = render(m, 100000);
    for (size_t i = 0; i < 96000; ++i) REQUIRE(out[i] == 0.0f);  // -2 s of pre-roll, then the BGM
    CHECK(out[96000] == 0.25f);
    CHECK(m.dropped() == 0);
}

TEST_CASE("a pause requested after a retry survives the retry's restart") {
    // Retry, then pause, both before the audio thread handles the restart: the restart drops the old run's pause
    // request but keeps the new one.
    Mixer m(kRate);
    m.set_clips({constant(100000, 1.0f)});
    m.start(0);
    m.submit(loop(0, 0.0f, 1));
    render(m, 1000);
    m.pause(5000);  // pending, from the old run
    m.restart_loop(1000, -2.0, /*keep_voices=*/false);
    m.submit(AudioCmd{AudioCmd::PlayLooped, 0, 0, 0, 2});  // the new run's looped clip, from frame 1000
    m.pause(1500);  // after the retry: kept
    const auto out = render(m, 3000);  // frames 1000 .. 3999
    CHECK(out[0] == 1.0f);    // the new clip alone: the old run's loop is gone
    CHECK(out[499] == 1.0f);
    CHECK(out[500] == 0.0f);  // paused at frame 1500
    CHECK(out[2999] == 0.0f);
    m.resume(5000);
    const auto after = render(m, 2000);  // frames 4000 .. 5999
    CHECK(after[999] == 0.0f);
    CHECK(after[1000] == 1.0f);  // frame 5000: the clip goes on
    CHECK(after[1999] == 1.0f);
}

TEST_CASE("a pause still pending at a retry is dropped by the retry's restart") {
    Mixer m(kRate);
    m.set_clips({constant(100000, 1.0f)});
    m.start(0);
    render(m, 1000);
    m.pause(1500);  // from the old run, not yet reached
    m.restart_loop(1000, -2.0, /*keep_voices=*/false);
    m.submit(AudioCmd{AudioCmd::PlayLooped, 0, 0, 0, 2});
    const auto out = render(m, 3000);
    for (const float s : out) REQUIRE(s == 1.0f);  // live throughout
}

TEST_CASE("clips at other rates are resampled to the mixer rate") {
    Mixer m(kRate);
    Pcm mono;
    mono.rate = 24000, mono.channels = 1;
    mono.samples.assign(2400, 0.5f);  // 0.1 s
    m.set_clips({mono});
    m.start(0);
    m.submit(play(0, 0.0f));
    const auto out = render(m, 6000);
    CHECK(out[2400] == doctest::Approx(0.5f).epsilon(0.05));  // mid-clip: 0.05 s in
    CHECK(out[5000] == doctest::Approx(0.0f).epsilon(0.01));
}

TEST_CASE("render does not allocate") {
    Mixer m(kRate);
    m.set_clips({constant(5000, 1.0f), constant(30, 0.5f)});
    m.set_bgm(constant(100000, 0.1f), 0.0);
    m.start(0);
    std::vector<float> buf(960 * 2);
    g_allocs = 0;
    g_count_allocs = true;
    m.submit(loop(0, 0.0f, 1));
    for (int i = 0; i < 40; ++i) {
        m.submit(play(1, 0.01f * float(i), 0.005f));
        if (i == 30) m.submit(stop(0.3f, 1));
        m.render(buf.data(), 960);
    }
    g_count_allocs = false;
    CHECK(g_allocs.load() == 0);
    CHECK(m.frames_rendered() == 40 * 960);
}
}  // TEST_SUITE
