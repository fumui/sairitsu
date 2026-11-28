#include "audio/mixer.h"

#include <algorithm>
#include <limits>

#include "audio/resample.h"

namespace sr::audio {

namespace {

constexpr double kNever = -std::numeric_limits<double>::infinity();
static_assert(std::atomic<uint64_t>::is_always_lock_free, "the audio thread must not lock");

int64_t round_to_frame(double x) { return int64_t(x >= 0 ? x + 0.5 : x - 0.5); }  // half away from zero, no libm

}  // namespace

Pcm effect_clip(const Pcm& decoded, int32_t rate) {
    Pcm out = resample(decoded, rate);
    if (decoded.channels == 1)
        for (float& s : out.samples) s *= 0.70710678f;
    return out;
}

Mixer::Mixer(int32_t rate) : rate_(rate), queue_(kQueueSize) { scheduled_.reserve(kMaxScheduled); }

void Mixer::set_clips(std::vector<Pcm> clips) {
    clips_.clear();
    clips_.reserve(clips.size());
    for (Pcm& clip : clips) clips_.push_back(resample(clip, rate_));
    last_start_.assign(clips_.size(), kNever);
}

void Mixer::set_bgm(Pcm bgm, double bgm_offset) {
    bgm_ = resample(bgm, rate_);
    bgm_offset_ = bgm_offset;
}

void Mixer::set_volumes(float effect, float bgm) {
    effect_volume_.store(effect, std::memory_order_relaxed);
    bgm_volume_.store(bgm, std::memory_order_relaxed);
}

void Mixer::set_audio_offset(double seconds) { audio_offset_ = seconds; }

void Mixer::start(int64_t frame_at_music_zero) {
    zero_frame_.store(frame_at_music_zero, std::memory_order_relaxed);
    restart_.store(true, std::memory_order_release);
}

void Mixer::restart_loop(int64_t frame, double music, bool keep_voices) {
    zero_frame_.store(frame - round_to_frame((music + audio_offset_) * rate_), std::memory_order_relaxed);
    drop_voices_.store(!keep_voices, std::memory_order_relaxed);
    if (!keep_voices) {
        producer_paused_ = false;
        restart_seq_.store(++control_seq_, std::memory_order_relaxed);
    }
    loop_restart_.store(true, std::memory_order_release);
}

void Mixer::pause(int64_t frame) {
    if (producer_paused_) return;
    producer_paused_ = true;
    pause_frame_.store(frame, std::memory_order_relaxed);
    pause_request_.store(++control_seq_, std::memory_order_release);
}

void Mixer::resume(int64_t frame) {
    if (!producer_paused_) return;
    producer_paused_ = false;
    const int64_t shift = frame - pause_frame_.load(std::memory_order_relaxed);
    zero_frame_.store(zero_frame_.load(std::memory_order_relaxed) + shift, std::memory_order_relaxed);
    resume_shift_.store(shift, std::memory_order_relaxed);
    resume_frame_.store(frame, std::memory_order_relaxed);
    resume_request_.store(++control_seq_, std::memory_order_release);
}

void Mixer::stop_looped() { stop_looped_.store(true, std::memory_order_release); }

void Mixer::submit(const modes::AudioCmd& cmd) {
    const size_t tail = tail_.load(std::memory_order_relaxed);
    if (tail - head_.load(std::memory_order_acquire) >= kQueueSize) {
        dropped_.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    queue_[tail & (kQueueSize - 1)] = cmd;
    tail_.store(tail + 1, std::memory_order_release);
}

// The audio offset's sign. A positive offset moves audio later; flip the sign here, in both functions, to
// change that. The offset moves audio against entity time (wiki:
// PlayScheduled "accounts for audio offset to sync up with BGM timeline"; Next RUSH's watchData appears not to read
// Runtime Environment index 2, so MusicClock's music time is assumed to be entity time and to share this convention:
// assumed from reading the data, not observed by re-rendering.
int64_t Mixer::music_to_frame(double t) const {
    return zero_frame_.load(std::memory_order_relaxed) + round_to_frame((t + audio_offset_) * rate_);
}

double Mixer::frame_to_music(double frame) const {
    return (frame - double(zero_frame_.load(std::memory_order_relaxed))) / rate_ - audio_offset_;
}

void Mixer::apply(const modes::AudioCmd& cmd) {
    using modes::AudioCmd;
    // Play, PlayLooped and StopLooped (Play mode): they take effect at the next render(), whose first output frame is
    // `rendered_` (apply runs at the head of render, before it is advanced).
    int64_t now = rendered_.load(std::memory_order_relaxed);
    // While a resume is pending (its frame is usually a little ahead of `rendered_`, so the first frames stepped after
    // it are drained while still paused), they start at the resume frame instead: placed one shift early, because
    // do_resume() moves them by the shift. The distance rule's music time below then reads the new timeline.
    if ((paused_ || pause_request_.load(std::memory_order_acquire) != 0) &&
        resume_request_.load(std::memory_order_acquire) != 0)
        now = std::max(now, resume_frame_.load(std::memory_order_relaxed)) -
              resume_shift_.load(std::memory_order_relaxed);
    if (cmd.op == AudioCmd::StopLooped) {
        const int32_t handle = int32_t(cmd.handle);
        for (Voice& v : voices_)
            if (v.used && v.looped && v.handle == handle) v.stop = std::min(v.stop, now);
        return;
    }
    if (cmd.op == AudioCmd::StopLoopedScheduled) {
        const int32_t handle = int32_t(cmd.handle);
        const int64_t stop = audio_to_frame(double(cmd.time));
        for (Voice& v : voices_)
            if (v.used && v.looped && v.handle == handle) v.stop = std::min(v.stop, stop);
        return;
    }
    if (cmd.clip < 0 || size_t(cmd.clip) >= clips_.size() || clips_[size_t(cmd.clip)].samples.empty()) return;
    if (cmd.op == AudioCmd::PlayLoopedScheduled) {
        add_voice(cmd.clip, true, int32_t(cmd.handle), audio_to_frame(double(cmd.time)));
        return;
    }
    if (cmd.op == AudioCmd::PlayLooped) {
        add_voice(cmd.clip, true, int32_t(cmd.handle), now);
        return;
    }
    if (cmd.op == AudioCmd::Play) {  // the distance rule of PlayScheduled, at the music time of this render
        if (scheduled_.size() == scheduled_.capacity()) {
            dropped_.fetch_add(1, std::memory_order_relaxed);
            return;
        }
        const double music = (double(now) - double(audio_zero_)) / rate_ - audio_offset_;
        scheduled_.push_back(Scheduled{music, double(cmd.distance), now, cmd.clip, seq_++});
        scheduled_sorted_ = false;
        return;
    }
    if (scheduled_.size() == scheduled_.capacity()) {  // never reallocate on the audio thread
        dropped_.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    const Scheduled s{double(cmd.time), double(cmd.distance), audio_to_frame(double(cmd.time)), cmd.clip, seq_++};
    scheduled_.push_back(s);  // sorted by start_scheduled
    scheduled_sorted_ = false;
}

void Mixer::add_voice(int32_t clip, bool looped, int32_t handle, int64_t start) {
    for (Voice& v : voices_) {
        if (v.used) continue;
        v.used = true;
        v.looped = looped;
        v.clip = clip;
        v.handle = handle;
        v.start = start;
        v.stop = INT64_MAX;
        return;
    }
    dropped_.fetch_add(1, std::memory_order_relaxed);
}

// The distance rule of PlayScheduled: plays of a clip are
// judged in time order (command order for equal times), whatever order the commands came in; a play is dropped when
// it starts at most `distance` seconds after the clip's last play that was kept (the edge is dropped), distance 0
// never drops, and a dropped play doesn't count. So each play is decided when its start is rendered, and takes a
// voice only then.
void Mixer::start_scheduled(int64_t end) {
    if (!scheduled_sorted_) {  // in place, no allocation
        std::sort(scheduled_.begin() + std::ptrdiff_t(next_scheduled_), scheduled_.end(),
                  [](const Scheduled& a, const Scheduled& b) {
                      return a.time < b.time || (a.time == b.time && a.seq < b.seq);
                  });
        scheduled_sorted_ = true;
    }
    while (next_scheduled_ < scheduled_.size() && scheduled_[next_scheduled_].start < end) {
        const Scheduled& s = scheduled_[next_scheduled_++];
        double& last = last_start_[size_t(s.clip)];
        if (s.distance > 0 && s.time - last <= s.distance) continue;
        last = s.time;
        add_voice(s.clip, false, 0, s.start);
    }
    if (next_scheduled_ == scheduled_.size()) {  // keeps the capacity
        scheduled_.clear();
        next_scheduled_ = 0;
    }
}

// Adds voice `v` to out[0 .. frames) for output frames [from, from + frames), and frees it once it has ended.
void Mixer::mix_voice(Voice& v, int64_t from, int32_t frames, float* out) {
    const Pcm& clip = clips_[size_t(v.clip)];
    const int64_t length = int64_t(clip.frames());
    const int64_t end = v.looped ? v.stop : std::min(v.stop, v.start + length);
    const int64_t first = std::max(from, v.start), last = std::min(from + frames, end);
    for (int64_t n = first; n < last; ++n) {
        const int64_t i = (n - v.start) % length;  // looped voices wrap
        float* o = out + (n - from) * 2;
        o[0] += clip.samples[size_t(i) * 2];
        o[1] += clip.samples[size_t(i) * 2 + 1];
    }
    if (end <= from + frames) v.used = false;
}

// The resume takes effect on the audio thread: voices and pending plays move later by the paused frames and the audio
// thread's own music zero follows (the producer's zero moved in resume()). Everything the audio thread placed before
// this point used the old zero, so one shift puts all of it on the new timeline, whenever it was submitted.
void Mixer::do_resume() {
    const int64_t shift = resume_shift_.load(std::memory_order_relaxed);
    for (Voice& v : voices_) {
        if (!v.used) continue;
        v.start += shift;
        if (v.stop != INT64_MAX) v.stop += shift;
    }
    for (size_t i = next_scheduled_; i < scheduled_.size(); ++i) scheduled_[i].start += shift;
    audio_zero_ += shift;
    paused_ = false;
    resume_request_.store(0, std::memory_order_relaxed);
}

void Mixer::render(float* out, int32_t frames) {
    if (restart_.exchange(false, std::memory_order_acquire)) {
        for (Voice& v : voices_) v.used = false;
        std::fill(last_start_.begin(), last_start_.end(), kNever);
        scheduled_.clear();
        next_scheduled_ = 0;
        paused_ = false;
        audio_zero_ = zero_frame_.load(std::memory_order_relaxed);
    } else if (loop_restart_.exchange(false, std::memory_order_acquire)) {
        std::fill(last_start_.begin(), last_start_.end(), kNever);
        scheduled_.clear();
        next_scheduled_ = 0;
        audio_zero_ = zero_frame_.load(std::memory_order_relaxed);
        if (drop_voices_.exchange(false, std::memory_order_relaxed)) {
            // Play's retry: nothing of the old run survives. A pause or resume the producer issued after the retry
            // (a higher sequence number) is the new run's, and stays; the compare-exchange never clears a newer one.
            const uint64_t since = restart_seq_.load(std::memory_order_relaxed);
            for (Voice& v : voices_) v.used = false;
            if (paused_ && paused_seq_ < since) paused_ = false;
            uint64_t p = pause_request_.load(std::memory_order_acquire);
            if (p != 0 && p < since) pause_request_.compare_exchange_strong(p, 0, std::memory_order_relaxed);
            uint64_t r = resume_request_.load(std::memory_order_acquire);
            if (r != 0 && r < since) resume_request_.compare_exchange_strong(r, 0, std::memory_order_relaxed);
        }
    }
    const int64_t from = rendered_.load(std::memory_order_relaxed);
    // A resume whose frame has been reached takes effect before the drain, so what the producer submitted after it is
    // placed by the new zero. A resume (or a pause and resume) inside this block takes effect at its frame, below.
    if (paused_ && resume_request_.load(std::memory_order_acquire) != 0 &&
        resume_frame_.load(std::memory_order_relaxed) <= from)
        do_resume();
    // Drain the queue.
    size_t head = head_.load(std::memory_order_relaxed);
    const size_t tail = tail_.load(std::memory_order_acquire);
    for (; head != tail; ++head) apply(queue_[head & (kQueueSize - 1)]);
    head_.store(head, std::memory_order_release);

    if (stop_looped_.exchange(false, std::memory_order_acquire))
        for (Voice& v : voices_)
            if (v.used && v.looped) v.stop = std::min(v.stop, from);

    // The block in segments: live, or silent while paused. A pause or resume lands on its exact frame (one already past
    // lands at the segment's start).
    int32_t pos = 0;
    while (pos < frames) {
        const int64_t at = from + pos;
        int32_t n = frames - pos;
        if (!paused_) {
            uint64_t request = pause_request_.load(std::memory_order_acquire);
            if (request != 0) {
                const int64_t p = pause_frame_.load(std::memory_order_relaxed);
                if (p <= at) {
                    paused_ = true;
                    paused_seq_ = request;
                    pause_request_.compare_exchange_strong(request, 0, std::memory_order_relaxed);
                    continue;
                }
                if (p < from + frames) n = int32_t(p - at);
            }
            mix(out + size_t(pos) * 2, at, n);
        } else {
            if (resume_request_.load(std::memory_order_acquire) != 0) {
                const int64_t r = resume_frame_.load(std::memory_order_relaxed);
                if (r <= at) {
                    do_resume();
                    continue;
                }
                if (r < from + frames) n = int32_t(r - at);
            }
            std::fill(out + size_t(pos) * 2, out + size_t(pos + n) * 2, 0.0f);
        }
        pos += n;
    }
    rendered_.store(from + frames, std::memory_order_release);
}

void Mixer::mix(float* out, int64_t from, int32_t frames) {
    const float effect = effect_volume_.load(std::memory_order_relaxed);
    const float bgm_gain = bgm_volume_.load(std::memory_order_relaxed);
    std::fill(out, out + size_t(frames) * 2, 0.0f);

    start_scheduled(from + frames);
    // Effects are mixed at unity first, then scaled, so the volume multiplies the sum.
    for (Voice& v : voices_)
        if (v.used) mix_voice(v, from, frames, out);
    for (int32_t i = 0; i < frames * 2; ++i) out[i] *= effect;

    // BGM sample 0 plays at music time -bgm_offset: bgmOffset is the BGM time at music time 0 (pre-roll and offset
    // sign share music_to_frame).
    const int64_t bgm_start = audio_to_frame(-bgm_offset_);
    const int64_t bgm_length = int64_t(bgm_.frames());
    const int64_t first = std::max(from, bgm_start), last = std::min(from + frames, bgm_start + bgm_length);
    for (int64_t n = first; n < last; ++n) {
        const size_t i = size_t(n - bgm_start) * 2;
        float* o = out + (n - from) * 2;
        o[0] += bgm_.samples[i] * bgm_gain;
        o[1] += bgm_.samples[i + 1] * bgm_gain;
    }
}

}  // namespace sr::audio
