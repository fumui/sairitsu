#include "android/aaudio_output.h"

#include <android/log.h>

#include <chrono>
#include <cstring>
#include <ctime>

namespace sr::android {

namespace {

constexpr char kTag[] = "sairitsu_runtime";
constexpr int kReopenAttempts = 50;
constexpr auto kReopenWait = std::chrono::milliseconds(100);

#define SR_LOGI(...) __android_log_print(ANDROID_LOG_INFO, kTag, __VA_ARGS__)
#define SR_LOGW(...) __android_log_print(ANDROID_LOG_WARN, kTag, __VA_ARGS__)
#define SR_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kTag, __VA_ARGS__)

std::string aaudio_error(const char* what, aaudio_result_t r) {
    return std::string(what) + " failed: " + AAudio_convertResultToText(r);
}

void log_stream(AAudioStream* s, const char* what) {
    SR_LOGI("Audio %s: %d Hz, %d ch, %s, %s, burst %d frames, device %d", what, AAudioStream_getSampleRate(s),
            AAudioStream_getChannelCount(s),
            AAudioStream_getSharingMode(s) == AAUDIO_SHARING_MODE_EXCLUSIVE ? "exclusive" : "shared",
            AAudioStream_getPerformanceMode(s) == AAUDIO_PERFORMANCE_MODE_LOW_LATENCY ? "low latency" : "not low latency",
            AAudioStream_getFramesPerBurst(s), AAudioStream_getDeviceId(s));
}

}  // namespace

std::unique_ptr<AAudioOutput> AAudioOutput::open(std::string& error) {
    std::unique_ptr<AAudioOutput> out(new AAudioOutput());
    AAudioStream* stream = nullptr;
    const aaudio_result_t r = out->open_stream(0, &stream);
    if (r != AAUDIO_OK) {
        error = aaudio_error("AAudioStreamBuilder_openStream", r);
        return nullptr;
    }
    out->stream_ = stream;
    out->rate_ = AAudioStream_getSampleRate(stream);
    log_stream(stream, "opened");
    return out;
}

aaudio_result_t AAudioOutput::open_stream(int32_t rate, AAudioStream** out) {
    AAudioStreamBuilder* b = nullptr;
    aaudio_result_t r = AAudio_createStreamBuilder(&b);
    if (r != AAUDIO_OK) return r;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(b, 2);
    if (rate > 0) AAudioStreamBuilder_setSampleRate(b, rate);  // a reopen keeps the mixer's rate; AAudio converts
    if (__builtin_available(android 28, *)) {
        AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_GAME);
        AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
    }
    if (__builtin_available(android 29, *)) AAudioStreamBuilder_setAllowedCapturePolicy(b, AAUDIO_ALLOW_CAPTURE_BY_ALL);
    AAudioStreamBuilder_setDataCallback(b, data_callback, this);
    AAudioStreamBuilder_setErrorCallback(b, error_callback, this);
    r = AAudioStreamBuilder_openStream(b, out);
    AAudioStreamBuilder_delete(b);
    if (r == AAUDIO_OK && (AAudioStream_getChannelCount(*out) != 2 ||
                           AAudioStream_getFormat(*out) != AAUDIO_FORMAT_PCM_FLOAT ||
                           (rate > 0 && AAudioStream_getSampleRate(*out) != rate))) {
        AAudioStream_close(*out);
        *out = nullptr;
        return AAUDIO_ERROR_UNIMPLEMENTED;  // not float stereo at the mixer's rate
    }
    return r;
}

AAudioOutput::~AAudioOutput() {
    {
        std::lock_guard<std::mutex> lock(thread_mutex_);
        closing_.store(true);
    }
    if (reopen_thread_.joinable()) reopen_thread_.join();  // no new one starts once closing_ is set
    std::lock_guard<std::mutex> lock(stream_mutex_);
    close_stream();
}

void AAudioOutput::close_stream() {
    stream_ok_.store(false);
    if (stream_ == nullptr) return;
    AAudioStream_requestStop(stream_);
    AAudioStream_close(stream_);  // waits for a callback in progress
    stream_ = nullptr;
}

bool AAudioOutput::start(audio::Mixer& mixer, std::string& error) {
    mixer_.store(&mixer, std::memory_order_release);
    std::lock_guard<std::mutex> lock(stream_mutex_);
    switched();
    const aaudio_result_t r = AAudioStream_requestStart(stream_);
    if (r != AAUDIO_OK) {
        error = aaudio_error("AAudioStream_requestStart", r);
        return false;
    }
    stream_ok_.store(true);
    return true;
}

void AAudioOutput::switched() {
    audio::Mixer* mixer = mixer_.load(std::memory_order_acquire);
    base_.store(mixer != nullptr ? mixer->frames_rendered() : 0, std::memory_order_relaxed);
    generation_.fetch_add(1, std::memory_order_release);
}

void AAudioOutput::set_paused(bool paused) {
    std::unique_lock<std::mutex> lock(stream_mutex_, std::try_to_lock);
    if (!lock.owns_lock() || stream_ == nullptr || !stream_ok_.load()) return;
    if (paused) {
        AAudioStream_requestPause(stream_);
    } else {
        AAudioStream_requestStart(stream_);
    }
}

bool AAudioOutput::poll(audio::MusicClock& clock) {
    std::unique_lock<std::mutex> lock(stream_mutex_, std::try_to_lock);
    if (!lock.owns_lock()) return gave_up_.load();  // a reopen is in progress: the clock holds
    const uint32_t generation = generation_.load(std::memory_order_acquire);
    if (generation != seen_generation_) {
        seen_generation_ = generation;
        clock.on_stream_start(base_.load(std::memory_order_relaxed));
    }
    if (stream_ == nullptr || !stream_ok_.load()) return gave_up_.load();
    int64_t position = 0, time_ns = 0;
    if (AAudioStream_getTimestamp(stream_, CLOCK_MONOTONIC, &position, &time_ns) == AAUDIO_OK) {
        timespec now{};
        clock_gettime(CLOCK_MONOTONIC, &now);
        clock.on_timestamp(position, time_ns, int64_t(now.tv_sec) * 1'000'000'000 + now.tv_nsec);
    }
    return false;
}

aaudio_data_callback_result_t AAudioOutput::data_callback(AAudioStream*, void* user, void* audio, int32_t frames) {
    audio::Mixer* mixer = static_cast<AAudioOutput*>(user)->mixer_.load(std::memory_order_acquire);
    if (mixer != nullptr)
        mixer->render(static_cast<float*>(audio), frames);
    else
        std::memset(audio, 0, size_t(frames) * 2 * sizeof(float));
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void AAudioOutput::error_callback(AAudioStream*, void* user, aaudio_result_t error) {
    static_cast<AAudioOutput*>(user)->on_error(error);
}

// AAudio's thread: the stream may not be closed or reopened here, so a helper thread does it.
void AAudioOutput::on_error(aaudio_result_t error) {
    SR_LOGW("Audio stream error: %s; reopening", AAudio_convertResultToText(error));
    stream_ok_.store(false);
    switched();  // rendering has stopped: the clock holds at the frame the new stream will start from
    std::lock_guard<std::mutex> lock(thread_mutex_);
    if (closing_.load() || reopening_.exchange(true)) return;
    if (reopen_thread_.joinable()) reopen_thread_.join();  // the previous reopen has finished (reopening_ was false)
    reopen_thread_ = std::thread([this] { reopen(); });
}

void AAudioOutput::reopen() {
    for (;;) {
        const bool ok = reopen_once();
        if (!ok && !closing_.load()) {
            SR_LOGE("Audio stream could not be reopened; the level continues without sound");
            gave_up_.store(true);  // poll tells the render thread, which switches the clock to present time
        }
        std::lock_guard<std::mutex> lock(thread_mutex_);
        // An error that arrived while this thread ran found reopening_ set and left it to us.
        if (ok && !stream_ok_.load() && !closing_.load()) continue;
        reopening_.store(false);
        return;
    }
}

bool AAudioOutput::reopen_once() {
    {
        std::lock_guard<std::mutex> lock(stream_mutex_);
        close_stream();
    }
    for (int attempt = 0; attempt < kReopenAttempts && !closing_.load(); ++attempt) {
        {
            std::lock_guard<std::mutex> lock(stream_mutex_);
            AAudioStream* stream = nullptr;
            aaudio_result_t r = open_stream(rate_, &stream);
            if (r == AAUDIO_OK) {
                stream_ = stream;
                switched();
                r = AAudioStream_requestStart(stream_);
                if (r == AAUDIO_OK) {
                    stream_ok_.store(true);
                    log_stream(stream_, "reopened");
                    return true;
                }
                close_stream();
            }
            if (attempt == 0) SR_LOGW("%s; retrying", aaudio_error("Audio reopen", r).c_str());
        }
        std::this_thread::sleep_for(kReopenWait);
    }
    return false;
}

}  // namespace sr::android
