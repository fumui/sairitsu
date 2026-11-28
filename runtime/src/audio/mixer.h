#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <vector>

#include "audio/decode.h"
#include "modes/presentation.h"

namespace sr::audio {

// A decoded effect clip made ready for the mixer: stereo at `rate`. The effect volume is
// linear, and a mono clip is panned to the centre at equal power (each channel x 1/sqrt 2); a mono BGM plays at
// unity.
Pcm effect_clip(const Pcm& decoded, int32_t rate);

// Sample-exact mixer for the effect clips and the BGM. Output is interleaved stereo float at `rate`.
//
// Threads: `submit` is called from one producer thread (the render thread) and `render` from the audio thread;
// the queue between them is lock-free and `render` never allocates or blocks. `set_clips`, `set_bgm` and
// `set_audio_offset` configure the mixer before playback and are not safe to call while `render` runs; `set_volumes`
// and `start` may be called at any time.
//
// Times. A command's time is a music time in seconds; the output frame it lands on is
// `frame_at_music_zero + round((t + audio_offset) * rate)` (the offset's sign is set in `music_to_frame` / `frame_to_music`).
// BGM sample 0 is at music time `-bgm_offset` (music time t plays BGM time t + bgm_offset). A command that arrives after its frame has been rendered plays from the
// right offset into the clip: it joins the mix as if it had started on time.
class Mixer {
public:
    explicit Mixer(int32_t rate);

    // Clip index i of an AudioCmd is clips[i]. Any rate and mono or stereo; converted to stereo at the mixer's rate.
    void set_clips(std::vector<Pcm> clips);
    void set_bgm(Pcm bgm, double bgm_offset);
    void set_volumes(float effect, float bgm);
    void set_audio_offset(double seconds);

    // Music time 0 is output frame `frame_at_music_zero` (in `frames_rendered()` units). Clears every playing voice
    // and the distance history at the next render (commands submitted after start() are kept), so it also restarts a
    // level.
    void start(int64_t frame_at_music_zero);

    // A loop: music time `music` becomes output frame `frame` from now on (so the BGM starts over `-bgm_offset -
    // music` seconds after it), the pending scheduled clips and the distance history are forgotten, and commands
    // submitted after this call play against the new mapping. Voices already playing run on to their end (a clip's
    // tail is not cut), because they hold absolute output frames. Call it from the producer thread with nothing
    // submitted before it still queued. With `keep_voices` false (Play's retry) every voice is dropped too, and a
    // pause or resume issued before this call ends: the output is live again from the next render (a pause issued
    // after it still lands).
    void restart_loop(int64_t frame, double music, bool keep_voices = true);

    // Play's pause: from output frame `frame` on the mixer outputs silence and music time stands still (`frame` is
    // the frame that carries the paused music time; one already rendered pauses at the next render). Voices and pending
    // scheduled plays are kept where they are. `resume(frame)` makes `frame` carry the paused music time: music time
    // continues from there, the voices go on from their paused positions and the pending plays keep their distance, all
    // shifted by the paused frames. The music zero moves at once (MusicClock reads `frame_to_music` right after), the
    // audio thread follows at the render that reaches `frame`. Producer thread only; a pause while paused and a resume
    // while running do nothing.
    void pause(int64_t frame);
    void resume(int64_t frame);
    // Ends every looped voice at the next render (Play's end and quit leave nothing looping under the result).
    void stop_looped();

    // Queues a command from the producer thread. A full queue drops it (see `dropped()`).
    void submit(const modes::AudioCmd& cmd);

    // Writes `frames` interleaved stereo frames, advancing `frames_rendered()`.
    void render(float* stereo_out, int32_t frames);

    // The one music time <-> output frame convention (offset and music zero applied): the mixer places commands and
    // the BGM with music_to_frame; MusicClock reads music time back with frame_to_music, its exact inverse.
    int64_t music_to_frame(double t) const;
    double frame_to_music(double frame) const;

    int64_t frames_rendered() const { return rendered_.load(std::memory_order_acquire); }
    int32_t rate() const { return rate_; }
    // Commands lost to a full queue or a full voice table.
    int32_t dropped() const { return dropped_.load(std::memory_order_relaxed); }

    static constexpr size_t kMaxScheduled = 65536;  // PlayScheduled commands waiting to start
    // The command ring holds a whole burst: a level sends every PlayScheduled on its first frame, so it is as big as
    // the scheduled table (power of two). Allocated once in the constructor, never on the audio thread.
    static constexpr size_t kQueueSize = kMaxScheduled;
    static constexpr size_t kMaxVoices = 512;

private:
    struct Voice {
        bool used = false;
        bool looped = false;
        int32_t clip = 0;
        int32_t handle = 0;  // looped voices
        int64_t start = 0;   // output frame of clip sample 0
        int64_t stop = INT64_MAX;  // first output frame without this voice (looped voices; INT64_MAX: never)
    };
    // A PlayScheduled waiting for its start: the distance rule is decided then, in time order, and only then
    // does it take a voice, so a level may schedule every clip up front.
    struct Scheduled {
        double time = 0;  // music time
        double distance = 0;
        int64_t start = 0;  // output frame
        int32_t clip = 0;
        uint64_t seq = 0;  // command order, for equal times
    };

    void apply(const modes::AudioCmd& cmd);
    void do_resume();
    // music_to_frame with the audio thread's zero (the producer's moves at once on resume, this one at the resume frame).
    int64_t audio_to_frame(double t) const { return audio_zero_ + int64_t(round_music((t + audio_offset_) * rate_)); }
    static double round_music(double x) { return x >= 0 ? x + 0.5 : x - 0.5; }
    // Writes (not adds) output frames [from, from + frames) of the live mix into `out`.
    void mix(float* out, int64_t from, int32_t frames);
    void mix_voice(Voice& v, int64_t from, int32_t frames, float* out);
    void start_scheduled(int64_t end);
    void add_voice(int32_t clip, bool looped, int32_t handle, int64_t start);

    int32_t rate_;
    std::vector<Pcm> clips_;
    Pcm bgm_;
    double bgm_offset_ = 0;
    double audio_offset_ = 0;
    std::atomic<float> effect_volume_{1.0f}, bgm_volume_{1.0f};
    std::atomic<int64_t> zero_frame_{0};
    std::atomic<bool> restart_{true};
    std::atomic<bool> loop_restart_{false};  // forget the pending plays, keep the voices
    std::atomic<bool> drop_voices_{false};   // with loop_restart_: drop the voices and leave a pause too
    std::atomic<bool> stop_looped_{false};
    // Pause and resume (producer -> audio thread). A request holds its sequence number (0: none), so a retry's restart
    // can drop the requests issued before it and keep the ones issued after it.
    std::atomic<uint64_t> pause_request_{0}, resume_request_{0};
    std::atomic<int64_t> pause_frame_{0}, resume_frame_{0}, resume_shift_{0};
    std::atomic<uint64_t> restart_seq_{0};  // sequence number of the last restart_loop that drops the voices
    bool producer_paused_ = false;  // producer thread only: pause() was called and not resume()
    uint64_t control_seq_ = 0;      // producer thread only: numbers pause, resume and the dropping restart
    std::atomic<int64_t> rendered_{0};
    std::atomic<int32_t> dropped_{0};

    std::vector<modes::AudioCmd> queue_;  // kQueueSize slots
    std::atomic<size_t> head_{0};  // next slot to read (audio thread)
    std::atomic<size_t> tail_{0};  // next slot to write (producer)

    // Audio-thread state.
    bool paused_ = false;
    uint64_t paused_seq_ = 0;  // the sequence number of the pause in effect
    int64_t audio_zero_ = 0;  // the audio thread's music zero: moves with the resume's shift at the resume frame
    std::array<Voice, kMaxVoices> voices_;
    std::vector<double> last_start_;  // per clip: music time of the last PlayScheduled that played (time order)
    std::vector<Scheduled> scheduled_;  // not yet started from next_scheduled_ on; capacity kMaxScheduled, never grown
    size_t next_scheduled_ = 0;
    bool scheduled_sorted_ = true;
    uint64_t seq_ = 0;
};

}  // namespace sr::audio
