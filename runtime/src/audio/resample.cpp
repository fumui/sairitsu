#include "audio/resample.h"

#include <algorithm>
#include <cstdint>
#include <memory>
#include <string>

#include "data/load_error.h"
#include "speex_resampler.h"

namespace sr::audio {

namespace {

struct ResamplerDeleter {
    void operator()(SpeexResamplerState* st) const { speex_resampler_destroy(st); }
};

std::vector<float> to_stereo(const Pcm& in) {
    std::vector<float> out(in.frames() * 2);
    for (size_t i = 0; i < in.frames(); ++i) {
        const float l = in.samples[i * size_t(in.channels)];
        out[2 * i] = l;
        out[2 * i + 1] = in.channels > 1 ? in.samples[i * size_t(in.channels) + 1] : l;
    }
    return out;
}

}  // namespace

Pcm resample(const Pcm& in, int32_t out_rate) {
    if (in.rate <= 0 || out_rate <= 0 || in.channels < 1 || in.channels > 2)
        throw data::LoadError("audio: cannot resample " + std::to_string(in.channels) + " channels at " +
                              std::to_string(in.rate) + " Hz to " + std::to_string(out_rate) + " Hz");
    Pcm out;
    out.rate = out_rate;
    out.channels = 2;
    std::vector<float> stereo = to_stereo(in);
    if (in.rate == out_rate) {
        out.samples = std::move(stereo);
        return out;
    }
    int err = 0;
    std::unique_ptr<SpeexResamplerState, ResamplerDeleter> st(
        speex_resampler_init(2, unsigned(in.rate), unsigned(out_rate), 10 /* Quality */, &err));
    if (!st || err != 0) throw data::LoadError("audio: the resampler failed to start");
    speex_resampler_skip_zeros(st.get());  // drop the filter's input latency: output sample 0 is input sample 0

    const uint64_t in_frames = stereo.size() / 2;
    const uint64_t want = (in_frames * uint64_t(out_rate) + uint64_t(in.rate) / 2) / uint64_t(in.rate);
    out.samples.resize(size_t(want) * 2);
    size_t produced = 0;  // output frames
    size_t consumed = 0;  // input frames
    const std::vector<float> zeros(4096 * 2, 0.0f);  // fed after the input to flush the filter tail
    while (produced < want) {
        const bool tail = consumed >= in_frames;
        const float* src = tail ? zeros.data() : stereo.data() + consumed * 2;
        spx_uint32_t in_len = tail ? unsigned(zeros.size() / 2) : unsigned(std::min<uint64_t>(in_frames - consumed, 65536));
        spx_uint32_t out_len = unsigned(std::min<size_t>(want - produced, 65536));
        const int rc = speex_resampler_process_interleaved_float(st.get(), src, &in_len, out.samples.data() + produced * 2, &out_len);
        if (rc != 0) throw data::LoadError("audio: the resampler failed");
        if (!tail) consumed += in_len;
        produced += out_len;
        if (in_len == 0 && out_len == 0) break;
    }
    out.samples.resize(produced * 2);
    return out;
}

}  // namespace sr::audio
