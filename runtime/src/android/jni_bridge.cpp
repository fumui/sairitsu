// JNI surface for my.fumui.sairitsu.gameplay.NativeRuntime (a Kotlin object, so the
// natives are instance methods on its singleton).

#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <algorithm>
#include <chrono>
#include <exception>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

#include "android/gameplay_loop.h"
#include "android/render_at_times.h"
#include "data/load_error.h"
#include "data/resources.h"
#include "modes/play_session.h"
#include "modes/replay.h"
#include "modes/replay_file.h"
#include "modes/watch_session.h"
#include "vm/program.h"

namespace {

// Prefix of an error naming an engine function or block the runtime doesn't know. Must match
// NativeError.UNSUPPORTED in NativeRuntime.kt: "unsupported:" + function name (or "block <id>").
constexpr char kUnsupportedPrefix[] = "unsupported:";

// Prefix of an error that is a callback fault: "fault:" + label + ": " + detail. Must match NativeError.FAULT in
// NativeRuntime.kt, which puts it into "The level stopped: %s".
constexpr char kFaultPrefix[] = "fault:";

// NativeRuntime.start's mode codes (GameplayMode ordinals).
constexpr jint kModeWatch = 0, kModePlay = 1;

// NativeRuntime.start's settings, in AppSettings order: effect volume, BGM volume, audio offset (s), input
// offset (s). Entries past the end are 0.
constexpr size_t kSettingEffectVolume = 0, kSettingBgmVolume = 1, kSettingAudioOffset = 2, kSettingInputOffset = 3;

struct Runtime {
    std::optional<sr::data::LoadedLevel> level;
    std::optional<sr::vm::Program> watch;  // compiled watch data of `level`
    // `level`'s audio at the output device's rate, made at load (null: no device, a silent level).
    std::unique_ptr<sr::android::PreparedAudio> audio;
    // The last Play run's replay files (replayStatus). Shared with the loop and its detached writer threads, which may
    // outlive the loop and this Runtime.
    const std::shared_ptr<sr::modes::ReplaySlot> replay = std::make_shared<sr::modes::ReplaySlot>();
    // The replay the running Watch plays (start's replayIn; null: none). The session and the loop point at it, so it is
    // replaced only after the loop has stopped, and declared before the loop (destroyed after it).
    std::unique_ptr<sr::modes::Replay> watch_replay;
    // The running Watch; refers to `level` and `audio`, so it is declared last (destroyed first). `loop_mutex` guards
    // the pointer, so the UI thread's overlay and fault reads never meet a loop being destroyed.
    std::mutex loop_mutex;
    std::unique_ptr<sr::android::GameplayLoop> loop;

    // Stops the running loop (its destructor joins the render thread, outside the lock).
    void stop_loop() {
        std::unique_ptr<sr::android::GameplayLoop> old;
        {
            std::lock_guard<std::mutex> lock(loop_mutex);
            old = std::move(loop);
        }
    }

    void set_loop(std::unique_ptr<sr::android::GameplayLoop> next) {
        std::lock_guard<std::mutex> lock(loop_mutex);
        loop = std::move(next);
    }

    void reset() {
        stop_loop();
        watch_replay.reset();
        audio.reset();
        watch.reset();
        level.reset();
    }
};

Runtime* from_handle(jlong handle) { return reinterpret_cast<Runtime*>(static_cast<intptr_t>(handle)); }

// Thrown when a JNI call failed and left a Java exception pending: the native function must
// return at once and let it propagate.
struct JavaExceptionPending {};

std::string to_string(JNIEnv* env, jstring s) {
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) throw JavaExceptionPending{};
    std::string out = chars;
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

// A Java string from UTF-8 bytes. NewStringUTF wants modified UTF-8 and aborts under CheckJNI on
// anything else, and error text can carry arbitrary bytes from level files.
// Returns null with the Java exception pending when a JNI call fails.
jstring to_jstring(JNIEnv* env, const std::string& text) {
    jstring out = nullptr;
    jclass string_class = nullptr;
    jstring charset = nullptr;
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(text.size()));
    if (bytes != nullptr) {
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
        if (!env->ExceptionCheck()) string_class = env->FindClass("java/lang/String");
    }
    jmethodID ctor = string_class ? env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V") : nullptr;
    if (ctor != nullptr) charset = env->NewStringUTF("UTF-8");
    if (charset != nullptr) out = static_cast<jstring>(env->NewObject(string_class, ctor, bytes, charset));
    if (charset) env->DeleteLocalRef(charset);
    if (string_class) env->DeleteLocalRef(string_class);
    if (bytes) env->DeleteLocalRef(bytes);
    return out;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_create(JNIEnv*, jobject) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(new Runtime()));
}

extern "C" JNIEXPORT jstring JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_load(
    JNIEnv* env, jobject, jlong handle, jintArray kinds, jobjectArray paths) {
    Runtime* rt = from_handle(handle);
    if (rt == nullptr) return to_jstring(env, "internal error: no runtime (closed)");
    std::string error;
    try {
        const jsize n = env->GetArrayLength(kinds);
        if (env->GetArrayLength(paths) != n) throw sr::data::LoadError("kinds and paths differ in length");
        std::vector<jint> codes(static_cast<size_t>(n));
        env->GetIntArrayRegion(kinds, 0, n, codes.data());
        if (env->ExceptionCheck()) throw JavaExceptionPending{};
        std::map<sr::data::ResourceKind, std::filesystem::path> files;
        for (jsize i = 0; i < n; ++i) {
            if (codes[i] < 0 || codes[i] >= sr::data::kResourceKindCount) {
                throw sr::data::LoadError("unknown resource kind code " + std::to_string(codes[i]));
            }
            auto path = static_cast<jstring>(env->GetObjectArrayElement(paths, i));
            if (env->ExceptionCheck()) throw JavaExceptionPending{};
            if (path == nullptr) throw sr::data::LoadError("no path for resource kind code " + std::to_string(codes[i]));
            files[static_cast<sr::data::ResourceKind>(codes[i])] = std::filesystem::path(to_string(env, path));
            env->DeleteLocalRef(path);
        }
        rt->reset();
        rt->level.emplace(sr::data::load_level(files));
        rt->watch.emplace(sr::vm::compile(rt->level->watch, {sr::data::Mode::Watch}));
        // The resampling takes seconds: do it here, on the load thread, never under the surface lock in start().
        rt->audio = sr::android::prepare_audio(*rt->level);
        return nullptr;
    } catch (const JavaExceptionPending&) {
        rt->reset();
        return nullptr;  // the pending exception is thrown in Kotlin
    } catch (const sr::vm::CompileError& e) {
        error = kUnsupportedPrefix + e.function;
    } catch (const sr::data::LoadError& e) {
        error = e.what();
    } catch (const std::exception& e) {
        error = std::string("internal error: ") + e.what();
    } catch (...) {
        error = "internal error";
    }
    rt->reset();
    return to_jstring(env, error);
}

extern "C" JNIEXPORT jstring JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_describe(JNIEnv* env, jobject, jlong handle) {
    const Runtime* rt = from_handle(handle);
    if (rt == nullptr) return to_jstring(env, "no runtime (closed)");
    if (!rt->level) return to_jstring(env, "no level loaded");
    const std::string text = std::to_string(rt->level->watch.archetypes.size()) + " archetypes, " +
                             std::to_string(rt->level->level.entities.size()) + " entities";
    return to_jstring(env, text);
}

namespace {

std::vector<float> to_floats(JNIEnv* env, jfloatArray array) {
    if (array == nullptr) return {};
    std::vector<float> out(size_t(env->GetArrayLength(array)));
    env->GetFloatArrayRegion(array, 0, jsize(out.size()), out.data());
    if (env->ExceptionCheck()) throw JavaExceptionPending{};
    return out;
}

// Runtime Environment on the device for a width x height screen: its aspect, the full screen
// as the safe area, the offsets as set, no debug, no replay.
sr::modes::WatchConfig watch_config(int32_t width, int32_t height, std::vector<float> options,
                                    const std::vector<float>& settings) {
    sr::modes::WatchConfig config;
    config.options = std::move(options);
    sr::modes::Environment& env = config.env;
    env.aspect = float(width) / float(height);
    env.safe_x_min = -env.aspect;
    env.safe_x_max = env.aspect;
    env.safe_y_min = -1;
    env.safe_y_max = 1;
    env.audio_offset = settings.size() > kSettingAudioOffset ? settings[kSettingAudioOffset] : 0.0f;
    env.input_offset = settings.size() > kSettingInputOffset ? settings[kSettingInputOffset] : 0.0f;
    return config;
}

// Every Level Option value of the run: the given ones, then the configuration's defaults (as the sessions fill them).
std::vector<float> all_options(const sr::data::LoadedLevel& level, const std::vector<float>& given) {
    const std::vector<sr::data::EngineOption>& engine_options = level.configuration.options;
    std::vector<float> out(std::max(engine_options.size(), given.size()));
    for (size_t i = 0; i < out.size(); ++i) out[i] = i < given.size() ? given[i] : engine_options[i].def;
    return out;
}

// A replay file (gzip JSON) parsed. Throws LoadError when it can't be read or parsed.
sr::modes::Replay read_replay(const std::filesystem::path& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) throw sr::data::LoadError("can't open " + path.string());
    const std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    if (in.bad()) throw sr::data::LoadError("can't read " + path.string());
    return sr::modes::parse_replay(bytes);
}

// Runs a level in Watch or Play on `window` (the steps of NativeRuntime.start); a Play run's replay goes to
// `replay_out` (empty: none) through `slot`; a Watch plays `watch_replay` (null: none; it outlives the loop). Throws
// LoadError (CompileError included).
std::unique_ptr<sr::android::GameplayLoop> start_session(const sr::data::LoadedLevel& level,
                                                         const sr::android::PreparedAudio* prepared,
                                                         ANativeWindow* window, jint mode, std::vector<float> options,
                                                         const std::vector<float>& settings,
                                                         std::filesystem::path replay_out,
                                                         std::shared_ptr<sr::modes::ReplaySlot> slot,
                                                         const sr::modes::Replay* watch_replay, std::string& error) {
    const int32_t width = ANativeWindow_getWidth(window), height = ANativeWindow_getHeight(window);
    if (width <= 0 || height <= 0) throw sr::data::LoadError("the surface has no size");
    if (mode == kModePlay) watch_replay = nullptr;  // a replay is only watched
    sr::modes::WatchConfig config = watch_config(width, height, std::move(options), settings);
    config.replay = watch_replay;
    const sr::modes::Environment& env = config.env;

    const auto begin = std::chrono::steady_clock::now();
    std::unique_ptr<sr::modes::Session> session;
    if (mode == kModePlay) {
        sr::modes::PlayConfig play;
        play.options = config.options;
        play.env = config.env;
        session = sr::modes::PlaySession::create(level, play);
    } else {
        session = sr::modes::WatchSession::create(level, config);
    }
    session->prepare();
    if (session->faulted()) {
        error = kFaultPrefix + std::string(sr::android::fault_label(session->fault().kind)) + ": " +
                session->fault().detail;
        return nullptr;
    }
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - begin).count();
    __android_log_print(ANDROID_LOG_INFO, "sairitsu_runtime", "%s %dx%d prepared in %.1f ms",
                        mode == kModePlay ? "Play" : "Watch", width, height, ms);
    sr::android::AudioSettings audio;
    if (settings.size() > kSettingEffectVolume) audio.effect_volume = settings[kSettingEffectVolume];
    if (settings.size() > kSettingBgmVolume) audio.bgm_volume = settings[kSettingBgmVolume];
    audio.audio_offset = env.audio_offset;
    audio.input_offset = env.input_offset;
    sr::android::ReplayRecording replay;
    if (mode == kModePlay && !replay_out.empty()) {  // Watch runs are never recorded
        replay.out = std::move(replay_out);
        replay.options = all_options(level, config.options);
        replay.slot = std::move(slot);
    }
    return sr::android::GameplayLoop::start(window, level, prepared, std::move(session), env.aspect, audio,
                                            std::move(replay), watch_replay, error);
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_start(
    JNIEnv* env, jobject, jlong handle, jobject surface, jint mode, jfloatArray options, jfloatArray settings,
    jstring replay_out, jstring replay_in) {
    Runtime* rt = from_handle(handle);
    if (rt == nullptr) return to_jstring(env, "internal error: no runtime (closed)");
    rt->stop_loop();  // a running level stops first
    rt->replay->reset();  // every start is a new run: no replay yet (a write in flight finishes unseen)
    rt->watch_replay.reset();  // nothing points at it once the loop has stopped
    if (!rt->level) return to_jstring(env, "no level loaded");
    if (mode != kModeWatch && mode != kModePlay) return to_jstring(env, "internal error: unsupported mode");
    // Watch with a replay: parsed here, kept in the Runtime for the session's lifetime (WatchConfig::replay).
    if (mode == kModeWatch && replay_in != nullptr) {
        try {
            rt->watch_replay = std::make_unique<sr::modes::Replay>(read_replay(to_string(env, replay_in)));
        } catch (const JavaExceptionPending&) {
            return nullptr;  // the pending exception is thrown in Kotlin
        } catch (const std::exception& e) {
            return to_jstring(env, std::string("replay: ") + e.what());
        }
    }
    ANativeWindow* window = surface != nullptr ? ANativeWindow_fromSurface(env, surface) : nullptr;
    if (window == nullptr) return to_jstring(env, "internal error: no window for the surface");
    std::string error;
    try {
        std::filesystem::path out;
        if (replay_out != nullptr) out = std::filesystem::path(to_string(env, replay_out));
        rt->set_loop(start_session(*rt->level, rt->audio.get(), window, mode, to_floats(env, options),
                                   to_floats(env, settings), std::move(out), rt->replay, rt->watch_replay.get(),
                                   error));
    } catch (const JavaExceptionPending&) {
        ANativeWindow_release(window);
        return nullptr;  // the pending exception is thrown in Kotlin
    } catch (const sr::vm::CompileError& e) {
        error = kUnsupportedPrefix + e.function;
    } catch (const sr::data::LoadError& e) {
        error = e.what();
    } catch (const std::exception& e) {
        error = std::string("internal error: ") + e.what();
    } catch (...) {
        error = "internal error";
    }
    ANativeWindow_release(window);  // the loop holds its own reference
    {
        std::lock_guard<std::mutex> lock(rt->loop_mutex);
        if (rt->loop) return nullptr;
    }
    return to_jstring(env, error.empty() ? std::string("internal error: Watch did not start") : error);
}

extern "C" JNIEXPORT jstring JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_renderAtTimes(
    JNIEnv* env, jobject, jlong handle, jfloatArray times, jfloatArray options, jstring out_dir) {
    Runtime* rt = from_handle(handle);
    if (rt == nullptr) return to_jstring(env, "internal error: no runtime (closed)");
    rt->stop_loop();  // a running level stops first
    if (!rt->level) return to_jstring(env, "no level loaded");
    std::string error;
    try {
        const sr::modes::WatchConfig config =
            watch_config(sr::android::kRenderWidth, sr::android::kRenderHeight, to_floats(env, options), {});
        error = sr::android::render_at_times(*rt->level, config, to_floats(env, times),
                                             std::filesystem::path(to_string(env, out_dir)));
    } catch (const JavaExceptionPending&) {
        return nullptr;  // the pending exception is thrown in Kotlin
    } catch (const sr::vm::CompileError& e) {
        error = kUnsupportedPrefix + e.function;
    } catch (const sr::data::LoadError& e) {
        error = e.what();
    } catch (const std::exception& e) {
        error = std::string("internal error: ") + e.what();
    } catch (...) {
        error = "internal error";
    }
    return error.empty() ? nullptr : to_jstring(env, error);
}

extern "C" JNIEXPORT void JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_stop(JNIEnv*, jobject, jlong handle) {
    if (Runtime* rt = from_handle(handle)) rt->stop_loop();
}

// The built-in HUD and Watch's bar for the Compose layer (NativeOverlay in NativeRuntime.kt mirrors this).
// `out` gets a 4-float header: rect, text and icon counts, 0; then per rect 12 floats: four x, four y (screen units,
// x in -aspect..aspect, y up in -1..1), r, g, b, a (straight alpha); per text 11: x, y, size, pivot, r, g, b, a,
// bold, outline, 0; per icon 13: kind, four x, four y, r, g, b, a. At most kMaxRects / kMaxTexts / kMaxIcons of each
// (the rest is dropped), and no more rects than `out` has room for after the texts and icons (Watch's ticks, the last
// rects, go first). Returns the texts joined by '\n' (the i-th line is text i), or null when there is nothing.
extern "C" JNIEXPORT jstring JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_overlay(JNIEnv* env, jobject, jlong handle, jfloatArray out) {
    constexpr size_t kHeader = 4, kPerRect = 12, kPerText = 11, kPerIcon = 13;
    constexpr size_t kMaxRects = 320, kMaxTexts = 16, kMaxIcons = 16;
    Runtime* rt = from_handle(handle);
    if (rt == nullptr || out == nullptr) return nullptr;
    sr::render::UiOverlay overlay;
    {
        std::lock_guard<std::mutex> lock(rt->loop_mutex);
        if (!rt->loop) return nullptr;
        overlay = rt->loop->overlay();
    }
    if (overlay.texts.empty() && overlay.rects.empty() && overlay.icons.empty()) return nullptr;
    const size_t texts = std::min(overlay.texts.size(), kMaxTexts);
    const size_t icons = std::min(overlay.icons.size(), kMaxIcons);
    // Rects past what the caller's array holds are dropped (Watch's ticks are the last ones), never the whole overlay.
    const size_t capacity = size_t(env->GetArrayLength(out));
    const size_t fixed = kHeader + texts * kPerText + icons * kPerIcon;
    if (fixed > capacity) return nullptr;  // the caller's array is too small (a bug)
    const size_t rects = std::min({overlay.rects.size(), kMaxRects, (capacity - fixed) / kPerRect});
    std::vector<float> data = {float(rects), float(texts), float(icons), 0.0f};
    data.reserve(kHeader + rects * kPerRect + texts * kPerText + icons * kPerIcon);
    for (size_t n = 0; n < rects; ++n) {
        const sr::render::UiRect& r = overlay.rects[n];
        for (int i = 0; i < 4; ++i) data.push_back(r.screen.x[i]);
        for (int i = 0; i < 4; ++i) data.push_back(r.screen.y[i]);
        for (int i = 0; i < 4; ++i) data.push_back(r.rgba[i]);
    }
    std::string joined;
    for (size_t n = 0; n < texts; ++n) {
        const sr::render::UiText& t = overlay.texts[n];
        data.insert(data.end(), {t.x, t.y, t.size, t.pivot, t.rgba[0], t.rgba[1], t.rgba[2], t.rgba[3],
                                 t.bold ? 1.0f : 0.0f, t.outline ? 1.0f : 0.0f, 0.0f});
        if (n > 0) joined += '\n';
        joined += t.text;
    }
    for (size_t n = 0; n < icons; ++n) {
        const sr::render::UiIcon& c = overlay.icons[n];
        data.push_back(float(int(c.kind)));
        for (int i = 0; i < 4; ++i) data.push_back(c.box.x[i]);
        for (int i = 0; i < 4; ++i) data.push_back(c.box.y[i]);
        for (int i = 0; i < 4; ++i) data.push_back(c.rgba[i]);
    }
    if (data.size() > size_t(env->GetArrayLength(out))) return nullptr;  // the caller's array is too small (a bug)
    env->SetFloatArrayRegion(out, 0, jsize(data.size()), data.data());
    return to_jstring(env, joined);
}

// A fault that stopped the running level: "label\ndetail\ntime (s)", or null while it runs.
extern "C" JNIEXPORT jstring JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_fault(JNIEnv* env, jobject, jlong handle) {
    Runtime* rt = from_handle(handle);
    if (rt == nullptr) return nullptr;
    sr::android::GameplayLoop::Fault fault;
    {
        std::lock_guard<std::mutex> lock(rt->loop_mutex);
        if (!rt->loop || !rt->loop->fault(fault)) return nullptr;
    }
    return to_jstring(env, fault.label + "\n" + fault.detail + "\n" + std::to_string(fault.time));
}

// The render thread's frames per second (GameplayLoop::render_fps), 0 while nothing runs.
extern "C" JNIEXPORT jfloat JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_renderFps(JNIEnv*, jobject, jlong handle) {
    Runtime* rt = from_handle(handle);
    if (rt == nullptr) return 0.0f;
    std::lock_guard<std::mutex> lock(rt->loop_mutex);
    return rt->loop ? rt->loop->render_fps() : 0.0f;
}

// Play: `count` pointers of `data` (per pointer id, phase, x px, y px) at CLOCK_MONOTONIC `time_nanos`. Never blocks
// on start(): only the loop pointer's short lock and the loop's touch buffer.
extern "C" JNIEXPORT void JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_touch(
    JNIEnv* env, jobject, jlong handle, jlong time_nanos, jint count, jfloatArray data) {
    Runtime* rt = from_handle(handle);
    if (rt == nullptr || data == nullptr || count <= 0) return;
    if (env->GetArrayLength(data) < count * 4) return;
    std::vector<float> values(size_t(count) * 4);
    env->GetFloatArrayRegion(data, 0, jsize(values.size()), values.data());
    if (env->ExceptionCheck()) return;
    std::lock_guard<std::mutex> lock(rt->loop_mutex);
    if (rt->loop) rt->loop->touch(int64_t(time_nanos), count, values.data());
}

// Play's pause, resume (the countdown), retry and quit. Each takes only the loop pointer's short lock, never what
// start() holds, so the UI thread can call them at any time.
extern "C" JNIEXPORT void JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_pause(JNIEnv*, jobject, jlong handle) {
    Runtime* rt = from_handle(handle);
    if (rt == nullptr) return;
    std::lock_guard<std::mutex> lock(rt->loop_mutex);
    if (rt->loop) rt->loop->pause();
}

extern "C" JNIEXPORT void JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_resume(JNIEnv*, jobject, jlong handle) {
    Runtime* rt = from_handle(handle);
    if (rt == nullptr) return;
    std::lock_guard<std::mutex> lock(rt->loop_mutex);
    if (rt->loop) rt->loop->resume();
}

extern "C" JNIEXPORT void JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_retry(JNIEnv*, jobject, jlong handle) {
    Runtime* rt = from_handle(handle);
    if (rt == nullptr) return;
    std::lock_guard<std::mutex> lock(rt->loop_mutex);
    if (rt->loop) rt->loop->retry();
}

extern "C" JNIEXPORT void JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_quit(JNIEnv*, jobject, jlong handle) {
    Runtime* rt = from_handle(handle);
    if (rt == nullptr) return;
    std::lock_guard<std::mutex> lock(rt->loop_mutex);
    if (rt->loop) rt->loop->quit();
}

// Play: the engine's Menu widget box in screen units (left, bottom, right, top into `out`, 4 floats); false while unknown.
extern "C" JNIEXPORT jboolean JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_menuRect(JNIEnv* env, jobject, jlong handle, jfloatArray out) {
    Runtime* rt = from_handle(handle);
    if (rt == nullptr || out == nullptr || env->GetArrayLength(out) < 4) return JNI_FALSE;
    float box[4];
    {
        std::lock_guard<std::mutex> lock(rt->loop_mutex);
        if (!rt->loop || !rt->loop->menu_rect(box)) return JNI_FALSE;
    }
    env->SetFloatArrayRegion(out, 0, 4, box);
    return JNI_TRUE;
}

// Watch's controls (GameplayLoop::watch_pause, seek, set_repeat, show_bar). Each takes only the loop pointer's short lock,
// never what start() holds, so the UI thread can call them at any time; nothing outside Watch.
extern "C" JNIEXPORT void JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_watchPause(JNIEnv*, jobject, jlong handle, jboolean paused) {
    Runtime* rt = from_handle(handle);
    if (rt == nullptr) return;
    std::lock_guard<std::mutex> lock(rt->loop_mutex);
    if (rt->loop) rt->loop->watch_pause(paused != JNI_FALSE);
}

extern "C" JNIEXPORT void JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_seek(JNIEnv*, jobject, jlong handle, jfloat time, jboolean scrubbing) {
    Runtime* rt = from_handle(handle);
    if (rt == nullptr) return;
    std::lock_guard<std::mutex> lock(rt->loop_mutex);
    if (rt->loop) rt->loop->seek(double(time), scrubbing != JNI_FALSE);
}

extern "C" JNIEXPORT void JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_setRepeat(JNIEnv*, jobject, jlong handle, jboolean on) {
    Runtime* rt = from_handle(handle);
    if (rt == nullptr) return;
    std::lock_guard<std::mutex> lock(rt->loop_mutex);
    if (rt->loop) rt->loop->set_repeat(on != JNI_FALSE);
}

extern "C" JNIEXPORT void JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_showBar(JNIEnv*, jobject, jlong handle) {
    Runtime* rt = from_handle(handle);
    if (rt == nullptr) return;
    std::lock_guard<std::mutex> lock(rt->loop_mutex);
    if (rt->loop) rt->loop->show_bar();
}

// Watch: fills `out` (17 floats, GameplayLoop::watch_state's layout: time, length, paused, repeat, bar visible, then the
// track, repeat and pause rects as left, bottom, right, top in screen units). False (nothing written) outside Watch.
extern "C" JNIEXPORT jboolean JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_watchState(JNIEnv* env, jobject, jlong handle, jfloatArray out) {
    constexpr int kFloats = sr::android::GameplayLoop::kWatchStateFloats;
    Runtime* rt = from_handle(handle);
    if (rt == nullptr || out == nullptr || env->GetArrayLength(out) < kFloats) return JNI_FALSE;
    float values[kFloats];
    {
        std::lock_guard<std::mutex> lock(rt->loop_mutex);
        if (!rt->loop || !rt->loop->watch_state(values)) return JNI_FALSE;
    }
    env->SetFloatArrayRegion(out, 0, kFloats, values);
    return JNI_TRUE;
}

// Play, in the background: the loop pauses and lets go of the surface (true when it did), then takes a new one of the
// same size back with the paused frame on it. Both wait for the render thread (3 s at most) with the loop pointer's lock
// held; the render thread never takes that lock.
extern "C" JNIEXPORT jboolean JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_detachSurface(JNIEnv*, jobject, jlong handle) {
    Runtime* rt = from_handle(handle);
    if (rt == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(rt->loop_mutex);
    return rt->loop && rt->loop->detach_surface() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_attachSurface(JNIEnv* env, jobject, jlong handle, jobject surface) {
    Runtime* rt = from_handle(handle);
    if (rt == nullptr || surface == nullptr) return JNI_FALSE;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return JNI_FALSE;
    std::string error;
    std::lock_guard<std::mutex> lock(rt->loop_mutex);
    if (!rt->loop) {
        ANativeWindow_release(window);
        return JNI_FALSE;
    }
    if (rt->loop->attach_surface(window, error)) return JNI_TRUE;  // the loop owns the window reference
    __android_log_print(ANDROID_LOG_WARN, "sairitsu_runtime", "Surface attach failed: %s", error.c_str());
    return JNI_FALSE;
}

// Play: fills `out` (11 floats): the state (0 running, 1 paused, 2 countdown, 3 ended), then the countdown's seconds left
// at [1] while counting down, or once ended the result at [1..9] (grade ordinal, arcade, accuracy, combo, P, G, Good, M,
// total) and at [10] 1 for a quit's partial result. Returns the haptic levels queued since the last call.
extern "C" JNIEXPORT jintArray JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_playState(JNIEnv* env, jobject, jlong handle, jfloatArray out) {
    Runtime* rt = from_handle(handle);
    std::vector<int32_t> haptics;
    float values[sr::android::GameplayLoop::kPlayStateFloats] = {};
    if (rt != nullptr) {
        std::lock_guard<std::mutex> lock(rt->loop_mutex);
        if (rt->loop) rt->loop->play_state(values, haptics);
    }
    if (out != nullptr && env->GetArrayLength(out) >= sr::android::GameplayLoop::kPlayStateFloats)
        env->SetFloatArrayRegion(out, 0, sr::android::GameplayLoop::kPlayStateFloats, values);
    jintArray result = env->NewIntArray(jsize(haptics.size()));
    if (result != nullptr && !haptics.empty())
        env->SetIntArrayRegion(result, 0, jsize(haptics.size()), reinterpret_cast<const jint*>(haptics.data()));
    return result;
}

// Play: the ended run's judgment and accuracy pairs, flattened; empty while a run is live.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_playTimings(JNIEnv* env, jobject, jlong handle) {
    Runtime* rt = from_handle(handle);
    std::vector<float> timings;
    if (rt != nullptr) {
        std::lock_guard<std::mutex> lock(rt->loop_mutex);
        if (rt->loop) timings = rt->loop->play_timings();
    }
    jfloatArray result = env->NewFloatArray(jsize(timings.size()));
    if (result != nullptr && !timings.empty()) env->SetFloatArrayRegion(result, 0, jsize(timings.size()), timings.data());
    return result;
}

// Play: the last run's replay files (a ReplayStatus code: 0 none, 1 writing, 2 ready, 3 failed). Only the slot's short
// lock; never waits on a write.
extern "C" JNIEXPORT jint JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_replayStatus(JNIEnv*, jobject, jlong handle) {
    const Runtime* rt = from_handle(handle);
    if (rt == nullptr) return jint(sr::modes::ReplayStatus::None);
    return jint(rt->replay->status());
}

extern "C" JNIEXPORT void JNICALL
Java_my_fumui_sairitsu_gameplay_NativeRuntime_destroy(JNIEnv*, jobject, jlong handle) {
    delete from_handle(handle);
}
