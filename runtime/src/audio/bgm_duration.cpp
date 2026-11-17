#include "audio/bgm_duration.h"

#include <cstring>
#include <string>

#include "audio/decode.h"

namespace sr::audio {

namespace {

uint32_t le32(const uint8_t* p) { return uint32_t(p[3]) << 24 | uint32_t(p[2]) << 16 | uint32_t(p[1]) << 8 | p[0]; }

std::optional<double> wav_duration(std::span<const uint8_t> b) {
    uint32_t byte_rate = 0;
    for (size_t at = 12; at + 8 <= b.size();) {
        const uint32_t size = le32(b.data() + at + 4);
        if (std::memcmp(b.data() + at, "fmt ", 4) == 0 && size >= 16 && at + 8 + 16 <= b.size())
            byte_rate = le32(b.data() + at + 8 + 8);
        if (std::memcmp(b.data() + at, "data", 4) == 0) {
            if (byte_rate == 0) return std::nullopt;
            return double(size) / byte_rate;
        }
        at += 8 + size_t(size) + (size & 1);
    }
    return std::nullopt;
}

// Ogg Vorbis: the last page's granule position (samples, ignoring pages that end no packet, granule -1) over the
// identification header's sample rate.
std::optional<double> ogg_duration(std::span<const uint8_t> b) {
    static const uint8_t kIdentification[] = {0x01, 'v', 'o', 'r', 'b', 'i', 's'};
    uint32_t rate = 0;
    for (size_t at = 0; at + 16 <= b.size() && at < 4096; ++at)
        if (std::memcmp(b.data() + at, kIdentification, sizeof kIdentification) == 0) {
            rate = le32(b.data() + at + 12);  // after the type, "vorbis", version (4) and channels (1)
            break;
        }
    if (rate == 0) return std::nullopt;
    for (size_t at = b.size() >= 27 ? b.size() - 27 : 0;; --at) {
        if (b.size() >= 27 && std::memcmp(b.data() + at, "OggS", 4) == 0 && b[at + 4] == 0) {
            uint64_t granule = 0;
            for (int i = 7; i >= 0; --i) granule = granule << 8 | b[at + 6 + size_t(i)];
            if (granule != ~uint64_t(0)) return double(granule) / rate;
        }
        if (at == 0) return std::nullopt;
    }
}

}  // namespace

std::optional<double> bgm_duration(std::span<const uint8_t> bytes) {
    const std::string container = container_format(bytes);
    if (container == "WAV") return wav_duration(bytes);
    if (container == "Ogg") return ogg_duration(bytes);
    // Any other recognised container has no header-only length here (the decoded length stands in), and is never
    // scanned for MP3 frames: compressed data can hold bytes that look like Layer III headers.
    if (!container.empty()) return std::nullopt;
    // MP3: the decoder's gapless length (decode.h), computed from the headers.
    const auto layout = scan_mp3(bytes);
    if (!layout) return std::nullopt;
    return double(layout->length) / layout->rate;
}

std::optional<double> bgm_length(std::span<const uint8_t> bytes, const Pcm& pcm) {
    if (const auto from_headers = bgm_duration(bytes)) return from_headers;
    if (pcm.rate > 0 && pcm.frames() > 0) return double(pcm.frames()) / pcm.rate;
    return std::nullopt;
}

}  // namespace sr::audio
