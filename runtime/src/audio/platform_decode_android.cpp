// Android platform decoding (audio/platform_decode.h): MediaExtractor + MediaCodec (NDK, API 21), 16-bit or float PCM
// out, converted to float.
#include "audio/platform_decode.h"

#include <dlfcn.h>
#include <fcntl.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>

namespace sr::audio {

namespace {

constexpr int64_t kTimeoutUs = 10000;
// Consecutive dequeue rounds (each up to 2 x kTimeoutUs) in which the codec takes no input and gives no output
// before the decode is abandoned: about 10 s, far past any real stall.
constexpr int kMaxIdleRounds = 500;
constexpr int32_t kEncodingPcm16 = 2, kEncodingPcmFloat = 4;  // AudioFormat.ENCODING_PCM_16BIT / _FLOAT

struct Fd {
    int fd = -1;
    ~Fd() {
        if (fd >= 0) close(fd);
    }
};
struct ExtractorDeleter {
    void operator()(AMediaExtractor* e) const { AMediaExtractor_delete(e); }
};
struct FormatDeleter {
    void operator()(AMediaFormat* f) const { AMediaFormat_delete(f); }
};
struct CodecDeleter {
    void operator()(AMediaCodec* c) const {
        AMediaCodec_stop(c);
        AMediaCodec_delete(c);
    }
};

int32_t get_int(AMediaFormat* f, const char* key, int32_t fallback) {
    int32_t v = 0;
    return AMediaFormat_getInt32(f, key, &v) ? v : fallback;
}

// MediaCodec hands its buffers back over binder callbacks, which need the process's binder thread pool. An app
// process has one; a plain executable run from the adb shell (sr_tests, srun) does not, and every dequeue then
// times out. Starting the pool is idempotent. libbinder_ndk exists from API 29; on older systems only apps run this.
void ensure_binder_thread_pool() {
    static std::once_flag once;
    std::call_once(once, [] {
        void* lib = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_LOCAL);
        if (lib == nullptr) return;
        if (auto start = reinterpret_cast<void (*)()>(dlsym(lib, "ABinderProcess_startThreadPool"))) start();
    });
}

}  // namespace

std::optional<Pcm> platform_decode(const std::string& path, std::string& error) {
    ensure_binder_thread_pool();
    Fd file;
    file.fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    struct stat st {};
    if (file.fd < 0 || fstat(file.fd, &st) != 0) {
        error = "cannot open " + path;
        return std::nullopt;
    }
    std::unique_ptr<AMediaExtractor, ExtractorDeleter> extractor(AMediaExtractor_new());
    if (!extractor || AMediaExtractor_setDataSourceFd(extractor.get(), file.fd, 0, st.st_size) != AMEDIA_OK) {
        error = "the platform cannot read this file";
        return std::nullopt;
    }
    std::unique_ptr<AMediaFormat, FormatDeleter> format;
    std::string mime;
    for (size_t i = 0, n = AMediaExtractor_getTrackCount(extractor.get()); i < n; ++i) {
        std::unique_ptr<AMediaFormat, FormatDeleter> f(AMediaExtractor_getTrackFormat(extractor.get(), i));
        const char* m = nullptr;
        if (f && AMediaFormat_getString(f.get(), AMEDIAFORMAT_KEY_MIME, &m) && m != nullptr &&
            std::strncmp(m, "audio/", 6) == 0) {
            AMediaExtractor_selectTrack(extractor.get(), i);
            format = std::move(f);
            mime = m;
            break;
        }
    }
    if (!format) {
        error = "no audio track";
        return std::nullopt;
    }
    std::unique_ptr<AMediaCodec, CodecDeleter> codec(AMediaCodec_createDecoderByType(mime.c_str()));
    if (!codec) {
        error = "no platform decoder for " + mime;
        return std::nullopt;
    }
    if (AMediaCodec_configure(codec.get(), format.get(), nullptr, nullptr, 0) != AMEDIA_OK ||
        AMediaCodec_start(codec.get()) != AMEDIA_OK) {
        error = "the platform decoder for " + mime + " did not start";
        return std::nullopt;
    }

    Pcm pcm;
    pcm.rate = get_int(format.get(), AMEDIAFORMAT_KEY_SAMPLE_RATE, 0);
    pcm.channels = get_int(format.get(), AMEDIAFORMAT_KEY_CHANNEL_COUNT, 0);
    int32_t encoding = kEncodingPcm16;
    const int32_t encoder_delay = std::max(0, get_int(format.get(), "encoder-delay", 0));
    const int32_t encoder_padding = std::max(0, get_int(format.get(), "encoder-padding", 0));
    int64_t duration_us = -1;  // the track's length; an MP4 edit list sets it to the playable part (ffmpeg's length)
    if (!AMediaFormat_getInt64(format.get(), AMEDIAFORMAT_KEY_DURATION, &duration_us)) duration_us = -1;
    // Access units queued, and the time step between the first two: the frames per unit.
    int64_t units = 0, first_time = 0, unit_us = 0;

    bool input_done = false, ended = false;
    for (int idle = 0; !ended;) {
        if (idle >= kMaxIdleRounds) {
            error = "the platform decoder for " + mime + " stopped responding";
            return std::nullopt;
        }
        bool progress = false;
        if (!input_done) {
            const ssize_t in = AMediaCodec_dequeueInputBuffer(codec.get(), kTimeoutUs);
            if (in >= 0) {
                progress = true;
                size_t capacity = 0;
                uint8_t* buffer = AMediaCodec_getInputBuffer(codec.get(), size_t(in), &capacity);
                const ssize_t size = buffer ? AMediaExtractor_readSampleData(extractor.get(), buffer, capacity) : -1;
                if (size < 0) {
                    AMediaCodec_queueInputBuffer(codec.get(), size_t(in), 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    input_done = true;
                } else {
                    // An applied edit list dates the priming samples before 0. A negative time passes through as two's
                    // complement and comes back as a negative presentationTimeUs (dropped below).
                    const int64_t time = AMediaExtractor_getSampleTime(extractor.get());
                    if (units == 0) first_time = time;
                    if (units == 1) unit_us = time - first_time;
                    ++units;
                    AMediaCodec_queueInputBuffer(codec.get(), size_t(in), 0, size_t(size), uint64_t(time), 0);
                    AMediaExtractor_advance(extractor.get());
                }
            }
        }
        AMediaCodecBufferInfo info{};
        const ssize_t out = AMediaCodec_dequeueOutputBuffer(codec.get(), &info, kTimeoutUs);
        if (out == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            std::unique_ptr<AMediaFormat, FormatDeleter> f(AMediaCodec_getOutputFormat(codec.get()));
            pcm.rate = get_int(f.get(), AMEDIAFORMAT_KEY_SAMPLE_RATE, pcm.rate);
            pcm.channels = get_int(f.get(), AMEDIAFORMAT_KEY_CHANNEL_COUNT, pcm.channels);
            encoding = get_int(f.get(), "pcm-encoding", kEncodingPcm16);
            idle = 0;
            continue;
        }
        if (out < 0) {
            idle = progress ? 0 : idle + 1;
            continue;
        }
        idle = 0;
        size_t capacity = 0;
        const uint8_t* buffer = AMediaCodec_getOutputBuffer(codec.get(), size_t(out), &capacity);
        if (buffer != nullptr && info.size > 0 && pcm.channels > 0 && pcm.rate > 0) {
            const uint8_t* data = buffer + info.offset;
            const size_t width = encoding == kEncodingPcmFloat ? 4 : 2;
            size_t frames = size_t(info.size) / width / size_t(pcm.channels);
            size_t skip = 0;
            if (info.presentationTimeUs < 0)  // drop the part before time 0
                skip = std::min<size_t>(
                    frames, size_t(std::llround(double(-info.presentationTimeUs) * pcm.rate / 1e6)));
            const size_t first = skip * size_t(pcm.channels), count = frames * size_t(pcm.channels);
            for (size_t k = first; k < count; ++k) {
                float v;
                if (width == 4) {
                    std::memcpy(&v, data + k * 4, 4);
                } else {
                    int16_t s;
                    std::memcpy(&s, data + k * 2, 2);
                    v = float(s) / 32768.0f;
                }
                pcm.samples.push_back(v);
            }
        }
        AMediaCodec_releaseOutputBuffer(codec.get(), size_t(out), false);
        ended = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
    }
    if (pcm.channels < 1 || pcm.channels > 2 || pcm.rate <= 0) {
        error = "the platform decoder gave " + std::to_string(pcm.channels) + " channels at " +
                std::to_string(pcm.rate) + " Hz";
        return std::nullopt;
    }
    // Encoder delay and padding as ffmpeg drops them, less what the decoder (or the negative times) already dropped.
    // Without two input times the full input length is unknown, and the decoder is taken to have dropped nothing.
    const size_t ch = size_t(pcm.channels);
    const int64_t kept = int64_t(pcm.samples.size() / ch);
    const int64_t unit_frames = unit_us > 0 ? std::llround(double(unit_us) * pcm.rate / 1e6) : 0;
    const Trim trim = decoder_trim(unit_frames > 0 ? units * unit_frames : kept, kept, encoder_delay, encoder_padding);
    const size_t start = std::min(pcm.samples.size(), size_t(trim.start) * ch);
    pcm.samples.erase(pcm.samples.begin(), pcm.samples.begin() + std::ptrdiff_t(start));
    pcm.samples.resize(pcm.samples.size() - std::min(pcm.samples.size(), size_t(trim.end) * ch));
    if (duration_us > 0) {
        const size_t frames = size_t(std::llround(double(duration_us) * pcm.rate / 1e6));
        if (pcm.samples.size() > frames * ch) pcm.samples.resize(frames * ch);
    }
    return pcm;
}

}  // namespace sr::audio
