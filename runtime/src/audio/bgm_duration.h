#pragma once

#include <cstdint>
#include <optional>
#include <span>

namespace sr::audio {

// The playing time in seconds of a level's BGM file, read from its headers without decoding:
// - MP3 (MPEG-1, 2 or 2.5 Layer III, after any ID3v2 tag): the length decode_audio() outputs (audio/decode.h):
//   the frame count of a Xing/Info/VBRI header in the first frame (that frame itself holds no audio, as in ffmpeg),
//   else every frame counted, less the LAME tag's encoder delay and padding.
// - WAV (RIFF WAVE): the data chunk's size over the fmt chunk's byte rate.
// - Ogg Vorbis: the last page's granule position over the identification header's rate (decode_audio's and
//   ffmpeg's length).
// nullopt for anything else (another recognised container such as MP4, which is never scanned for MP3 frames) or
// a file without a readable frame / chunk / page. Callers then use the decoded length.
std::optional<double> bgm_duration(std::span<const uint8_t> bytes);

struct Pcm;
// The BGM's length in seconds: bgm_duration(bytes), else the decoded length of `pcm` (a format with no header-only
// length, such as an M4A decoded by the platform), else nullopt.
std::optional<double> bgm_length(std::span<const uint8_t> bytes, const Pcm& pcm);

}  // namespace sr::audio
