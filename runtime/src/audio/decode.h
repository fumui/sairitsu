#pragma once

#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <vector>

namespace sr::audio {

// Decoded audio: interleaved float32 samples in [-1, 1].
struct Pcm {
    int32_t rate = 0, channels = 0;
    std::vector<float> samples;  // frames * channels

    size_t frames() const { return channels > 0 ? samples.size() / size_t(channels) : 0; }
};

// Decodes an MP3 (MPEG-1/2/2.5 Layer III) file, gapless the way FFmpeg does: the Xing/Info frame is not audio, and
// when it carries a LAME tag the encoder delay (plus the decoder's 529 samples) is dropped from the start and the
// padding from the end, so the result is sample-aligned with `ffmpeg -i file -f f32le`.
// Also reads RIFF WAVE with PCM or float samples, mono or stereo, as is, and Ogg Vorbis
// (stb_vorbis; sample-aligned with ffmpeg). Throws data::LoadError for anything else, naming the format (MP4,
// FLAC, ...) when it is recognised; a recognised container is never scanned for MP3 frames.
Pcm decode_audio(std::span<const uint8_t> bytes);

// The container a file's magic bytes name ("Ogg", "WAV", "FLAC", "MP4", "Matroska/WebM"), or "" (MP3, with or
// without an ID3v2 tag, has no such magic).
std::string container_format(std::span<const uint8_t> bytes);

// What the headers of an MP3 file promise, without decoding.
struct Mp3Layout {
    int32_t rate = 0, channels = 0;
    size_t first_frame = 0;      // byte offset of the first audio frame (after ID3v2 and the Xing/Info frame)
    uint64_t skip = 0;           // leading decoded sample frames to drop (0 without a LAME tag)
    uint64_t length = 0;         // sample frames the decoder outputs after the drop
};
// nullopt when `bytes` holds no Layer III frame.
std::optional<Mp3Layout> scan_mp3(std::span<const uint8_t> bytes);

}  // namespace sr::audio
