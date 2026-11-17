#include "android/gameplay_loop.h"

#include <EGL/egl.h>
#include <android/choreographer.h>
#include <android/data_space.h>
#include <android/log.h>
#include <android/looper.h>

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <functional>
#include <future>
#include <iterator>
#include <mutex>
#include <optional>
#include <thread>
#include <utility>
#include <vector>

#include "android/aaudio_output.h"
#include "audio/mixer.h"
#include "audio/music_clock.h"
#include "audio/resample.h"
#include "modes/judgment_log.h"
#include "modes/play_session.h"
#include "modes/replay_file.h"
#include "modes/replay_view.h"
#include "modes/replay_writer.h"
#include "modes/touch.h"
#include "particle/particle_system.h"
#include "render/background_layout.h"
#include "render/geometry.h"
#include "render/gl_renderer.h"
#include "time/frame_clock.h"
#include "time/time_tables.h"

namespace sr::android {

namespace {

constexpr char kTag[] = "sairitsu_runtime";
constexpr int64_t kReportEveryNs = 10'000'000'000;  // frame statistics in logcat every 10 s
constexpr int64_t kFpsEveryNs = 500'000'000;         // the FPS counter's window
constexpr int64_t kLogFirstFrames = 8;              // the first frames' times in logcat (the -2 s start check)
// The mixer's music time 0 is time::kPreRoll (2 s) into the stream and the picture runs through the
// negative times before it (FrameClock's start).
using time::kPreRoll;
// Before API 33 there is no frame timeline: a frame is assumed to reach the screen this long after its vsync.
constexpr int64_t kPresentLatencyNs = 16'666'667;

#define SR_LOGI(...) __android_log_print(ANDROID_LOG_INFO, kTag, __VA_ARGS__)
#define SR_LOGW(...) __android_log_print(ANDROID_LOG_WARN, kTag, __VA_ARGS__)
#define SR_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kTag, __VA_ARGS__)

double ms_since(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
}

// The steady clock in ns: Watch's bar (since_shown) is timed on it from both the UI and the render thread.
int64_t steady_ns() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

// Watch's bar: opaque for 2.8 s after it was shown, then a 0.2 s fade (render::build_overlay).
constexpr float kBarShownFor = 3.0f;

// The level's clips (index i = the effect data's clip i) and BGM at `rate`.
PreparedAudio resample_level_audio(const data::LoadedLevel& level, int32_t rate) {
    PreparedAudio out;
    out.rate = rate;
    out.clips.reserve(level.effect.clips.size());
    // The resampler quality (speex 10) is set in audio/resample.cpp; this is the only place a level is converted.
    for (const data::EffectClip& clip : level.effect.clips) {
        const auto it = level.effect_pcm.find(clip.filename);
        out.clips.push_back(it != level.effect_pcm.end() ? audio::effect_clip(it->second, rate) : audio::Pcm{});
    }
    out.bgm = audio::resample(level.bgm_pcm, rate);
    return out;
}

}  // namespace

std::unique_ptr<PreparedAudio> prepare_audio(const data::LoadedLevel& level) {
    const auto start = std::chrono::steady_clock::now();
    std::string error;
    const std::unique_ptr<AAudioOutput> probe = AAudioOutput::open(error);  // closed on return
    if (!probe) {
        SR_LOGW("No audio device (%s): the level will be silent", error.c_str());
        return nullptr;
    }
    auto out = std::make_unique<PreparedAudio>(resample_level_audio(level, probe->rate()));
    SR_LOGI("Audio resampled to %d Hz in %.1f ms", out->rate, ms_since(start));
    return out;
}

struct GameplayLoop::State {
    State(ANativeWindow* w, const data::LoadedLevel& l, const PreparedAudio* p,
          std::unique_ptr<modes::Session> s, float a, const AudioSettings& au, ReplayRecording r,
          const modes::Replay* wr)
        : window(w),
          level(l),
          prepared(p),
          session(std::move(s)),
          aspect(a),
          audio_settings(au),
          replay(std::move(r)),
          frame_clock(session->times()) {
        play = dynamic_cast<modes::PlaySession*>(session.get());
        const audio::Pcm& bgm = level.bgm_pcm;
        if (bgm.rate > 0) bgm_length = float(double(bgm.frames()) / double(bgm.rate));
        if (play == nullptr) {
            watch_replay = wr;
            if (wr) ticks = modes::replay_ticks(*wr);
            bar_rects = render::watch_bar_rects(aspect);
            end_time = double(session->end_time());
        }
        shown_ns.store(steady_ns());
    }

    ANativeWindow* window;  // acquired by start(), released by stop()
    const data::LoadedLevel& level;
    const PreparedAudio* prepared;  // may be null
    std::unique_ptr<modes::Session> session;
    float aspect;  // Runtime Environment's
    AudioSettings audio_settings;
    ReplayRecording replay;
    std::atomic<bool> stopping{false};
    ALooper* looper = nullptr;  // set by the thread (acquired) before it reports setup; released by stop()
    std::promise<std::string> ready;
    bool ready_set = false;  // render thread only: `ready` has a value

    // Render thread only (the surface can be taken away and given back by run_task).
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    EGLConfig config = nullptr;
    EGLint format = 0;
    // Work for the render thread from other threads (surface detach and attach): one task at a time, run after the
    // looper wakes.
    std::mutex task_mutex;
    std::condition_variable task_cv;
    std::function<void()> task;
    bool task_done = false;
    std::string task_error;  // a task's outcome, read by the caller after task_done
    std::unique_ptr<render::GlRenderer> renderer;
    std::unique_ptr<particle::ParticleSystem> particles;
    // Audio (null without a stream). Destroyed output first: its callbacks use the mixer.
    std::unique_ptr<audio::Mixer> mixer;
    std::unique_ptr<audio::MusicClock> music;
    std::unique_ptr<AAudioOutput> output;
    time::FrameClock frame_clock;
    int64_t start_ns = -1;
    int64_t period_ns = kPresentLatencyNs;  // the display's frame period (API 33+: from the frame timelines)
    int64_t pipeline_ns = kPresentLatencyNs;  // the current frame's expected present time minus its vsync time
    // Read by the UI thread (overlay(), fault()).
    std::atomic<float> shown_time{float(-kPreRoll)};  // the last frame's music time
    float bgm_length = 0;                             // s, for the overlay's time text
    mutable std::mutex fault_mutex;
    GameplayLoop::Fault fault_info;
    std::atomic<bool> faulted{false};
    // Play (null in Watch): the session, the pool that turns pointer events into each cycle's touches, the surface size.
    modes::PlaySession* play = nullptr;
    modes::TouchPool pool;
    int32_t width = 0, height = 0;
    // Render thread, Play with a replay output (set up once the surface size is known): the touches the session got
    // this frame, and every stepped frame's touches so far.
    std::vector<modes::Touch> frame_touches;
    std::optional<modes::TouchRecorder> recorder;
    // The UI thread appends to `raw_touches` under `touch_mutex`; the render thread swaps it once per frame.
    std::mutex touch_mutex;
    std::vector<modes::RawTouch> raw_touches;  // surface pixels
    std::atomic<bool> accepting{false};  // touches are taken: Play running, set up, not ended or faulted
    // Play's state for Kotlin (playState): the status, the result once ended, the haptic levels not yet drained.
    // `play_status` is what the UI last asked for (pause, resume, retry write it at once, so a poll never sees an
    // older state); the render thread follows it with `phase`.
    std::mutex play_mutex;
    std::atomic<float> play_status{GameplayLoop::kPlayRunning};
    float play_result[9] = {};
    bool partial = false;  // under play_mutex: the result is a quit's
    std::vector<int32_t> haptics;
    // Pause, countdown, retry and quit (render thread, except the requests).
    enum class Phase { Running, Paused, Countdown } phase = Phase::Running;
    std::atomic<float> countdown_left{0};  // s, for the digit
    std::atomic<bool> retry_requested{false}, quit_requested{false};
    double paused_time = 0;      // the music time frozen by the pause
    int64_t countdown_ns = 0;    // present time the countdown began at
    const modes::FrameOutput* last_out = nullptr;  // the last stepped frame (valid until the next step)
    int64_t touch_events = 0;  // render thread: pointer events fed to the pool
    bool done = false;  // ended or faulted: no more steps
    // The next stepped frame is a time jump to this time (delta 0, skip set): the loop's skip to -kPreRoll or a
    // Watch seek.
    std::optional<double> skip_target;
    // Watch (all unset in Play). Constant after construction: the replay (markers), its ticks, the bar's control
    // rects, the session's end time at the start (the UI side's seek clamp).
    const modes::Replay* watch_replay = nullptr;
    std::vector<modes::WatchTick> ticks;
    render::WatchBarRects bar_rects{};
    double end_time = 0;
    // What the UI asked for, under `watch_mutex` (short: copies only). `paused` includes the stop at the end with
    // repeat off (`by_end`: a seek from there runs on); `seek_seq` counts seeks, the latest to `seek_to`.
    struct WatchRequest {
        bool paused = false, by_end = false, repeat = true, scrubbing = false;
        uint64_t seek_seq = 0;
        double seek_to = 0;
    };
    mutable std::mutex watch_mutex;
    WatchRequest watch_req;
    std::atomic<int64_t> shown_ns{0};          // steady_ns() the bar was last shown at
    std::atomic<uint64_t> seek_applied{0};     // the last seek_seq the render thread took
    // Render thread: the state in effect. `w_paused`: stage frozen, mixer paused. `w_scrubbing`: the last seek was a
    // scrub (stage frozen at it, mixer paused) until the release. `w_hold`: the level ended with repeat off.
    bool w_paused = false, w_scrubbing = false, w_hold = false, w_repeat = true;
    int64_t loops = 0;
    int64_t retries = 0;
    bool posted = false;  // render thread: a Choreographer callback is pending
    std::atomic<float> menu_box[4] = {};  // the Menu widget's box, written each stepped frame
    std::atomic<bool> menu_known{false};
    // The built-in HUD's inputs as of the last stepped frame (Runtime UI, stats, music time); read by overlay().
    mutable std::mutex hud_mutex;
    float hud_ui[100] = {};
    modes::Stats hud_stats;
    float hud_time = 0;
    bool hud_known = false;
    bool gl_error_logged = false;
    bool silent_logged = false;
    int64_t report_ns = 0;
    size_t logged_judgments = 0;  // Judgments already written to logcat (debug builds)
    int64_t frames = 0, window_frames = 0;
    // The FPS counter (render_fps): frames drawn since `fps_from_ns` (the vsync time it began at, 0 before the first).
    int64_t fps_from_ns = 0, fps_frames = 0;
    std::atomic<float> fps{0};
    double window_ms = 0, window_max_ms = 0;
    size_t window_max_sprites = 0;
};

namespace {

using State = GameplayLoop::State;

const char* mode_name(const State& s) { return s.play ? "Play" : "Watch"; }

std::string egl_error(const char* what) {
    char text[64];
    std::snprintf(text, sizeof text, "%s failed (EGL 0x%04x)", what, unsigned(eglGetError()));
    return text;
}

void teardown_audio(State& s) {
    s.output.reset();  // stops the stream's callbacks first
    s.music.reset();
    s.mixer.reset();
}

// The mixer at the stream's rate with the level's clips (index i = the effect data's clip i) and BGM, started so
// music time 0 is kPreRoll into the stream. Without a stream the level plays silent on the vsync clock.
void setup_audio(State& s) {
    const auto start = std::chrono::steady_clock::now();
    std::string error;
    s.output = AAudioOutput::open(error);
    if (!s.output) {
        SR_LOGW("No audio (%s): music time follows vsync", error.c_str());
        return;
    }
    const int32_t rate = s.output->rate();
    s.mixer = std::make_unique<audio::Mixer>(rate);
    // Resampled at load (prepare_audio); only a device whose rate changed since gets the slow path here. The mixer's
    // own resample of already-converted audio is a stereo copy.
    PreparedAudio fallback;
    const PreparedAudio* audio = s.prepared;
    if (audio == nullptr || audio->rate != rate) {
        SR_LOGW("Audio resampled at start (device rate %d Hz, prepared %d Hz)", rate, audio ? audio->rate : 0);
        fallback = resample_level_audio(s.level, rate);
        audio = &fallback;
    }
    s.mixer->set_clips(audio->clips);
    s.mixer->set_bgm(audio->bgm, s.level.level.bgm_offset);
    s.mixer->set_volumes(s.audio_settings.effect_volume, s.audio_settings.bgm_volume);
    s.mixer->set_audio_offset(s.audio_settings.audio_offset);
    s.mixer->start(int64_t(kPreRoll * rate));
    s.music = std::make_unique<audio::MusicClock>(*s.mixer);
    if (!s.output->start(*s.mixer, error)) {
        SR_LOGW("No audio (%s): music time follows vsync", error.c_str());
        teardown_audio(s);
        return;
    }
    SR_LOGI("Audio ready in %.1f ms (%d Hz, offset %.3f s)", ms_since(start), rate,
            double(s.audio_settings.audio_offset));
}

// The EGL surface on `s.window` (setup, and an attach after a detach), made current with the context.
std::string make_surface(State& s) {
    ANativeWindow_setBuffersGeometry(s.window, 0, 0, s.format);
    s.surface = eglCreateWindowSurface(s.display, s.config, s.window, nullptr);
    if (s.surface == EGL_NO_SURFACE) return egl_error("eglCreateWindowSurface");
    // The layer is tagged dataspace V0_SRGB on a P3 display, so SurfaceFlinger
    // (and screencap) convert both apps' frames alike.
    if (__builtin_available(android 28, *)) {
        if (ANativeWindow_setBuffersDataSpace(s.window, ADATASPACE_SRGB) != 0)
            SR_LOGW("ANativeWindow_setBuffersDataSpace(SRGB) failed");
    }
    if (eglMakeCurrent(s.display, s.surface, s.surface, s.context) != EGL_TRUE) return egl_error("eglMakeCurrent");
    eglSwapInterval(s.display, 1);
    if (__builtin_available(android 30, *)) {
        if (ANativeWindow_setFrameRate(s.window, 120.0f, ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) != 0)
            SR_LOGW("ANativeWindow_setFrameRate(120) failed");
    }
    return {};
}

// EGL ES 3 on the window: exactly RGBA8, no depth, stencil or MSAA; the default colour space, so GL writes
// the values as they are (no sRGB encode), with the buffers tagged V0_SRGB.
std::string setup(State& s) {
    s.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (s.display == EGL_NO_DISPLAY || eglInitialize(s.display, nullptr, nullptr) != EGL_TRUE)
        return egl_error("eglInitialize");
    const EGLint attribs[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                              EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                              EGL_DEPTH_SIZE, 0, EGL_STENCIL_SIZE, 0, EGL_SAMPLE_BUFFERS, 0, EGL_NONE};
    EGLint count = 0;
    if (eglChooseConfig(s.display, attribs, nullptr, 0, &count) != EGL_TRUE || count <= 0)
        return egl_error("eglChooseConfig");
    std::vector<EGLConfig> configs(static_cast<size_t>(count));
    eglChooseConfig(s.display, attribs, configs.data(), count, &count);
    EGLConfig config = nullptr;
    for (EGLint i = 0; i < count && config == nullptr; ++i) {
        EGLint r = 0, g = 0, b = 0, a = 0, depth = 0, samples = 0;
        eglGetConfigAttrib(s.display, configs[size_t(i)], EGL_RED_SIZE, &r);
        eglGetConfigAttrib(s.display, configs[size_t(i)], EGL_GREEN_SIZE, &g);
        eglGetConfigAttrib(s.display, configs[size_t(i)], EGL_BLUE_SIZE, &b);
        eglGetConfigAttrib(s.display, configs[size_t(i)], EGL_ALPHA_SIZE, &a);
        eglGetConfigAttrib(s.display, configs[size_t(i)], EGL_DEPTH_SIZE, &depth);
        eglGetConfigAttrib(s.display, configs[size_t(i)], EGL_SAMPLES, &samples);
        if (r == 8 && g == 8 && b == 8 && a == 8 && depth == 0 && samples <= 1) config = configs[size_t(i)];
    }
    if (config == nullptr) return "no RGBA8 EGL config without depth or MSAA";
    s.config = config;
    eglGetConfigAttrib(s.display, config, EGL_NATIVE_VISUAL_ID, &s.format);

    const EGLint context_attribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    s.context = eglCreateContext(s.display, config, EGL_NO_CONTEXT, context_attribs);
    if (s.context == EGL_NO_CONTEXT) return egl_error("eglCreateContext");
    if (const std::string error = make_surface(s); !error.empty()) return error;

    EGLint width = 0, height = 0;
    eglQuerySurface(s.display, s.surface, EGL_WIDTH, &width);
    eglQuerySurface(s.display, s.surface, EGL_HEIGHT, &height);
    s.width = width;
    s.height = height;
    // The replay's play area is the surface; its points are px from there, its times without the input offset.
    if (s.play && !s.replay.out.empty() && s.replay.slot)
        s.recorder.emplace(width, height, s.audio_settings.input_offset);
    const auto start = std::chrono::steady_clock::now();
    s.renderer = std::make_unique<render::GlRenderer>();
    if (!s.renderer->init(width, height)) return "renderer: " + s.renderer->error();
    s.renderer->upload(s.level, s.aspect);
    s.particles = std::make_unique<particle::ParticleSystem>(s.level.particle, particle::kParticleSeed);
    if (!s.renderer->error().empty()) return "renderer: " + s.renderer->error();
    SR_LOGI("Renderer %dx%d, %s mode, %s; textures uploaded in %.1f ms (background blur %.1f ms)", width, height,
            s.renderer->lightweight() ? "lightweight" : "standard",
            reinterpret_cast<const char*>(glGetString(GL_RENDERER)), ms_since(start), s.renderer->blur_ms());
    setup_audio(s);
    return {};
}

void teardown(State& s) {
    teardown_audio(s);
    if (s.display == EGL_NO_DISPLAY) return;
    // Its GL objects go with the context current. A detached surface (Play, in the background) leaves none: try a
    // surfaceless context, and if the driver has none let the context's destruction free the objects.
    if (s.surface == EGL_NO_SURFACE && s.context != EGL_NO_CONTEXT &&
        eglMakeCurrent(s.display, EGL_NO_SURFACE, EGL_NO_SURFACE, s.context) != EGL_TRUE)
        (void)s.renderer.release();
    s.renderer.reset();
    eglMakeCurrent(s.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (s.surface != EGL_NO_SURFACE) eglDestroySurface(s.display, s.surface);
    if (s.context != EGL_NO_CONTEXT) eglDestroyContext(s.display, s.context);
    // No eglTerminate: the display is shared with the app's other EGL users (the UI renderer).
    eglReleaseThread();
    s.surface = EGL_NO_SURFACE;
    s.context = EGL_NO_CONTEXT;
    s.display = EGL_NO_DISPLAY;
}

// The level ended on this frame (`present_ns`), which drew nothing. Watch skips to -2 s on the next
// frame, with no preprocess and no terminate-all, and its period is the BGM's length plus 2 s. So the next frame's
// present time becomes music time -2 s: the mixer restarts there (the BGM from its start, the preprocess clips
// again; clips still sounding run out), the music clock moves its line there, the frame clock skips, and the
// particles start over.
void restart_loop(State& s, double ended_at, int64_t present_ns) {
    const int64_t next_ns = present_ns + s.period_ns;
    constexpr double kRestart = -kPreRoll;
    if (s.output) {
        // The mixer frame the old line puts at the next present time is where music time -2 s now goes (the old
        // mapping must be read before the restart replaces it).
        const int64_t frame = s.mixer->music_to_frame(s.music->music_time_at(next_ns));
        s.mixer->restart_loop(frame, kRestart);
        s.music->rebase(next_ns, kRestart);
        for (const modes::AudioCmd& cmd : s.session->preprocess_audio()) s.mixer->submit(cmd);
    } else {
        s.start_ns = next_ns;  // the vsync clock: music time is (present - start) - pre-roll
    }
    s.particles = std::make_unique<particle::ParticleSystem>(s.level.particle, particle::kParticleSeed);
    s.skip_target = kRestart;
    s.shown_ns.store(steady_ns());  // the bar shows at each restart, as at the start
    ++s.loops;
    SR_LOGI("Watch loop %lld: ended at %.3f s after %lld frames, restarting at %.1f s", static_cast<long long>(s.loops),
            ended_at, static_cast<long long>(s.frames), kRestart);
}

// The level stopped for good (a fault, an internal error): stepping ends, the last frame stays, and the UI reads why.
void set_fault(State& s, const char* label, const std::string& detail, double time) {
    {
        std::lock_guard<std::mutex> lock(s.fault_mutex);
        s.fault_info = GameplayLoop::Fault{label, detail, time};
    }
    s.faulted.store(true);
    s.accepting.store(false);
    s.done = true;
    // Always on the render thread, which owns the audio objects, and frame() never runs again: stop the BGM and any
    // looped voice now, instead of letting them play on under the fault message.
    teardown_audio(s);
}

// Play: this frame's touches. The pointers the UI thread queued since the last frame become TouchPool events in
// arrival order (each pointer's are in time order), and the pool's touches for `frame_time` go to the session (and stay
// in `frame_touches` for the recorder, which takes them after the step). While paused (or counting down) they are
// dropped and the pool is cleared.
void feed_touches(State& s, double frame_time, bool paused) {
    std::vector<modes::RawTouch> raw;
    {
        std::lock_guard<std::mutex> lock(s.touch_mutex);
        raw.swap(s.raw_touches);
    }
    if (!paused) s.touch_events += int64_t(raw.size());
    // The frame-timeline map (drain_touches has the rule): the same map the frame's own music time comes from, the
    // audio clock or, without an audio stream, the vsync clock.
    modes::drain_touches(raw, s.pool, paused, s.width, s.height, s.audio_settings.input_offset, s.pipeline_ns,
                         [&s](int64_t ns) {
                             return s.output ? s.music->music_time_at(ns) : double(ns - s.start_ns) * 1e-9 - kPreRoll;
                         });
    if (!paused) {
        s.frame_touches = s.pool.frame(frame_time);
        s.play->set_touches(s.frame_touches);
    }
}

// A run that ended on its own leaves its replay. Built here on the render thread (copies of the session's result,
// judgments, exports and streams, and the recorded touches; `t` is the ending frame's time, the run's duration), then
// written on a detached thread that holds only its own copies and the slot. The slot reads Writing before Kotlin can
// see the run end (finish_play sets the state after this).
void start_replay_write(State& s, double t) {
    if (!s.recorder) return;
    const std::shared_ptr<modes::ReplaySlot> slot = s.replay.slot;
    const uint64_t token = slot->begin();
    try {
        modes::ReplayData data = modes::replay_data(*s.play, s.level, *s.recorder, t, s.width, s.height,
                                                    s.audio_settings.input_offset);
        std::thread([slot, token, base = s.replay.out, data = std::move(data), options = s.replay.options] {
            try {
                const auto begin = std::chrono::steady_clock::now();
                if (slot->write(token, base, data, options))
                    SR_LOGI("Replay written in %.1f ms: %s", ms_since(begin), base.c_str());
                else
                    SR_LOGW("Replay not written (a failed write, or a newer run or a retry superseded it): %s",
                            base.c_str());
            } catch (...) {
                try {
                    slot->finish(token, false);
                } catch (...) {
                }
            }
        }).detach();
    } catch (const std::exception& e) {  // an allocation, or no thread to write on
        SR_LOGE("Replay not written: %s", e.what());
        slot->finish(token, false);
    } catch (...) {
        SR_LOGE("Replay not written");
        slot->finish(token, false);
    }
}

// A Play run is over: it ended on its own at `t` (the frame drew nothing), or the player quit (`partial`: the
// stats so far; Exit goes to the result with them). Stepping stops and the last frame stays. Looped
// voices stop either way, so the result screen is silent; a quit cuts the rest of the audio too. Kotlin reads the
// state and the result.
void finish_play(State& s, double t, bool partial) {
    if (!partial) start_replay_write(s, t);  // Quits never leave a replay
    const modes::GameplayResult r = s.play->result();
    {
        std::lock_guard<std::mutex> lock(s.play_mutex);
        const float values[9] = {float(int(r.grade)), float(r.arcade_score), float(r.accuracy_score), float(r.combo),
                                 float(r.perfect),    float(r.great),        float(r.good),           float(r.miss),
                                 float(r.total)};
        std::copy(values, values + 9, s.play_result);
        s.partial = partial;
    }
    if (s.mixer) {
        if (partial && s.phase == State::Phase::Running) s.mixer->pause(s.mixer->music_to_frame(t));
        s.mixer->stop_looped();
    }
    s.accepting.store(false);
    s.play_status.store(GameplayLoop::kPlayEnded);
    s.done = true;
    SR_LOGI("Play %s at %.3f s after %lld frames: %s, arcade %d, accuracy %d, combo %d, P %d G %d Good %d M %d of %d; "
            "%zu judgments counted",
            partial ? "quit" : "ended", t, static_cast<long long>(s.frames), modes::grade_name(r.grade),
            r.arcade_score, r.accuracy_score, r.combo, r.perfect, r.great, r.good, r.miss, r.total,
            s.play->judgments().size());
}

// The countdown after a resume lasts exactly this long (3, 2, 1 for a third of a second each).
constexpr int64_t kCountdownNs = 1'000'000'000;

// Pause. The stage freezes on the frame last shown (nothing steps; the surface keeps its last buffer), the mixer
// cuts all audio at once and stands music time still at that frame; voices and pending plays stay. From a countdown
// the mixer is already paused.
void pause_play(State& s) {
    if (s.phase == State::Phase::Running) {
        s.paused_time = double(s.shown_time.load());
        if (s.mixer) s.mixer->pause(s.mixer->music_to_frame(s.paused_time));
        SR_LOGI("Play paused at %.3f s", s.paused_time);
    }
    s.phase = State::Phase::Paused;
    s.countdown_left.store(0);
    s.accepting.store(false);
}

// The countdown is over at `present_ns`, `overshoot_ns` past its end. Time is the paused time plus the overshoot
// (pauseTime + d), so the music clock and the mixer's music zero move to put that at `present_ns`, and the frame clock
// (its last time is the paused time) steps by d. Returns false if the UI paused again meanwhile.
bool resume_play(State& s, int64_t present_ns, int64_t overshoot_ns) {
    float expected = GameplayLoop::kPlayCountdown;
    if (!s.play_status.compare_exchange_strong(expected, GameplayLoop::kPlayRunning)) return false;
    const double d = double(overshoot_ns) * 1e-9;
    if (s.output) {
        // The mixer frame the unpaused stream plays at `present_ns` (the old mapping, read before resume moves it) is
        // where paused time + d goes now.
        const int64_t at_present = s.mixer->music_to_frame(s.music->music_time_at(present_ns));
        s.mixer->resume(at_present - int64_t(d * double(s.mixer->rate())));
        s.music->rebase(present_ns, s.paused_time + d);
    } else {
        s.start_ns = present_ns - int64_t((s.paused_time + d + kPreRoll) * 1e9);
    }
    s.phase = State::Phase::Running;
    s.countdown_left.store(0);
    s.accepting.store(true);
    SR_LOGI("Play resumed at %.3f s (+%.4f s)", s.paused_time, d);
    return true;
}

// Retry. Immediately, no countdown and no transition: the session back to its post-preparation state, the audio
// restarted so that -2 s is what plays at `present_ns` (the pause ends, every voice is dropped, the preprocess clips
// are queued again), the clocks started over at -2 s, the particles rebuilt, no touch carried over.
void retry_play(State& s, int64_t present_ns) {
    s.play->restart();
    s.logged_judgments = 0;
    s.last_out = nullptr;
    s.pool.clear();
    {
        std::lock_guard<std::mutex> lock(s.touch_mutex);
        s.raw_touches.clear();
    }
    // A new run: no touch recorded so far, and no replay for Kotlin (a write in flight finishes unseen; never waited on).
    s.frame_touches.clear();
    if (s.recorder) s.recorder->clear();
    if (s.replay.slot) s.replay.slot->reset();
    constexpr double kRestart = -kPreRoll;
    if (s.output) {
        const int64_t frame = s.mixer->music_to_frame(s.music->music_time_at(present_ns));
        s.mixer->restart_loop(frame, kRestart, /*keep_voices=*/false);
        s.music->rebase(present_ns, kRestart);
        for (const modes::AudioCmd& cmd : s.play->preprocess_audio()) s.mixer->submit(cmd);
    } else {
        s.start_ns = present_ns;
    }
    s.particles = std::make_unique<particle::ParticleSystem>(s.level.particle, particle::kParticleSeed);
    s.frame_clock.restart(float(kRestart));
    s.skip_target.reset();
    s.shown_time.store(float(kRestart));
    {
        std::lock_guard<std::mutex> lock(s.play_mutex);
        s.haptics.clear();
    }
    s.phase = State::Phase::Running;
    s.countdown_left.store(0);
    s.accepting.store(true);
    ++s.retries;
    SR_LOGI("Play retry %lld: back at %.1f s", static_cast<long long>(s.retries), kRestart);
}

// Play, at the start of each frame: follows what the UI asked for (quit, retry, pause, resume), advances the countdown.
// True when this frame must not step or draw (paused, counting down, quit, no surface).
bool control(State& s, int64_t present_ns) {
    if (s.quit_requested.exchange(false)) {
        finish_play(s, double(s.shown_time.load()), /*partial=*/true);
        return true;
    }
    if (s.retry_requested.exchange(false)) retry_play(s, present_ns);
    const float want = s.play_status.load();
    if (want == GameplayLoop::kPlayPaused && s.phase != State::Phase::Paused) {
        pause_play(s);
    } else if (want == GameplayLoop::kPlayCountdown && s.phase == State::Phase::Paused) {
        s.phase = State::Phase::Countdown;
        s.countdown_ns = present_ns;
    }
    if (s.phase == State::Phase::Countdown) {
        const int64_t elapsed = present_ns - s.countdown_ns;
        if (elapsed < kCountdownNs) {
            s.countdown_left.store(float(double(kCountdownNs - elapsed) * 1e-9));
        } else if (!resume_play(s, present_ns, elapsed - kCountdownNs)) {
            return true;
        }
    }
    if (s.phase != State::Phase::Running || s.surface == EGL_NO_SURFACE) {
        feed_touches(s, 0, /*paused=*/true);
        return true;
    }
    return false;
}

// Watch: the time the stage stands at (a pending skip's target once the mixer has been moved there).
double stage_time(const State& s) { return s.skip_target ? *s.skip_target : double(s.shown_time.load()); }

// Watch's pause: as Play's, the stage freezes and the mixer cuts the audio and stands music time still at the
// stage's time; voices and pending plays stay.
void pause_watch(State& s) {
    s.w_paused = true;
    if (s.output) s.mixer->pause(s.mixer->music_to_frame(stage_time(s)));
    SR_LOGI("Watch paused at %.3f s", stage_time(s));
}

// Watch's resume: at once, no countdown. Play's bookkeeping with the overshoot one frame period: this frame shows the
// paused time plus a period, which the mixer's resume and the music clock's line put at `present_ns`.
void resume_watch(State& s, int64_t present_ns) {
    const double d = double(s.period_ns) * 1e-9, t0 = stage_time(s);
    if (s.output) {
        // The mixer frame the unpaused stream plays at `present_ns` (the mapping a pause does not move).
        const int64_t at_present = s.mixer->music_to_frame(s.music->music_time_at(present_ns));
        s.mixer->resume(at_present - int64_t(d * double(s.mixer->rate())));
        s.music->rebase(present_ns, t0 + d);
    } else {
        s.start_ns = present_ns - int64_t((t0 + d + kPreRoll) * 1e9);
    }
    s.w_paused = false;
    SR_LOGI("Watch resumed at %.3f s", t0);
}

// Watch's seek: this frame jumps to `target` (clamped to [-kPreRoll, end]), which the lifetime system takes like the
// loop's skip; the particles start over. A scrub keeps the mixer paused (the stage shows the target, silent, and
// stays there). A release restarts the audio so `target` plays at `present_ns`: every voice is dropped, the
// preprocess clips are queued again from `target` on (the earlier ones would all start at once), and the mixer pauses
// at `target` again when the run is paused.
void seek_watch(State& s, double target, bool scrubbing, bool paused, int64_t present_ns) {
    const double t = modes::clamp_seek(target, double(s.session->end_time()));
    if (scrubbing) {
        if (s.output) s.mixer->pause(s.mixer->music_to_frame(stage_time(s)));  // nothing while already paused
        s.w_scrubbing = true;
    } else {
        if (s.output) {
            const int64_t frame = s.mixer->music_to_frame(s.music->music_time_at(present_ns));
            s.mixer->restart_loop(frame, t, /*keep_voices=*/false);  // also ends a pause
            s.music->rebase(present_ns, t);
            for (const modes::AudioCmd& cmd : s.session->preprocess_audio())
                if (cmd.op != modes::AudioCmd::PlayScheduled || double(cmd.time) >= t) s.mixer->submit(cmd);
            if (paused) s.mixer->pause(s.mixer->music_to_frame(t));
        } else {
            s.start_ns = present_ns - int64_t((t + kPreRoll) * 1e9);
        }
        s.w_scrubbing = false;
        s.w_paused = paused;
    }
    s.w_hold = false;
    s.skip_target = t;
    s.shown_time.store(float(t));  // this frame shows it (watch_state never reads the old time once the seek is taken)
    s.particles = std::make_unique<particle::ParticleSystem>(s.level.particle, particle::kParticleSeed);
    SR_LOGI("Watch seek to %.3f s%s%s", t, scrubbing ? " (scrubbing)" : "", paused ? " (paused)" : "");
}

// Watch, at the start of each frame: follows the UI's requests (a seek, pause, resume, a resume at the end). True when
// this frame must not step or draw (paused, scrubbing, stopped at the end): the surface keeps the last frame.
bool watch_control(State& s, int64_t present_ns) {
    State::WatchRequest r;
    {
        std::lock_guard<std::mutex> lock(s.watch_mutex);
        r = s.watch_req;
    }
    s.w_repeat = r.repeat;
    std::optional<double> target;
    bool scrubbing = false;
    const bool new_seek = r.seek_seq != s.seek_applied.load();
    if (new_seek) {
        target = r.seek_to;
        scrubbing = r.scrubbing;
    } else if (s.w_hold && !r.paused) {
        target = -kPreRoll;  // a resume at the end starts over
    }
    if (target) {
        seek_watch(s, *target, scrubbing, r.paused, present_ns);
        if (new_seek) s.seek_applied.store(r.seek_seq);  // after shown_time holds the target
        return false;  // this frame shows the target
    }
    if (s.w_hold || s.w_scrubbing) return true;
    if (r.paused != s.w_paused) {
        if (r.paused) pause_watch(s);
        else resume_watch(s, present_ns);
    }
    return s.w_paused;
}

// Watch with repeat off: the level ended at `t` (this frame drew nothing). The last drawn frame stays, the mixer
// pauses (looped voices end), and the state reads paused (by the end: a seek from here runs on) with the bar shown.
void hold_at_end(State& s, double t) {
    s.w_hold = true;
    s.w_paused = true;
    if (s.output) {
        s.mixer->pause(s.mixer->music_to_frame(t));
        s.mixer->stop_looped();
    }
    {
        std::lock_guard<std::mutex> lock(s.watch_mutex);
        // A seek still in flight wins: the UI asked to go somewhere, so the request stays as it is.
        if (!s.watch_req.paused && s.watch_req.seek_seq == s.seek_applied.load()) {
            s.watch_req.paused = true;
            s.watch_req.by_end = true;
        }
    }
    s.shown_ns.store(steady_ns());
    SR_LOGI("Watch ended at %.3f s after %lld frames: stopped (repeat off)", t, static_cast<long long>(s.frames));
}

// One frame: `frame_ns` is its vsync, `present_ns` when it is expected on screen (both CLOCK_MONOTONIC).
void frame(State& s, int64_t frame_ns, int64_t present_ns) {
    if (s.start_ns < 0) {
        s.start_ns = present_ns;
        s.report_ns = frame_ns;
        s.shown_ns.store(steady_ns());  // the bar's first showing counts from the first frame, not from State's setup
    }
    s.pipeline_ns = present_ns - frame_ns;  // this frame's touches go on its timeline (feed_touches)
    double music_time = -kPreRoll;
    if (s.output) {
        // Music time is read at the frame's present time (expected presentation, kPresentLatencyNs before API 33),
        // not its vsync time. Screen recordings can't measure the picture/sound
        // offset to better than tens of ms, so this stays the design choice.
        // The device never came back: music time carries on from where it was held, on present time, silent.
        if (s.output->poll(*s.music)) s.music->on_silent(present_ns);
        if (s.music->silent() && !s.silent_logged) {
            s.silent_logged = true;
            SR_LOGW("Audio output is gone for good: the level continues without sound");
        }
    }
    // Play: quit, retry, pause, resume and the countdown are settled before the clock is read, because a resume or
    // a retry moves it. A paused frame steps and draws nothing: the surface keeps the last buffer.
    if (s.play && control(s, present_ns)) return;
    // Watch: a seek, pause or resume moves the clocks too; a frozen frame steps and draws nothing.
    if (!s.play && watch_control(s, present_ns)) return;
    if (s.output) {
        music_time = s.music->music_time_at(present_ns);
    } else {
        music_time = double(present_ns - s.start_ns) * 1e-9 - kPreRoll;
    }
    // The frame after a level's end is exactly -2 s with delta 0 and skip set, as a Skip(-2 s) makes it.
    // A Watch seek is the same jump to its target.
    const time::FrameTime ft = s.skip_target ? s.frame_clock.skip_to(float(*s.skip_target))
                                             : s.frame_clock.next(music_time, float(double(s.period_ns) * 1e-9));
    s.skip_target.reset();
    const double t = ft.time;
    s.shown_time.store(float(t));

    const auto begin = std::chrono::steady_clock::now();
    if (s.play) feed_touches(s, t, /*paused=*/false);
    const modes::FrameOutput& out = s.session->step(ft);
    s.last_out = &out;
    // After the step, as srun's play harness; the ending frame runs no system, so nothing is recorded there.
    if (s.recorder && !out.ended) s.recorder->frame(t, s.frame_touches);
    // Watch, a scrub onto the end: the last frame stays, and the release decides (loop or stop).
    if (!s.play && out.ended && s.w_scrubbing && !s.session->faulted()) return;
    // Watch with repeat off: the end stops here, before the empty end frame reaches the HUD or the screen.
    if (!s.play && out.ended && !s.w_repeat && !s.session->faulted()) {
        hold_at_end(s, t);
        return;
    }
    if (s.play || !out.ended) {  // Watch's end frame is empty: the HUD keeps the last frame's
        std::lock_guard<std::mutex> lock(s.hud_mutex);
        std::copy(std::begin(out.ui), std::end(out.ui), s.hud_ui);
        s.hud_stats = out.stats;
        s.hud_time = float(t);
        s.hud_known = true;
    }
#ifdef SR_JUDGMENT_LOG
    if (s.play) {  // One line per newly counted judgment, tag sairitsu_judge; no allocation
        const std::vector<modes::StatInput>& js = s.play->judgments();
        if (js.size() < s.logged_judgments) s.logged_judgments = 0;  // restart() cleared them
        char line[128];
        for (; s.logged_judgments < js.size(); ++s.logged_judgments) {
            modes::format_judgment(line, sizeof line, js[s.logged_judgments], t);
            __android_log_write(ANDROID_LOG_INFO, "sairitsu_judge", line);
        }
    }
#endif
    if (s.play) {  // Runtime UI widget 0 (Menu): anchor x, y, pivot x, y, width, height
        const float* m = out.ui;
        if (m[4] > 0 && m[5] > 0) {
            const float l = m[0] - m[2] * m[4], b = m[1] - m[3] * m[5];
            s.menu_box[0].store(l);
            s.menu_box[1].store(b);
            s.menu_box[2].store(l + m[4]);
            s.menu_box[3].store(b + m[5]);
            s.menu_known.store(true);
        }
    }
    if (s.session->faulted()) {
        SR_LOGE("%s stopped at %.3f s: %s", mode_name(s), t, s.session->fault().detail.c_str());
        set_fault(s, fault_label(s.session->fault().kind), s.session->fault().detail, t);
        return;
    }
    if (s.play) {
        if (out.ended) {
            finish_play(s, t, /*partial=*/false);
            return;
        }
        if (out.haptic != 0) {  // One level per frame; Kotlin drains them
            std::lock_guard<std::mutex> lock(s.play_mutex);
            s.haptics.push_back(out.haptic);
        }
    }
    if (s.mixer && !s.w_scrubbing)  // a scrub's frames are silent (the release restarts the audio)
        for (const modes::AudioCmd& cmd : out.audio) s.mixer->submit(cmd);
    const std::vector<render::SpriteQuad> sprites = render::build_sprite_list(out, s.level.skin);
    const render::BackgroundQuad background =
        render::layout_background(s.level.background, s.level.background_configuration, out.background);
    // The frame's particle calls take effect at the frame's time and are drawn in the same frame.
    for (const modes::ParticleCmd& cmd : out.particles) s.particles->apply(cmd, ft.time);
    std::vector<render::SpriteQuad> particles;
    s.particles->emit(ft.time, out.particle_transform, particles);
    // Watch with a replay: where the fingers were at this time, over the sprites.
    std::vector<modes::ReplayMarker> markers;
    if (s.watch_replay) markers = modes::replay_markers(*s.watch_replay, t, s.aspect);
    s.renderer->draw(sprites, background, particles, {}, markers);  // built-in UI: Compose draws it
    if (!s.renderer->error().empty() && !s.gl_error_logged) {
        SR_LOGE("%s (frame %lld)", s.renderer->error().c_str(), static_cast<long long>(s.frames));
        s.gl_error_logged = true;
    }
    if (eglSwapBuffers(s.display, s.surface) != EGL_TRUE) {
        const std::string detail = egl_error("eglSwapBuffers");
        SR_LOGE("%s stopped at %.3f s: %s", mode_name(s), t, detail.c_str());
        set_fault(s, "internal error", detail, t);
        return;
    }
    const double ms = ms_since(begin);
    ++s.frames;
    ++s.window_frames;
    if (s.fps_from_ns == 0) {
        s.fps_from_ns = frame_ns;
    } else if (++s.fps_frames; frame_ns - s.fps_from_ns >= kFpsEveryNs) {
        s.fps.store(float(double(s.fps_frames) * 1e9 / double(frame_ns - s.fps_from_ns)));
        s.fps_from_ns = frame_ns;
        s.fps_frames = 0;
    }
    s.window_ms += ms;
    s.window_max_ms = std::max(s.window_max_ms, ms);
    s.window_max_sprites = std::max(s.window_max_sprites, sprites.size());
    if (frame_ns - s.report_ns >= kReportEveryNs) {
        SR_LOGI("t %.1f s: %lld frames, step + draw mean %.2f ms, max %.2f ms, up to %zu sprites, %d audio commands "
                "dropped",
                t, static_cast<long long>(s.window_frames), s.window_ms / double(s.window_frames), s.window_max_ms,
                s.window_max_sprites, s.mixer ? int(s.mixer->dropped()) : 0);
        if (s.play)
            SR_LOGI("Play t %.1f s: %zu judgments counted, %lld touch events fed", t, s.play->judgments().size(),
                    static_cast<long long>(s.touch_events));
        s.report_ns = frame_ns;
        s.window_frames = 0;
        s.window_ms = s.window_max_ms = 0;
        s.window_max_sprites = 0;
    }
    if (s.frames <= kLogFirstFrames)
        SR_LOGI("frame %lld: music time %.4f s, time %.4f s, delta %.4f s%s", static_cast<long long>(s.frames),
                music_time, t, double(ft.delta), ft.skip ? ", skip" : "");
    if (out.ended && !s.play) restart_loop(s, t, present_ns);  // Play ends instead (finish_play)
}

void post(State& s);

void on_frame_at(State& s, int64_t frame_ns, int64_t present_ns) {
    s.posted = false;
    if (s.stopping.load()) return;
    // Nothing may unwind through the Choreographer's C frames: an exception stops the level instead.
    try {
        frame(s, frame_ns, present_ns);
    } catch (const std::exception& e) {
        SR_LOGE("%s stopped: internal error: %s", mode_name(s), e.what());
        set_fault(s, "internal error", e.what(), double(s.shown_time.load()));
    } catch (...) {
        SR_LOGE("%s stopped: internal error", mode_name(s));
        set_fault(s, "internal error", "unknown", double(s.shown_time.load()));
    }
    // Without a surface (Play in the background) no more frames are asked for; attach posts again.
    if (!s.done && !s.stopping.load() && s.surface != EGL_NO_SURFACE) post(s);
}

// API 33+: the preferred frame timeline's expected presentation time.
void on_vsync(const AChoreographerFrameCallbackData* data, void* user) {
    if (__builtin_available(android 33, *)) {  // only ever posted there
        const int64_t frame_ns = AChoreographerFrameCallbackData_getFrameTimeNanos(data);
        const size_t preferred = AChoreographerFrameCallbackData_getPreferredFrameTimelineIndex(data);
        const int64_t present_ns =
            AChoreographerFrameCallbackData_getFrameTimelineExpectedPresentationTimeNanos(data, preferred);
        if (AChoreographerFrameCallbackData_getFrameTimelinesLength(data) > preferred + 1) {
            const int64_t next_ns =
                AChoreographerFrameCallbackData_getFrameTimelineExpectedPresentationTimeNanos(data, preferred + 1);
            if (next_ns > present_ns) static_cast<State*>(user)->period_ns = next_ns - present_ns;
        }
        on_frame_at(*static_cast<State*>(user), frame_ns, present_ns);
    }
}

void on_frame(int64_t frame_ns, void* data) {
    on_frame_at(*static_cast<State*>(data), frame_ns, frame_ns + kPresentLatencyNs);
}

void on_frame_long(long frame_ns, void* data) { on_frame(int64_t(frame_ns), data); }

void post(State& s) {
    s.posted = true;
    AChoreographer* choreographer = AChoreographer_getInstance();
    if (__builtin_available(android 33, *)) {
        AChoreographer_postVsyncCallback(choreographer, on_vsync, &s);
    } else if (__builtin_available(android 29, *)) {
        AChoreographer_postFrameCallback64(choreographer, on_frame, &s);
    } else {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
        AChoreographer_postFrameCallback(choreographer, on_frame_long, &s);  // API 26-28 (64-bit ABIs only)
#pragma clang diagnostic pop
    }
}

// The paused frame again, on a new surface (an attach): nothing steps, so the last output is still what was shown.
void redraw(State& s) {
    if (s.last_out == nullptr || s.surface == EGL_NO_SURFACE) return;
    const modes::FrameOutput& out = *s.last_out;
    const float t = s.shown_time.load();
    const std::vector<render::SpriteQuad> sprites = render::build_sprite_list(out, s.level.skin);
    const render::BackgroundQuad background =
        render::layout_background(s.level.background, s.level.background_configuration, out.background);
    std::vector<render::SpriteQuad> particles;
    s.particles->emit(t, out.particle_transform, particles);
    s.renderer->draw(sprites, background, particles, {});
    eglSwapBuffers(s.display, s.surface);
}

// Runs queued work (a surface detach or attach) from another thread.
void service_task(State& s) {
    std::function<void()> fn;
    {
        std::lock_guard<std::mutex> lock(s.task_mutex);
        if (!s.task) return;
        fn = std::move(s.task);
        s.task = nullptr;
    }
    try {
        fn();
    } catch (...) {
    }
    {
        std::lock_guard<std::mutex> lock(s.task_mutex);
        s.task_done = true;
    }
    s.task_cv.notify_all();
}

// Runs `fn` on the render thread and waits for it (3 s at most). False when the loop is stopping or the thread did not
// answer in time; `fn` must then not touch anything the caller frees, so it captures by value.
bool run_task(State& s, std::function<void()> fn) {
    constexpr auto kWait = std::chrono::seconds(3);
    std::unique_lock<std::mutex> lock(s.task_mutex);
    if (s.stopping.load() || s.looper == nullptr || s.task) return false;
    s.task = std::move(fn);
    s.task_done = false;
    ALooper_wake(s.looper);
    return s.task_cv.wait_for(lock, kWait, [&s] { return s.task_done; });
}

void report_ready(State& s, const std::string& error) {
    if (s.ready_set) return;
    s.ready_set = true;
    s.ready.set_value(error);
}

void run_loop(State& s) {
    const std::string error = setup(s);
    if (!error.empty()) {
        teardown(s);
        report_ready(s, error);
        return;
    }
    report_ready(s, {});
    s.accepting.store(s.play != nullptr);
    post(s);
    while (!s.stopping.load()) {
        ALooper_pollOnce(-1, nullptr, nullptr, nullptr);
        service_task(s);
    }
    teardown(s);
}

// The thread's body: an exception (from setup, say a failed allocation in the blur) becomes a start error, or ends a
// running loop, instead of std::terminate.
void run(State& s) {
    s.looper = ALooper_prepare(0);
    ALooper_acquire(s.looper);
    std::string error;
    try {
        run_loop(s);
        return;
    } catch (const std::exception& e) {
        error = std::string("internal error: ") + e.what();
    } catch (...) {
        error = "internal error";
    }
    SR_LOGE("%s loop failed: %s", mode_name(s), error.c_str());
    try {
        teardown(s);
    } catch (...) {
    }
    report_ready(s, error);
}

}  // namespace

GameplayLoop::GameplayLoop(std::unique_ptr<State> state) : state_(std::move(state)) {}

std::unique_ptr<GameplayLoop> GameplayLoop::start(ANativeWindow* window, const data::LoadedLevel& level,
                                                  const PreparedAudio* prepared,
                                                  std::unique_ptr<modes::Session> session, float aspect,
                                                  const AudioSettings& audio, ReplayRecording replay,
                                                  const modes::Replay* watch_replay, std::string& error) {
    std::unique_ptr<GameplayLoop> loop(new GameplayLoop(std::make_unique<State>(
        window, level, prepared, std::move(session), aspect, audio, std::move(replay), watch_replay)));
    // From here the loop owns a reference: stop() (the destructor too, if std::thread throws) releases it.
    ANativeWindow_acquire(window);
    std::future<std::string> ready = loop->state_->ready.get_future();
    State* state = loop->state_.get();
    loop->thread_ = std::thread([state] { run(*state); });
    error = ready.get();
    if (!error.empty()) {
        loop->stop();
        return nullptr;
    }
    return loop;
}

GameplayLoop::~GameplayLoop() { stop(); }

const char* fault_label(vm::Fault::Kind kind) {
    switch (kind) {
        case vm::Fault::RunawayLoop: return "runaway loop";
        case vm::Fault::StackOverflow: return "stack overflow";
        default: return "error";
    }
}

namespace {

// Seconds since Watch's bar was last shown; 0 while the user scrubs (it stays shown under the finger).
float since_shown(const State& s, const State::WatchRequest& r) {
    if (r.scrubbing) return 0;
    return float(double(steady_ns() - s.shown_ns.load()) * 1e-9);
}

}  // namespace

render::UiOverlay GameplayLoop::overlay() const {
    const State* s = state_.get();
    if (s == nullptr) return {};
    render::UiOverlay o;
    {
        modes::FrameOutput f;
        float time = 0;
        {
            std::lock_guard<std::mutex> lock(s->hud_mutex);
            if (s->hud_known) {
                std::copy(std::begin(s->hud_ui), std::end(s->hud_ui), f.ui);
                f.stats = s->hud_stats;
                time = s->hud_time;
            }
        }
        o = render::build_hud(f, s->level.configuration.ui, time, s->bgm_length);
    }
    if (s->play == nullptr) {  // Watch's control bar, above the HUD
        State::WatchRequest r;
        {
            std::lock_guard<std::mutex> lock(s->watch_mutex);
            r = s->watch_req;
        }
        const float t = s->shown_time.load();
        const render::WatchBar watch{since_shown(*s, r), t, s->bgm_length, r.paused, r.repeat, s->ticks};
        render::UiOverlay bar = render::build_overlay(watch, s->aspect);
        o.rects.insert(o.rects.end(), bar.rects.begin(), bar.rects.end());
        o.texts.insert(o.texts.end(), bar.texts.begin(), bar.texts.end());
        o.icons.insert(o.icons.end(), bar.icons.begin(), bar.icons.end());
    }
    return o;
}

// Watch's controls: the request is written at once under the short request lock (so watch_state already reads it), the
// render thread takes it at its next frame (watch_control).
void GameplayLoop::watch_pause(bool paused) {
    State* s = state_.get();
    if (s == nullptr || s->play != nullptr) return;
    {
        std::lock_guard<std::mutex> lock(s->watch_mutex);
        s->watch_req.paused = paused;
        s->watch_req.by_end = false;
    }
    s->shown_ns.store(steady_ns());
}

void GameplayLoop::seek(double t, bool scrubbing) {
    State* s = state_.get();
    if (s == nullptr || s->play != nullptr) return;
    {
        std::lock_guard<std::mutex> lock(s->watch_mutex);
        State::WatchRequest& r = s->watch_req;
        r.seek_to = modes::clamp_seek(t, s->end_time);
        r.scrubbing = scrubbing;
        ++r.seek_seq;
        if (r.by_end) {  // stopped at the end: a seek runs on from its target
            r.paused = false;
            r.by_end = false;
        }
    }
    s->shown_ns.store(steady_ns());
}

void GameplayLoop::set_repeat(bool on) {
    State* s = state_.get();
    if (s == nullptr || s->play != nullptr) return;
    {
        std::lock_guard<std::mutex> lock(s->watch_mutex);
        s->watch_req.repeat = on;
    }
    s->shown_ns.store(steady_ns());
}

void GameplayLoop::show_bar() {
    State* s = state_.get();
    if (s == nullptr || s->play != nullptr) return;
    s->shown_ns.store(steady_ns());
}

bool GameplayLoop::watch_state(float* out) const {
    std::fill(out, out + kWatchStateFloats, 0.0f);
    const State* s = state_.get();
    if (s == nullptr || s->play != nullptr) return false;
    State::WatchRequest r;
    {
        std::lock_guard<std::mutex> lock(s->watch_mutex);
        r = s->watch_req;
    }
    // A seek the render thread has not taken yet is where the stage is going: report it (a stop right after a seek
    // remembers the seek's time).
    out[0] = r.seek_seq != s->seek_applied.load() ? float(r.seek_to) : s->shown_time.load();
    out[1] = s->bgm_length;
    out[2] = r.paused ? 1.0f : 0.0f;
    out[3] = r.repeat ? 1.0f : 0.0f;
    const float since = since_shown(*s, r);
    out[4] = r.paused || (since >= 0 && since < kBarShownFor) ? 1.0f : 0.0f;
    std::copy(s->bar_rects.track, s->bar_rects.track + 4, out + kWatchTrack);
    std::copy(s->bar_rects.repeat, s->bar_rects.repeat + 4, out + kWatchRepeatRect);
    std::copy(s->bar_rects.pause, s->bar_rects.pause + 4, out + kWatchPauseRect);
    return true;
}

float GameplayLoop::render_fps() const {
    const State* s = state_.get();
    return s == nullptr ? 0.0f : s->fps.load();
}

bool GameplayLoop::fault(Fault& out) const {
    const State* s = state_.get();
    if (s == nullptr || !s->faulted.load()) return false;
    std::lock_guard<std::mutex> lock(s->fault_mutex);
    out = s->fault_info;
    return true;
}

void GameplayLoop::touch(int64_t time_ns, int32_t count, const float* data) {
    State* s = state_.get();
    if (s == nullptr || !s->accepting.load()) return;
    constexpr size_t kMaxQueued = 4096;  // a loop that stopped drawing must not grow this without end
    std::lock_guard<std::mutex> lock(s->touch_mutex);
    // The UI sends TouchPhase codes 0-3; the cast below relies on the enum's values.
    static_assert(modes::TouchEvent::Down == 0 && modes::TouchEvent::Move == 1 && modes::TouchEvent::Up == 2 &&
                  modes::TouchEvent::Cancel == 3);
    for (int32_t i = 0; i < count && s->raw_touches.size() < kMaxQueued; ++i) {
        const float* p = data + size_t(i) * 4;
        const int phase = int(p[1]);
        if (phase < 0 || phase > int(modes::TouchEvent::Cancel)) continue;
        s->raw_touches.push_back(
            modes::RawTouch{time_ns, int32_t(p[0]), static_cast<modes::TouchEvent::Phase>(phase), p[2], p[3]});
    }
}

void GameplayLoop::play_state(float* out, std::vector<int32_t>& haptics) {
    State* s = state_.get();
    std::fill(out, out + kPlayStateFloats, 0.0f);
    if (s == nullptr) return;
    out[0] = s->play_status.load();
    if (out[0] == kPlayCountdown) out[kPlayCountdownLeft] = s->countdown_left.load();
    std::lock_guard<std::mutex> lock(s->play_mutex);
    if (out[0] == kPlayEnded) {
        std::copy(s->play_result, s->play_result + 9, out + 1);
        out[kPlayPartial] = s->partial ? 1.0f : 0.0f;
    }
    haptics.insert(haptics.end(), s->haptics.begin(), s->haptics.end());
    s->haptics.clear();
}

// The UI-facing requests write `play_status` at once (so the next poll already shows it) and the render thread follows
// at its next frame. Transitions are compare-and-swaps: a pause that lands while the render thread finishes a
// countdown, or after the end, wins or does nothing, never both.
void GameplayLoop::pause() {
    State* s = state_.get();
    if (s == nullptr || s->play == nullptr) return;
    for (const float from : {kPlayRunning, kPlayCountdown}) {
        float expected = from;
        if (s->play_status.compare_exchange_strong(expected, kPlayPaused)) {
            s->accepting.store(false);  // nothing queued from here on; the render thread clears what is
            return;
        }
    }
}

void GameplayLoop::resume() {
    State* s = state_.get();
    if (s == nullptr || s->play == nullptr) return;
    s->countdown_left.store(1.0f);
    float expected = kPlayPaused;
    s->play_status.compare_exchange_strong(expected, kPlayCountdown);
}

void GameplayLoop::retry() {
    State* s = state_.get();
    if (s == nullptr || s->play == nullptr) return;
    for (const float from : {kPlayRunning, kPlayPaused, kPlayCountdown}) {
        float expected = from;
        if (s->play_status.compare_exchange_strong(expected, kPlayRunning)) {
            s->retry_requested.store(true);
            return;
        }
    }
}

void GameplayLoop::quit() {
    State* s = state_.get();
    if (s == nullptr || s->play == nullptr || s->play_status.load() == kPlayEnded) return;
    // The state reads "running" until the render thread has ended the run, so a poll in between does not show the pause
    // menu again.
    for (const float from : {kPlayPaused, kPlayCountdown}) {
        float expected = from;
        s->play_status.compare_exchange_strong(expected, kPlayRunning);
    }
    s->quit_requested.store(true);
}

bool GameplayLoop::menu_rect(float* out) const {
    const State* s = state_.get();
    if (s == nullptr || !s->menu_known.load()) return false;
    for (int i = 0; i < 4; ++i) out[i] = s->menu_box[size_t(i)].load();
    return true;
}

bool GameplayLoop::detach_surface() {
    State* s = state_.get();
    if (s == nullptr || s->play == nullptr || s->faulted.load()) return false;
    pause();
    return run_task(*s, [s] {
        if (s->phase != State::Phase::Paused && s->play_status.load() == kPlayPaused) pause_play(*s);
        if (s->surface != EGL_NO_SURFACE) {
            eglMakeCurrent(s->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroySurface(s->display, s->surface);
            s->surface = EGL_NO_SURFACE;
        }
        if (s->window != nullptr) {
            ANativeWindow_release(s->window);
            s->window = nullptr;
        }
        if (s->output) s->output->set_paused(true);  // nothing plays or renders in the background
        SR_LOGI("Play surface detached");
    });
}

bool GameplayLoop::attach_surface(ANativeWindow* window, std::string& error) {
    State* s = state_.get();
    if (s == nullptr || s->play == nullptr || s->faulted.load() || window == nullptr) {
        if (window != nullptr) ANativeWindow_release(window);
        error = "no running Play to attach to";
        return false;
    }
    s->task_error.clear();
    const bool ran = run_task(*s, [s, window] {
        if (ANativeWindow_getWidth(window) != s->width || ANativeWindow_getHeight(window) != s->height) {
            ANativeWindow_release(window);
            s->task_error = "the surface changed size";
            return;
        }
        s->window = window;  // ours now: released by detach or stop()
        s->task_error = make_surface(*s);
        if (!s->task_error.empty()) return;
        redraw(*s);
        if (s->output) {
            // The stream did not run while detached: its callbacks start again and music time restarts at the mixer's
            // frame (it is still paused: silence until Resume).
            s->output->set_paused(false);
            const int64_t now_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
                                       std::chrono::steady_clock::now().time_since_epoch()).count();
            s->music->rebase(now_ns, s->mixer->frame_to_music(double(s->mixer->frames_rendered())));
        }
        if (!s->posted) post(*s);
        SR_LOGI("Play surface attached");
    });
    if (!ran) {
        error = "the render thread did not answer";
        return false;
    }
    error = s->task_error;
    return error.empty();
}

void GameplayLoop::stop() {
    if (!state_) return;
    state_->stopping.store(true);
    if (state_->looper != nullptr) ALooper_wake(state_->looper);
    if (thread_.joinable()) thread_.join();
    if (state_->looper != nullptr) ALooper_release(state_->looper);
    if (state_->window != nullptr) ANativeWindow_release(state_->window);  // null after a detach
    SR_LOGI("%s loop stopped after %lld frames", mode_name(*state_), static_cast<long long>(state_->frames));
    state_.reset();
}

}  // namespace sr::android
