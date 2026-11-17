#pragma once

#include <aaudio/AAudio.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#include "audio/mixer.h"
#include "audio/music_clock.h"

namespace sr::android {

// One AAudio output stream fed by a Mixer: low-latency performance mode, exclusive sharing if granted (AAudio falls
// back to shared), float stereo, usage GAME and capture allowed (so scrcpy records it). The first stream opens at the
// device's rate; the mixer runs at that rate for the whole level.
//
// A disconnect (headphones unplugged, a Bluetooth or USB-C device switch) reopens the stream on a helper thread at
// the same rate, on whatever device is current, retrying for up to 5 s. The new stream continues from the mixer frame
// where rendering stopped; the MusicClock is told (on_stream_start), so music time holds there until the new stream
// plays and then runs on: no jump in music time, the picture waits for the sound.
class AAudioOutput {
public:
    // Opens a stream (not started). Returns null with `error` set on failure.
    static std::unique_ptr<AAudioOutput> open(std::string& error);
    ~AAudioOutput();  // stops and closes the stream, waits for a reopen in progress

    AAudioOutput(const AAudioOutput&) = delete;
    AAudioOutput& operator=(const AAudioOutput&) = delete;

    int32_t rate() const { return rate_; }

    // Starts playback from `mixer` (which must outlive this). Returns false with `error` set on failure.
    bool start(audio::Mixer& mixer, std::string& error);

    // Render thread, once per frame: tells `clock` about a stream switch and passes it the stream's latest timestamp.
    // Returns true once reopening has given up for good (no stream will come back): the caller then runs the clock on
    // present time (MusicClock::on_silent).
    bool poll(audio::MusicClock& clock);

    // Render thread: stops the stream's callbacks (`paused`, the app is in the background) or starts them again. A reopen
    // in progress, or a stream that is gone, makes this do nothing. The mixer does not render while the stream is
    // paused, so the caller moves the music clock to the mixer's frame when it starts again.
    void set_paused(bool paused);

private:
    AAudioOutput() = default;

    aaudio_result_t open_stream(int32_t rate, AAudioStream** out);
    void close_stream();  // stream_mutex_ held
    void on_error(aaudio_result_t error);
    void reopen();
    bool reopen_once();  // closes the stream, then opens and starts a new one (retrying); false if it gave up
    void switched();  // the next poll restarts the clock at the mixer's current frame

    static aaudio_data_callback_result_t data_callback(AAudioStream* stream, void* user, void* audio, int32_t frames);
    static void error_callback(AAudioStream* stream, void* user, aaudio_result_t error);

    int32_t rate_ = 0;
    std::atomic<audio::Mixer*> mixer_{nullptr};

    std::mutex stream_mutex_;  // guards stream_ (the render thread only try-locks it)
    AAudioStream* stream_ = nullptr;
    std::atomic<bool> stream_ok_{false};  // stream_ is running and not disconnected
    std::atomic<bool> gave_up_{false};    // reopening failed for good

    std::atomic<int64_t> base_{0};        // mixer frame of the current stream's frame 0
    std::atomic<uint32_t> generation_{0};  // bumped (after base_) on every switch
    uint32_t seen_generation_ = 0;         // render thread

    std::mutex thread_mutex_;  // guards reopen_thread_ and closing_
    std::thread reopen_thread_;
    std::atomic<bool> reopening_{false}, closing_{false};
};

}  // namespace sr::android
