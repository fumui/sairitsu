#pragma once

#include <android/native_window.h>

#include <atomic>
#include <filesystem>
#include <memory>
#include <string>
#include <thread>
#include <vector>

#include "audio/mixer.h"
#include "data/resources.h"
#include "modes/replay_file.h"
#include "modes/session.h"
#include "render/ui.h"
#include "vm/interpreter.h"

namespace sr::android {

// The live Watch loop: a native render thread with its own ALooper, driven by AChoreographer vsync callbacks
// (AChoreographer_postFrameCallback64). It owns an EGL ES 3 context on a default (RGBA8, not sRGB-encoded) window
// surface with dataspace V0_SRGB, and asks for 120 Hz with
// ANativeWindow_setFrameRate(FIXED_SOURCE). Each vsync: music time, one WatchSession step, the frame's audio commands
// to the mixer, the frame's sprite list and background, GlRenderer::draw, eglSwapBuffers. The loop stops stepping at the level's end or a fault (both
// logged), leaving the last frame on screen.
//
// Sound and music time: the level's clips and BGM play through a Mixer on an AAudioOutput stream. Each vsync's
// expected present time (the AChoreographer frame timeline, API 33+) goes through the MusicClock (the stream's
// timestamps) into the FrameClock, so a frame shows the music time heard when it reaches the screen; the frame's
// audio commands go to the mixer. Without an audio stream, music time is the vsync time since the first frame.
struct AudioSettings {
    float effect_volume = 1, bgm_volume = 1;
    float audio_offset = 0;  // s
    float input_offset = 0;  // s, Play: subtracted from a touch's music time
};

// Play: where a run that ends on its own leaves its replay. The loop records the touches of every frame that
// steps (after the step, the ending frame left out, as srun's play harness), and at the end builds the replay on the
// render thread, takes a token from `slot` and writes `<out>.json.gz` + `<out>.config.gz` on a detached thread that
// owns copies of everything it needs and the slot's shared_ptr. A retry clears the touches and resets the slot; it
// never waits on a write. Quits and faults write nothing; Watch never records.
struct ReplayRecording {
    std::filesystem::path out;                // base path; empty: no recording
    std::vector<float> options;               // the run's Level Option values (all of them), for the config file
    std::shared_ptr<modes::ReplaySlot> slot;  // null: no recording
};

// The level's clips and BGM resampled to the output device's rate (speex quality 10, seconds of work for a long BGM).
// Made once at level load, off the UI's locks; every start (a restart on a surface resize included) reuses it.
struct PreparedAudio {
    int32_t rate = 0;
    std::vector<audio::Pcm> clips;  // index i = the effect data's clip i (empty Pcm where the file is missing)
    audio::Pcm bgm;
};

// Opens the output device briefly to learn its rate, then resamples `level`'s audio to it. Null (silent level) when
// there is no device. If the device's rate later differs, start() resamples again.
std::unique_ptr<PreparedAudio> prepare_audio(const data::LoadedLevel& level);

// The text for a fault kind in "The level stopped: <label>: <detail>".
const char* fault_label(vm::Fault::Kind kind);

class GameplayLoop {
public:
    // Starts the thread and waits for its setup (EGL, renderer, texture upload, audio). `window` is acquired for the
    // loop's lifetime; `level` and `prepared` (may be null) must outlive the loop; `session` is prepared; `aspect` is
    // the session's Runtime Environment aspect (the background blur uses it); `replay` says where a Play run's replay
    // goes (ReplayRecording); `watch_replay` (Watch, may be null; must outlive the loop) is the replay the Watch
    // session plays, whose touches the loop draws as markers and whose Misses and Goods tick the bar's track.
    // Returns null with `error` set when setup fails.
    static std::unique_ptr<GameplayLoop> start(ANativeWindow* window, const data::LoadedLevel& level,
                                               const PreparedAudio* prepared,
                                               std::unique_ptr<modes::Session> session, float aspect,
                                               const AudioSettings& audio, ReplayRecording replay,
                                               const modes::Replay* watch_replay, std::string& error);
    ~GameplayLoop();  // stop()

    GameplayLoop(const GameplayLoop&) = delete;
    GameplayLoop& operator=(const GameplayLoop&) = delete;

    // Stops the thread and releases EGL and the window. Idempotent.
    void stop();

    // What the Compose layer draws above the surface, so the geometry has one source (render/ui.cpp): the built-in
    // HUD widgets the engine shows (build_hud, from the last stepped frame's Runtime UI and stats) and, in Watch, the
    // control bar (build_overlay, shown for 3 s after the start, a loop's restart or a control call, and while
    // paused or scrubbing). Cheap; any thread.
    render::UiOverlay overlay() const;

    // Watch's controls (saved replays, design section 6). Cheap, any thread, no lock the level's start holds (only the
    // loop's own short request lock); the state reads back at once and the render thread follows at its next frame.
    // Each call shows the bar again (since_shown starts over). Nothing in Play.
    // - watch_pause: true freezes the stage on its last frame and pauses the mixer (music time stands still); false
    //   continues at once from there (no countdown). At the level's end with repeat off, false starts over at -2 s.
    // - seek: a time jump to `t` clamped to [-kPreRoll, end] (the session's end time), taken like the loop's skip
    //  . While `scrubbing` the stage shows each target and the audio stays paused; a seek with `scrubbing`
    //   false (the release) restarts the audio at `t` (voices dropped, the preprocess clips from `t` on queued
    //   again) and the run continues unless paused.
    // - set_repeat: on (the default) loops at the end as today; off stops on the last frame there, paused (the bar
    //   shows a play glyph); a seek from there continues running.
    // - show_bar: shows the bar (a tap on the stage).
    // - watch_state: fills `out` (kWatchStateFloats) with [0] music time (a seek not yet taken: its target), [1] BGM
    //   length, [2] paused (1/0, the end's stop included), [3] repeat, [4] bar visible, [5..8] the seek track,
    //   [9..12] the repeat glyph, [13..16] the pause control (render::WatchBarRects: left, bottom, right, top in
    //   screen units). False (and `out` zeroed) in Play.
    static constexpr int kWatchStateFloats = 17;
    static constexpr int kWatchTrack = 5, kWatchRepeatRect = 9, kWatchPauseRect = 13;
    void watch_pause(bool paused);
    void seek(double t, bool scrubbing);
    void set_repeat(bool on);
    void show_bar();
    bool watch_state(float* out) const;

    // A callback fault or internal error that stopped the level (it stays on its last frame): `label` names the kind,
    // `detail` says what failed (archetype and callback included), `time` is the music time it happened at.
    // False while the level runs. Any thread.
    struct Fault {
        std::string label, detail;
        double time = 0;
    };
    bool fault(Fault& out) const;

    // Play only. The UI thread's pointers: `count` pointers of `data` (per pointer id, phase (TouchEvent::Phase), x px,
    // y px in surface pixels) at CLOCK_MONOTONIC `time_ns`. Appended to a mutex-guarded buffer that the render thread
    // swaps once per frame; dropped while the session isn't running, after it ended, and in Watch. Any thread.
    void touch(int64_t time_ns, int32_t count, const float* data);

    // Play only: fills `out` (kPlayStateFloats) with the state (kPlayRunning / kPlayPaused / kPlayCountdown /
    // kPlayEnded); during a countdown the seconds left (1 down to 0) at [kPlayCountdownLeft]; once ended the result
    // (grade ordinal, arcade, accuracy, combo, perfect, great, good, miss, total) at [1..9] and at [kPlayPartial] 1 for a
    // quit (the stats so far) or 0 for a run that ended; and appends the haptic levels queued since the last call to
    // `haptics`. Any thread.
    static constexpr int kPlayStateFloats = 11;
    static constexpr int kPlayCountdownLeft = 1, kPlayPartial = 10;
    static constexpr float kPlayRunning = 0, kPlayPaused = 1, kPlayCountdown = 2, kPlayEnded = 3;
    void play_state(float* out, std::vector<int32_t>& haptics);

    // Play only. Cheap, any thread, no lock the level's start holds; the state reads back at once and the render
    // thread follows at its next frame.
    // pause: the stage freezes on the last frame, all audio is cut, touches are dropped. resume: a 1.0 s silent
    // countdown (kPlayCountdown, "3", "2", "1" drawn by the UI) over the frozen stage, then running from the paused time.
    // retry: back to -2 s at once (no countdown), from running or paused. quit: ends with the stats so far
    // (kPlayEnded, partial). Requests that do not fit the state (a resume while running) do nothing.
    void pause();
    void resume();
    void retry();
    void quit();

    // Play only: the app went to the background. detach_surface pauses and gives the surface up (returns once the render
    // thread no longer uses the window); attach_surface takes a new window of the same size and shows the paused frame
    // on it (it takes over the caller's reference to `window`, also on failure). Both block up to 3 s on the render
    // thread. False on failure; `error` says why (a new size, say): the caller starts over.
    // Play only: the engine's Menu widget box (Runtime UI, widget 0) as of the last stepped frame, in screen units
    // (x in -aspect..aspect, y up in -1..1): left, bottom, right, top. False until a frame has set it or when it has no
    // size. The Compose layer puts the pause control's hit target there. Any thread.
    bool menu_rect(float* out) const;

    // Frames drawn per second over the last half second (the FPS counter; paused frames draw nothing). Any thread.
    float render_fps() const;

    bool detach_surface();
    bool attach_surface(ANativeWindow* window, std::string& error);

    struct State;  // the render thread's state (gameplay_loop.cpp)

private:
    explicit GameplayLoop(std::unique_ptr<State> state);
    std::unique_ptr<State> state_;
    std::thread thread_;
};

}  // namespace sr::android
