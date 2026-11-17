#include "audio/decode.h"

#include <algorithm>
#include <cstring>
#include <string>

#include "data/load_error.h"

#define MINIMP3_ONLY_MP3
#define MINIMP3_NO_SIMD
#define MINIMP3_FLOAT_OUTPUT
#include "minimp3.h"

#define STB_VORBIS_NO_STDIO
#define STB_VORBIS_NO_PUSHDATA_API
#define STB_VORBIS_HEADER_ONLY
#include "stb_vorbis.c"

namespace sr::audio {

namespace {

constexpr uint64_t kDecoderDelay = 529;  // the 528 + 1 samples a Layer III decoder adds, which FFmpeg also drops

// No gain in decoding: BGM and clips play at the decoded level (MP3 matches FFmpeg's decode to a residual of 1e-4).
constexpr float kDecodeGain = 1.0f;

Pcm with_gain(Pcm pcm) {
    if (kDecodeGain != 1.0f)
        for (float& s : pcm.samples) s *= kDecodeGain;
    return pcm;
}

Pcm decode_mp3(std::span<const uint8_t> bytes);

uint32_t be32(const uint8_t* p) { return uint32_t(p[0]) << 24 | uint32_t(p[1]) << 16 | uint32_t(p[2]) << 8 | p[3]; }
uint32_t be24(const uint8_t* p) { return uint32_t(p[0]) << 16 | uint32_t(p[1]) << 8 | p[2]; }

struct Frame {
    uint32_t length = 0, samples = 0, rate = 0;
    int32_t channels = 0;
    uint32_t side_info = 0;  // bytes between the header and a Xing tag
};

// A Layer III frame header at p (4 bytes), or a zero-length Frame.
Frame parse_header(const uint8_t* p) {
    Frame f;
    if (p[0] != 0xFF || (p[1] & 0xE0) != 0xE0) return f;
    const int version = (p[1] >> 3) & 3;  // 0: MPEG-2.5, 1: reserved, 2: MPEG-2, 3: MPEG-1
    const int layer = (p[1] >> 1) & 3;    // 1: Layer III
    const int bitrate_index = p[2] >> 4, rate_index = (p[2] >> 2) & 3, padding = (p[2] >> 1) & 1;
    const bool mono = (p[3] >> 6) == 3;
    if (version == 1 || layer != 1 || bitrate_index == 0 || bitrate_index == 15 || rate_index == 3) return f;
    static const uint32_t mpeg1_kbps[15] = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
    static const uint32_t mpeg2_kbps[15] = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
    static const uint32_t mpeg1_rates[3] = {44100, 48000, 32000};
    const bool mpeg1 = version == 3;
    const uint32_t bitrate = (mpeg1 ? mpeg1_kbps : mpeg2_kbps)[bitrate_index] * 1000;
    f.rate = mpeg1_rates[rate_index] >> (mpeg1 ? 0 : version == 2 ? 1 : 2);
    f.samples = mpeg1 ? 1152 : 576;
    f.length = (mpeg1 ? 144 : 72) * bitrate / f.rate + uint32_t(padding);
    f.channels = mono ? 1 : 2;
    f.side_info = mpeg1 ? (mono ? 17 : 32) : (mono ? 9 : 17);
    return f;
}

size_t skip_id3v2(std::span<const uint8_t> b) {
    if (b.size() < 10 || std::memcmp(b.data(), "ID3", 3) != 0) return 0;
    const size_t size = size_t(b[6] & 0x7F) << 21 | size_t(b[7] & 0x7F) << 14 | size_t(b[8] & 0x7F) << 7 |
                        size_t(b[9] & 0x7F);
    return 10 + size + ((b[5] & 0x10) != 0 ? 10 : 0);
}

}  // namespace

std::string container_format(std::span<const uint8_t> b) {
    const auto starts = [&](size_t at, const char* magic) {
        const size_t n = std::strlen(magic);
        return b.size() >= at + n && std::memcmp(b.data() + at, magic, n) == 0;
    };
    if (starts(0, "OggS")) return "Ogg";
    if (starts(0, "RIFF") && starts(8, "WAVE")) return "WAV";
    if (starts(0, "fLaC")) return "FLAC";
    if (starts(4, "ftyp")) return "MP4";
    if (starts(0, "\x1A\x45\xDF\xA3")) return "Matroska/WebM";
    return "";
}

std::optional<Mp3Layout> scan_mp3(std::span<const uint8_t> b) {
    Mp3Layout out;
    size_t at = skip_id3v2(b);
    uint64_t frames = 0;           // audio frames found
    uint64_t tag_frames = 0;       // frame count promised by a Xing/Info/VBRI tag
    uint64_t start_pad = 0, end_pad = 0;
    bool first = true, lame = false;
    uint32_t spf = 0;
    while (at + 4 <= b.size()) {
        const Frame f = parse_header(b.data() + at);
        if (f.length < 4 || at + f.length > b.size() || (out.rate != 0 && int32_t(f.rate) != out.rate)) {
            ++at;  // not a frame here (or a truncated last frame): resync
            continue;
        }
        if (first) {
            first = false;
            out.rate = int32_t(f.rate);
            out.channels = f.channels;
            spf = f.samples;
            const uint8_t* frame = b.data() + at;
            const size_t tag = 4 + f.side_info;  // within the frame
            if (tag + 12 <= f.length && (std::memcmp(frame + tag, "Xing", 4) == 0 || std::memcmp(frame + tag, "Info", 4) == 0)) {
                const uint32_t flags = be32(frame + tag + 4);
                size_t off = tag + 8;
                if ((flags & 1) != 0 && off + 4 <= f.length) tag_frames = be32(frame + off), off += 4;
                if ((flags & 2) != 0) off += 4;
                if ((flags & 4) != 0) off += 100;
                if ((flags & 8) != 0) off += 4;
                // LAME tag: 9-byte encoder string, then the delay and padding as two 12-bit fields at offset 21.
                if (off + 24 <= f.length &&
                    (std::memcmp(frame + off, "LAME", 4) == 0 || std::memcmp(frame + off, "Lavf", 4) == 0 ||
                     std::memcmp(frame + off, "Lavc", 4) == 0)) {
                    const uint32_t v = be24(frame + off + 21);
                    start_pad = v >> 12, end_pad = v & 4095;
                    lame = true;
                }
                at += f.length;  // a tag frame holds no audio
                continue;
            }
            const size_t vbri = 4 + 32;
            if (vbri + 18 <= f.length && std::memcmp(frame + vbri, "VBRI", 4) == 0) {
                tag_frames = be32(frame + vbri + 14);
                at += f.length;
                continue;
            }
        }
        ++frames;
        if (frames == 1) out.first_frame = at;
        at += f.length;
    }
    if (frames == 0) return std::nullopt;
    const uint64_t decoded = frames * spf;  // sample frames the decoder will output, before gapless trimming
    if (lame) {
        out.skip = start_pad + kDecoderDelay;
        const uint64_t total = tag_frames != 0 ? tag_frames * spf : decoded;
        // FFmpeg: the stream runs from start_pad + 529 to total - end_pad + 529 (decoder output coordinates).
        const uint64_t end = total + kDecoderDelay > end_pad ? total - end_pad + kDecoderDelay : 0;
        out.length = end > out.skip ? end - out.skip : 0;
    } else {
        out.length = tag_frames != 0 ? tag_frames * spf : decoded;
    }
    return out;
}

namespace {

uint32_t le32(const uint8_t* p) { return uint32_t(p[3]) << 24 | uint32_t(p[2]) << 16 | uint32_t(p[1]) << 8 | p[0]; }
uint32_t le16(const uint8_t* p) { return uint32_t(p[1]) << 8 | p[0]; }

// RIFF WAVE with PCM (8/16/24/32-bit) or float32 samples, mono or stereo.
// Returns false when `b` is not such a file.
bool decode_wav(std::span<const uint8_t> b, Pcm& pcm) {
    if (b.size() < 12 || std::memcmp(b.data(), "RIFF", 4) != 0 || std::memcmp(b.data() + 8, "WAVE", 4) != 0)
        return false;
    uint32_t format = 0, channels = 0, rate = 0, bits = 0;
    for (size_t at = 12; at + 8 <= b.size();) {
        const uint8_t* chunk = b.data() + at;
        const size_t size = le32(chunk + 4), body = at + 8;
        if (std::memcmp(chunk, "fmt ", 4) == 0 && size >= 16 && body + 16 <= b.size()) {
            format = le16(chunk + 8), channels = le16(chunk + 10), rate = le32(chunk + 12), bits = le16(chunk + 22);
            if (format == 0xFFFE && size >= 26 && body + 26 <= b.size()) format = le16(chunk + 32);  // extensible
        } else if (std::memcmp(chunk, "data", 4) == 0) {
            const bool is_float = format == 3 && bits == 32;
            if (!((format == 1 && (bits == 8 || bits == 16 || bits == 24 || bits == 32)) || is_float) ||
                channels < 1 || channels > 2 || rate == 0)
                return false;
            const size_t bytes_per = bits / 8, avail = std::min(size, b.size() - body);
            const size_t count = avail / bytes_per / channels * channels;
            pcm.rate = int32_t(rate), pcm.channels = int32_t(channels);
            pcm.samples.resize(count);
            const uint8_t* p = b.data() + body;
            for (size_t i = 0; i < count; ++i, p += bytes_per) {
                float v;
                if (is_float) {
                    const uint32_t u = le32(p);
                    std::memcpy(&v, &u, 4);
                } else if (bits == 8) {
                    v = (float(p[0]) - 128.0f) / 128.0f;
                } else if (bits == 16) {
                    v = float(int16_t(le16(p))) / 32768.0f;
                } else if (bits == 24) {
                    v = float(int32_t(uint32_t(p[0]) << 8 | uint32_t(p[1]) << 16 | uint32_t(p[2]) << 24) >> 8) / 8388608.0f;
                } else {
                    v = float(int32_t(le32(p))) / 2147483648.0f;
                }
                pcm.samples[i] = v;
            }
            return true;
        }
        at = body + size + (size & 1);
    }
    return false;
}

// Ogg Vorbis, all of it (stb_vorbis honours the last page's granule position, as ffmpeg does). Returns false when
// `b` is not an Ogg file; throws when it is one stb_vorbis cannot decode (Opus, other codecs, corrupt data).
bool decode_vorbis(std::span<const uint8_t> b, Pcm& pcm) {
    if (b.size() < 4 || std::memcmp(b.data(), "OggS", 4) != 0) return false;
    int error = 0;
    stb_vorbis* v = stb_vorbis_open_memory(b.data(), int(std::min<size_t>(b.size(), INT32_MAX)), &error, nullptr);
    if (v == nullptr)
        throw data::LoadError("audio: unsupported Ogg file (not Vorbis, or corrupt; stb_vorbis error " +
                              std::to_string(error) + ")");
    const stb_vorbis_info info = stb_vorbis_get_info(v);
    if (info.channels < 1 || info.channels > 2 || info.sample_rate == 0) {
        stb_vorbis_close(v);
        throw data::LoadError("audio: Ogg Vorbis with " + std::to_string(info.channels) + " channels at " +
                              std::to_string(info.sample_rate) + " Hz is not supported");
    }
    pcm.rate = int32_t(info.sample_rate);
    pcm.channels = info.channels;
    pcm.samples.clear();
    float buffer[4096];
    for (;;) {
        const int frames = stb_vorbis_get_samples_float_interleaved(v, info.channels, buffer, int(std::size(buffer)));
        if (frames <= 0) break;
        pcm.samples.insert(pcm.samples.end(), buffer, buffer + size_t(frames) * size_t(info.channels));
    }
    stb_vorbis_close(v);
    return true;
}

Pcm decode_mp3(std::span<const uint8_t> bytes) {
    const auto layout = scan_mp3(bytes);
    if (!layout) throw data::LoadError("audio: not an MP3 file (no Layer III frame found)");
    Pcm pcm;
    pcm.rate = layout->rate;
    pcm.channels = layout->channels;
    const size_t ch = size_t(pcm.channels);
    std::vector<float> all;  // every decoded sample, interleaved
    all.reserve(size_t(layout->skip + layout->length) * ch + MINIMP3_MAX_SAMPLES_PER_FRAME);
    mp3dec_t dec;
    mp3dec_init(&dec);
    float frame_pcm[MINIMP3_MAX_SAMPLES_PER_FRAME];
    size_t at = layout->first_frame;
    while (at < bytes.size()) {
        mp3dec_frame_info_t info;
        const int n = mp3dec_decode_frame(&dec, bytes.data() + at, int(bytes.size() - at), frame_pcm, &info);
        if (info.frame_bytes <= 0) break;
        at += size_t(info.frame_bytes);
        if (n <= 0 || info.channels != pcm.channels || info.hz != pcm.rate) continue;
        all.insert(all.end(), frame_pcm, frame_pcm + size_t(n) * ch);
    }
    const size_t from = std::min(all.size(), size_t(layout->skip) * ch);
    const size_t to = std::min(all.size(), from + size_t(layout->length) * ch);
    pcm.samples.assign(all.begin() + std::ptrdiff_t(from), all.begin() + std::ptrdiff_t(to));
    return pcm;
}

}  // namespace

Pcm decode_audio(std::span<const uint8_t> bytes) {
    Pcm pcm;
    if (decode_wav(bytes, pcm)) return with_gain(std::move(pcm));
    if (decode_vorbis(bytes, pcm)) return with_gain(std::move(pcm));
    // A recognised container is never scanned for MP3 frames: compressed data can hold bytes that look like Layer
    // III headers (an M4A BGM once decoded as seconds of noise).
    const std::string other = container_format(bytes);
    if (!other.empty())
        throw data::LoadError("audio: unsupported format " + other + ", only MP3, WAV and Ogg Vorbis are supported");
    return with_gain(decode_mp3(bytes));
}

}  // namespace sr::audio
