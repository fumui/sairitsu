// srun's dump format (tools/srun/dump.h) and its sha256.
#include <bit>
#include <cstdint>
#include <limits>
#include <string>
#include <vector>

#include "doctest.h"
#include "modes/presentation.h"
#include "srun/dump.h"
#include "srun/sha256.h"
#include "srun/watch.h"

using namespace sr;

namespace {

float f(uint32_t bits) { return std::bit_cast<float>(bits); }

modes::DrawCmd quad(int32_t sprite, float cx, float cy, float half) {
    modes::DrawCmd d{};
    d.sprite = sprite;
    const float xs[4] = {cx - half, cx - half, cx + half, cx + half};
    const float ys[4] = {cy - half, cy + half, cy + half, cy - half};
    for (int i = 0; i < 4; ++i) d.x[i] = xs[i], d.y[i] = ys[i], d.z[i] = 1;
    d.a = 1;
    return d;
}

}  // namespace

TEST_SUITE("tools") {

TEST_CASE("sha256 of known vectors") {
    CHECK(tools::sha256_hex("") == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    CHECK(tools::sha256_hex("abc") == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    // Two blocks, and an update split across a block boundary.
    const std::string long_text = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq";
    CHECK(tools::sha256_hex(long_text) == "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1");
    tools::Sha256 split;
    split.update(long_text.substr(0, 7));
    split.update(long_text.substr(7));
    CHECK(split.hex() == "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1");
    std::string million(1000000, 'a');
    CHECK(tools::sha256_hex(million) == "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0");
}

TEST_CASE("hex floats are exact and the same on every C library") {
    CHECK(tools::hex_float(0.0f) == "0x0p+0");
    CHECK(tools::hex_float(-0.0f) == "-0x0p+0");
    CHECK(tools::hex_float(1.0f) == "0x1p+0");
    CHECK(tools::hex_float(3.0f) == "0x1.8p+1");
    CHECK(tools::hex_float(-0.1f) == "-0x1.99999ap-4");
    CHECK(tools::hex_float(f(0x00000001)) == "0x1p-149");      // smallest subnormal, normal as a double
    CHECK(tools::hex_float(f(0x00400000)) == "0x1p-127");
    CHECK(tools::hex_float(f(0x7F7FFFFF)) == "0x1.fffffep+127");
    CHECK(tools::hex_float(std::numeric_limits<float>::infinity()) == "inf");
    CHECK(tools::hex_float(-std::numeric_limits<float>::infinity()) == "-inf");
    // Every NaN prints alike (x86_64 makes 0xFFC00000 where arm64 makes 0x7FC00000).
    CHECK(tools::hex_float(f(0x7FC00000)) == "nan");
    CHECK(tools::hex_float(f(0xFFC00000)) == "nan");
    CHECK(tools::hex_float(f(0x7F800001)) == "nan");
}

TEST_CASE("frame line lists draws, audio, particles and stats") {
    modes::FrameOutput frame;
    modes::DrawCmd d = quad(3, 0, 0, 0.5f);
    d.z[0] = 2;
    d.a = f(0xFFC00000);  // a NaN
    d.entity = 7;
    frame.draws.push_back(d);
    modes::DrawCmd c = quad(1, 1, 1, 1);
    c.curve = modes::DrawCmd::BT;
    c.segments = 8;
    c.curve_args[0] = 0.25f;
    c.entity = 65535;
    frame.draws.push_back(c);
    frame.audio.push_back(modes::AudioCmd{modes::AudioCmd::PlayScheduled, 2, 1.5f, 0.25f, 0});
    modes::ParticleCmd p{};
    p.op = modes::ParticleCmd::Spawn;
    p.handle = 257;
    p.effect = 0;
    p.duration = 0.5f;
    p.looped = true;
    frame.particles.push_back(p);
    frame.stats.combo = 4;
    frame.ended = true;

    std::string line;
    tools::append_frame(line, 0.5f, frame);
    REQUIRE(!line.empty());
    CHECK(line.back() == '\n');
    CHECK(line.find('\n') == line.size() - 1);
    CHECK(line.rfind("{\"t\":\"0x1p-1\",\"draws\":[", 0) == 0);
    // sprite, z, a, x0, y0 .. x3, y3, z2, z3, z4, entity
    CHECK(line.find("[3,\"0x1p+1\",\"nan\",\"-0x1p-1\",\"-0x1p-1\",\"-0x1p-1\",\"0x1p-1\",\"0x1p-1\",\"0x1p-1\","
                    "\"0x1p-1\",\"-0x1p-1\",\"0x1p+0\",\"0x1p+0\",\"0x1p+0\",7]") != std::string::npos);
    // a curved draw appends curve, segments and the four curve arguments
    CHECK(line.find(",65535,5,\"0x1p+3\",\"0x1p-2\",\"0x0p+0\",\"0x0p+0\",\"0x0p+0\"]") != std::string::npos);
    CHECK(line.find("\"audio\":[[0,2,\"0x1.8p+0\",\"0x1p-2\",\"0x0p+0\"]]") != std::string::npos);
    CHECK(line.find("\"particles\":[[0,\"0x1.01p+8\",0,") != std::string::npos);
    CHECK(line.find("\"stats\":{\"combo\":4,") != std::string::npos);
    CHECK(line.find("\"ended\":true}") != std::string::npos);

    // NaN bits don't change the line.
    std::string other;
    frame.draws[0].a = f(0x7FC00000);
    tools::append_frame(other, 0.5f, frame);
    CHECK(other == line);
}

TEST_CASE("fixed rate clock runs until a frame ends") {
    const tools::FrameClock clock = tools::fixed_rate(120);
    // From where live playback starts (-kPreRoll = -2 s).
    CHECK(*clock(0, false) == -2);
    CHECK(*clock(1, false) == doctest::Approx(-2 + 1.0 / 120));
    CHECK(*clock(240, false) == 0);
    CHECK_FALSE(clock(241, true).has_value());
    const tools::FrameClock listed = tools::listed({0.5, 0.25});
    CHECK(*listed(1, true) == 0.25);
    CHECK_FALSE(listed(2, false).has_value());
}

}  // TEST_SUITE
