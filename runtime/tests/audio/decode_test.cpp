// Audio decoding and resampling (audio/decode.h): sample alignment against ffmpeg.
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <optional>
#include <string>
#include <vector>

#include "audio/decode.h"
#include "audio/platform_decode.h"
#include "audio/resample.h"
#include "data/resources.h"
#include "doctest.h"

using sr::audio::Pcm;

namespace {

using Bytes = std::vector<uint8_t>;

Bytes read_bytes(const std::filesystem::path& path) {
    std::ifstream in(path, std::ios::binary);
    REQUIRE_MESSAGE(bool(in), "cannot open ", path.string());
    return Bytes((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
}

std::vector<float> read_f32(const std::filesystem::path& path) {
    const Bytes bytes = read_bytes(path);
    std::vector<float> out(bytes.size() / 4);
    std::memcpy(out.data(), bytes.data(), out.size() * 4);
    return out;
}

// The ffmpeg that decodes the reference output: $SR_FFMPEG, else the one on PATH.
std::string ffmpeg_command() {
#if defined(__clang__)
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"  // MSVC CRT: getenv "may be unsafe"
#endif
    const char* value = std::getenv("SR_FFMPEG");
#if defined(__clang__)
#pragma clang diagnostic pop
#endif
    return value != nullptr && *value != '\0' ? std::string("\"") + value + "\"" : "ffmpeg";
}

// `ffmpeg -i file -f f32le` (source rate and channels); nullopt when ffmpeg cannot be run.
std::optional<std::vector<float>> ffmpeg_decode(const std::filesystem::path& file) {
    const std::filesystem::path out = std::filesystem::temp_directory_path() / "sr_decode_test.f32";
    std::filesystem::remove(out);
    std::string cmd = ffmpeg_command() + " -v error -y -i \"" + file.string() + "\" -f f32le \"" + out.string() + "\"";
#ifdef _WIN32
    cmd = "\"" + cmd + "\"";  // cmd.exe /c strips one pair of outer quotes
#endif
    if (std::system(cmd.c_str()) != 0 || !std::filesystem::exists(out)) return std::nullopt;
    auto samples = read_f32(out);
    std::filesystem::remove(out);
    return samples;
}

// The lag in [-8, 8] frames at which `a` best matches `b` (largest dot product over the overlap).
int best_lag(const Pcm& a, const std::vector<float>& b) {
    const size_t ch = size_t(a.channels);
    int best = 0;
    double best_score = -1e300;
    for (int lag = -8; lag <= 8; ++lag) {
        double score = 0;
        for (size_t i = 0; i < a.samples.size(); ++i) {
            const long j = long(i) + long(lag) * long(ch);
            if (j >= 0 && size_t(j) < b.size()) score += double(a.samples[i]) * double(b[size_t(j)]);
        }
        if (score > best_score) best_score = score, best = lag;
    }
    return best;
}

float max_abs_diff(const std::vector<float>& a, const std::vector<float>& b) {
    float worst = 0;
    for (size_t i = 0; i < a.size() && i < b.size(); ++i) worst = std::max(worst, std::fabs(a[i] - b[i]));
    return worst;
}

// Goertzel magnitude of left-channel samples at `hz`.
double tone_power(const Pcm& pcm, double hz) {
    const double w = 2 * 3.14159265358979323846 * hz / pcm.rate;
    const double c = 2 * std::cos(w);
    double s1 = 0, s2 = 0;
    for (size_t i = 0; i < pcm.samples.size(); i += size_t(pcm.channels)) {
        const double s = double(pcm.samples[i]) + c * s1 - s2;
        s2 = s1, s1 = s;
    }
    return s1 * s1 + s2 * s2 - c * s1 * s2;
}

}  // namespace

TEST_SUITE("audio") {
TEST_CASE("mp3 matches ffmpeg sample aligned") {
    const std::filesystem::path dir = std::filesystem::path(SR_TEST_FIXTURES) / "audio";
    const Pcm pcm = sr::audio::decode_audio(read_bytes(dir / "tone_lame.mp3"));
    const std::vector<float> expected = read_f32(dir / "tone_lame.f32");
    CHECK(pcm.rate == 44100);
    CHECK(pcm.channels == 1);
    REQUIRE(pcm.samples.size() == expected.size());
    CHECK(best_lag(pcm, expected) == 0);
    CHECK(max_abs_diff(pcm.samples, expected) < 1e-3f);
}

TEST_CASE("ogg vorbis matches ffmpeg sample aligned") {
    // Community BGMs include Ogg Vorbis
    // (`-f s16le -acodec pcm_s16le -ac 2 -ar {rate}` in the 1.1.4 metadata), so ffmpeg's length is the target.
    const std::filesystem::path dir = std::filesystem::path(SR_TEST_FIXTURES) / "audio";
    const Pcm pcm = sr::audio::decode_audio(read_bytes(dir / "tone_vorbis.ogg"));
    const std::vector<float> expected = read_f32(dir / "tone_vorbis.f32");
    // 1.5 s over 4 Ogg pages. (A file whose only audio page is also its last makes ffmpeg stop one first packet
    // short of the granule; real BGMs span many pages and ffmpeg decodes exactly up to the last granule.)
    CHECK(pcm.rate == 22050);
    CHECK(pcm.channels == 1);
    REQUIRE_EQ(pcm.samples.size(), expected.size());
    CHECK(best_lag(pcm, expected) == 0);
    CHECK(max_abs_diff(pcm.samples, expected) < 1e-3f);
}

TEST_CASE("an mp4 file is named, never scanned as mp3") {
    // AAC data can hold bytes that look like Layer III frame headers: an M4A BGM used to decode as a few seconds
    // of noise (and end the level early) instead of failing.
    const std::filesystem::path dir = std::filesystem::path(SR_TEST_FIXTURES) / "audio";
    CHECK_THROWS_WITH_AS(sr::audio::decode_audio(read_bytes(dir / "tone_aac.m4a")), doctest::Contains("MP4"),
                         sr::data::LoadError);
    // The worst case: an MP4 (ftyp box first) whose payload holds real Layer III frames.
    Bytes mp4 = {0, 0, 0, 16, 'f', 't', 'y', 'p', 'M', '4', 'A', ' ', 0, 0, 2, 0};
    const Bytes mp3 = read_bytes(dir / "tone_lame.mp3");
    mp4.insert(mp4.end(), mp3.begin(), mp3.end());
    CHECK_THROWS_WITH_AS(sr::audio::decode_audio(mp4), doctest::Contains("MP4"), sr::data::LoadError);
}

TEST_CASE("platform decode of m4a matches ffmpeg sample aligned (device only)") {
    // Android: MediaCodec decodes the M4A BGMs decode_audio() does not read, with the encoder delay and padding
    // dropped as ffmpeg drops them. The host has no platform decoder.
    const std::filesystem::path dir = std::filesystem::path(SR_TEST_FIXTURES) / "audio";
    std::string error;
    const std::optional<Pcm> pcm = sr::audio::platform_decode((dir / "tone_aac.m4a").string(), error);
#if defined(__ANDROID__)
    REQUIRE_MESSAGE(pcm.has_value(), error);
    const std::vector<float> expected = read_f32(dir / "tone_aac.f32");
    CHECK(pcm->rate == 44100);
    CHECK(pcm->channels == 1);
    REQUIRE_EQ(pcm->samples.size(), expected.size());
    CHECK(best_lag(*pcm, expected) == 0);
    CHECK(max_abs_diff(pcm->samples, expected) < 2e-2f);  // another AAC decoder than ffmpeg's
#else
    CHECK_FALSE(pcm.has_value());
    CHECK_FALSE(error.empty());
#endif
}

TEST_CASE("platform decode trims only the delay and padding the decoder left in") {
    using sr::audio::decoder_trim;
    // A decoder that drops nothing: all of the delay from the start, the padding from the end.
    CHECK(decoder_trim(8772608, 8772608, 2048, 104).start == 2048);
    CHECK(decoder_trim(8772608, 8772608, 2048, 104).end == 104);
    // Android's AAC decoder applies encoder-delay / encoder-padding itself (a Bestdori M4A on an Android tablet:
    // 8,567 units of 1,024 frames came out as 8,770,456, ffmpeg's length): nothing is left to drop.
    CHECK(decoder_trim(8772608, 8770456, 2048, 104).start == 0);
    CHECK(decoder_trim(8772608, 8770456, 2048, 104).end == 0);
    // A decoder that drops only the delay leaves the padding.
    CHECK(decoder_trim(8772608, 8770560, 2048, 104).start == 0);
    CHECK(decoder_trim(8772608, 8770560, 2048, 104).end == 104);
    // Unknown input length (untrimmed == kept), or more dropped than declared: no negative trims.
    CHECK(decoder_trim(100, 100, 0, 0).start == 0);
    CHECK(decoder_trim(100, 50, 10, 0).start == 0);
    CHECK(decoder_trim(100, 50, 10, 0).end == 0);
}

TEST_CASE("wav decodes at unity gain") {
    // The decode gain (unity) applies to
    // the WAV path as to the MP3 one ("mp3 matches ffmpeg sample aligned").
    Bytes wav = {'R', 'I', 'F', 'F', 40, 0, 0, 0, 'W', 'A', 'V', 'E', 'f', 'm', 't', ' ', 16, 0, 0, 0,
                 1, 0, 1, 0, 0x80, 0xBB, 0, 0, 0, 0x77, 1, 0, 2, 0, 16, 0, 'd', 'a', 't', 'a', 4, 0, 0, 0,
                 0x00, 0x40, 0x00, 0xC0};  // 48 kHz mono 16-bit: 16384, -16384
    const Pcm pcm = sr::audio::decode_audio(wav);
    CHECK(pcm.rate == 48000);
    REQUIRE(pcm.samples.size() == 2);
    CHECK(pcm.samples[0] == 0.5f);
    CHECK(pcm.samples[1] == -0.5f);
}

TEST_CASE("decode names an unsupported format") {
    Bytes ogg = {'O', 'g', 'g', 'S'};
    ogg.resize(4000, 0);
    try {
        sr::audio::decode_audio(ogg);  // an Ogg page header with nothing Vorbis in it
        FAIL("expected a LoadError");
    } catch (const sr::data::LoadError& e) {
        CHECK(std::string(e.what()).find("Ogg") != std::string::npos);
    }
    Bytes wav = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'};
    CHECK_THROWS_WITH_AS(sr::audio::decode_audio(wav), doctest::Contains("WAV"), sr::data::LoadError);
    CHECK_THROWS_AS(sr::audio::decode_audio(Bytes(100, 0x12)), sr::data::LoadError);
}

TEST_CASE("resample 44100 to 48000 keeps length and pitch") {
    const std::filesystem::path dir = std::filesystem::path(SR_TEST_FIXTURES) / "audio";
    const Pcm tone = sr::audio::decode_audio(read_bytes(dir / "tone_lame.mp3"));
    const Pcm out = sr::audio::resample(tone, 48000);
    CHECK(out.rate == 48000);
    CHECK(out.channels == 2);
    CHECK(out.samples.size() == 48000u * 2);
    // Both channels carry the mono signal.
    for (size_t i = 0; i < out.samples.size(); i += 2) REQUIRE(out.samples[i] == out.samples[i + 1]);
    double best_power = 0, best_hz = 0;
    for (double hz = 400; hz <= 480; hz += 1) {
        const double p = tone_power(out, hz);
        if (p > best_power) best_power = p, best_hz = hz;
    }
    CHECK(best_hz == 440);
    // Equal rates pass through (stereo).
    const Pcm same = sr::audio::resample(tone, 44100);
    CHECK(same.channels == 2);
    CHECK(same.samples.size() == tone.samples.size() * 2);
}
}  // TEST_SUITE
