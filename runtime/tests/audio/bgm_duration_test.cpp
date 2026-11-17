// BGM playing time from file headers (audio/bgm_duration.h): synthetic MP3 and WAV files
// against ffprobe's durations.
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#include "audio/bgm_duration.h"
#include "audio/decode.h"
#include "data/resources.h"
#include "doctest.h"

using sr::audio::bgm_duration;

namespace {

using Bytes = std::vector<uint8_t>;

void put32be(Bytes& b, size_t at, uint32_t v) {
    for (int i = 0; i < 4; ++i) b[at + size_t(i)] = uint8_t(v >> (24 - 8 * i));
}

void append32le(Bytes& b, uint32_t v) {
    for (int i = 0; i < 4; ++i) b.push_back(uint8_t(v >> (8 * i)));
}

void append16le(Bytes& b, uint16_t v) {
    b.push_back(uint8_t(v));
    b.push_back(uint8_t(v >> 8));
}

void append(Bytes& b, const std::string& text) { b.insert(b.end(), text.begin(), text.end()); }

// `count` MPEG-1 Layer III frames, 128 kbit/s, 44.1 kHz, stereo, no padding: 417 bytes and 1,152 samples each.
Bytes mpeg1_frames(int count) {
    Bytes out;
    for (int i = 0; i < count; ++i) {
        Bytes frame(417, 0);
        frame[0] = 0xFF, frame[1] = 0xFB, frame[2] = 0x90, frame[3] = 0x00;
        out.insert(out.end(), frame.begin(), frame.end());
    }
    return out;
}

// `count` MPEG-2 Layer III frames, 64 kbit/s, 22.05 kHz, mono, padded: 72 * 64000 / 22050 + 1 = 209 bytes and
// 576 samples each.
Bytes mpeg2_frames(int count) {
    Bytes out;
    for (int i = 0; i < count; ++i) {
        Bytes frame(209, 0);
        frame[0] = 0xFF, frame[1] = 0xF3, frame[2] = 0x82, frame[3] = 0xC0;
        out.insert(out.end(), frame.begin(), frame.end());
    }
    return out;
}

Bytes id3(size_t payload) {
    Bytes out = {'I', 'D', '3', 4, 0, 0};
    for (int shift = 21; shift >= 0; shift -= 7) out.push_back(uint8_t((payload >> shift) & 0x7F));  // syncsafe
    out.resize(out.size() + payload, 0);
    return out;
}

}  // namespace

TEST_SUITE("audio") {
TEST_CASE("mp3 duration counts frames") {
    CHECK(*bgm_duration(mpeg1_frames(100)) == doctest::Approx(100 * 1152 / 44100.0).epsilon(1e-12));
    CHECK(*bgm_duration(mpeg2_frames(50)) == doctest::Approx(50 * 576 / 22050.0).epsilon(1e-12));

    // After an ID3v2 tag, with junk bytes before the first frame and a truncated last frame (not counted).
    Bytes file = id3(300);
    file.push_back(0x00);
    file.push_back(0xFF);
    const Bytes frames = mpeg1_frames(10);
    file.insert(file.end(), frames.begin(), frames.end() - 100);
    CHECK(*bgm_duration(file) == doctest::Approx(9 * 1152 / 44100.0).epsilon(1e-12));
}

TEST_CASE("mp3 duration from a Xing or Info header") {
    for (const char* tag : {"Xing", "Info"}) {
        CAPTURE(tag);
        Bytes file = mpeg1_frames(3);
        // MPEG-1 stereo: the tag follows the 4-byte header and 32 bytes of side information.
        for (int i = 0; i < 4; ++i) file[36 + size_t(i)] = uint8_t(tag[i]);
        put32be(file, 40, 0x1);  // flags: frame count present
        put32be(file, 44, 5000);
        CHECK(*bgm_duration(file) == doctest::Approx(5000 * 1152 / 44100.0).epsilon(1e-12));
    }
}

TEST_CASE("wav duration from the data chunk") {
    Bytes file;
    append(file, "RIFF");
    append32le(file, 0);  // size: not used
    append(file, "WAVE");
    append(file, "LIST");
    append32le(file, 3);  // odd-sized chunk: padded to 4
    append(file, "abc");
    file.push_back(0);
    append(file, "fmt ");
    append32le(file, 16);
    append16le(file, 1);       // PCM
    append16le(file, 2);       // channels
    append32le(file, 48000);   // sample rate
    append32le(file, 192000);  // byte rate
    append16le(file, 4);
    append16le(file, 16);
    append(file, "data");
    append32le(file, 96000);
    file.resize(file.size() + 96000, 0);
    CHECK(*bgm_duration(file) == doctest::Approx(0.5).epsilon(1e-12));
}

TEST_CASE("no duration for other data") {
    CHECK_FALSE(bgm_duration(Bytes{}).has_value());
    CHECK_FALSE(bgm_duration(Bytes(1000, 0x00)).has_value());
    Bytes ogg = {'O', 'g', 'g', 'S'};
    ogg.resize(4000, 0);
    CHECK_FALSE(bgm_duration(ogg).has_value());
    Bytes riff = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'};  // no fmt or data chunk
    CHECK_FALSE(bgm_duration(riff).has_value());
    // A recognised container is never scanned for MP3 frames, even when it holds some.
    Bytes mp4 = {0, 0, 0, 16, 'f', 't', 'y', 'p', 'M', '4', 'A', ' ', 0, 0, 2, 0};
    const Bytes frames = mpeg1_frames(10);
    mp4.insert(mp4.end(), frames.begin(), frames.end());
    CHECK_FALSE(bgm_duration(mp4).has_value());
}

TEST_CASE("bgm length falls back to the decoded length") {
    Bytes mp4 = {0, 0, 0, 16, 'f', 't', 'y', 'p', 'M', '4', 'A', ' ', 0, 0, 2, 0};
    sr::audio::Pcm pcm;
    CHECK_FALSE(sr::audio::bgm_length(mp4, pcm).has_value());
    pcm.rate = 48000, pcm.channels = 2;
    pcm.samples.assign(2 * 72000, 0.0f);
    CHECK(*sr::audio::bgm_length(mp4, pcm) == 1.5);
    // Headers win when they give a length.
    CHECK(*sr::audio::bgm_length(mpeg1_frames(10), pcm) == doctest::Approx(10 * 1152 / 44100.0).epsilon(1e-12));
}

TEST_CASE("ogg vorbis duration from the last granule position") {
    // The length decode_audio() and ffmpeg give: the final page's granule over the identification header's rate.
    std::ifstream in(std::filesystem::path(SR_TEST_FIXTURES) / "audio" / "tone_vorbis.ogg", std::ios::binary);
    REQUIRE(bool(in));
    const Bytes file((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    REQUIRE(bgm_duration(file).has_value());
    CHECK(*bgm_duration(file) == doctest::Approx(33075 / 22050.0).epsilon(1e-12));
    // Vorbis data that happens to hold Layer III-looking bytes stays Ogg.
    Bytes tampered = file;
    const Bytes frames = mpeg1_frames(10);
    tampered.insert(tampered.begin() + 200, frames.begin(), frames.end());
    CHECK(bgm_duration(tampered).value_or(0) != doctest::Approx(10 * 1152 / 44100.0));
}
}  // TEST_SUITE
