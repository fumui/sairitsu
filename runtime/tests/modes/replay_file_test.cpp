// Replay files and the replay slot (modes/replay_file.h): the files a Play run leaves and the status Kotlin polls.
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <limits>
#include <random>
#include <string>
#include <vector>

#include "doctest.h"
#include "modes/replay.h"
#include "modes/replay_file.h"
#include "modes/replay_writer.h"

using namespace sr;
namespace fs = std::filesystem;

namespace {

// A fresh, empty directory under the system temp directory, removed when the test ends.
struct TempDir {
    fs::path path;
    TempDir() {
        std::random_device rd;
        path = fs::temp_directory_path() / ("sr_replay_file_" + std::to_string(rd()) + std::to_string(rd()));
        fs::create_directories(path);
    }
    ~TempDir() {
        std::error_code ec;
        fs::remove_all(path, ec);
    }
};

std::vector<uint8_t> read_bytes(const fs::path& p) {
    std::ifstream in(p, std::ios::binary);
    return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}

modes::ReplayData sample_data() {
    modes::ReplayData d;
    d.duration = 12.5;
    d.input_offset = 0.01f;
    d.width = 3200;
    d.height = 2136;
    d.result.combo = 3;
    d.result.perfect = 3;
    d.result.total = 3;
    d.inputs.push_back(modes::StatInput{});
    d.entities.resize(2);
    d.entities[1].emplace_back("judgment", 1.0f);
    modes::RecordedTouch touch;
    touch.points = {{1000, 10, -20}, {1008, 12, -20}};
    d.touches.push_back(touch);
    return d;
}

size_t count_files(const fs::path& dir) {
    size_t n = 0;
    for (const auto& entry : fs::recursive_directory_iterator(dir)) n += entry.is_regular_file() ? 1 : 0;
    return n;
}

}  // namespace

TEST_CASE("write_replay_files writes both files that parse back") {
    TempDir dir;
    const fs::path base = dir.path / "last";
    const std::vector<float> options{1.0f, 0.5f, 2.0f};
    REQUIRE(modes::write_replay_files(base, sample_data(), options));

    const modes::Replay replay = modes::parse_replay(read_bytes(dir.path / "last.json.gz"));
    CHECK(replay.duration == doctest::Approx(12.5));
    CHECK(replay.width == 3200);
    CHECK(replay.height == 2136);
    REQUIRE(replay.touches.size() == 1);
    CHECK(replay.touches[0].points.size() == 2);
    CHECK(replay.result.combo == 3);
    CHECK(modes::parse_replay_config(read_bytes(dir.path / "last.config.gz")) == options);
    CHECK(!fs::exists(dir.path / "last.json.gz.part"));
    CHECK(!fs::exists(dir.path / "last.config.gz.part"));
    CHECK(count_files(dir.path) == 2);

    // A second write replaces the pair.
    modes::ReplayData second = sample_data();
    second.duration = 3;
    REQUIRE(modes::write_replay_files(base, second, std::vector<float>{4.0f}));
    CHECK(modes::parse_replay(read_bytes(dir.path / "last.json.gz")).duration == doctest::Approx(3));
    CHECK(modes::parse_replay_config(read_bytes(dir.path / "last.config.gz")) == std::vector<float>{4.0f});
    CHECK(count_files(dir.path) == 2);
}

TEST_CASE("write_replay_files into a missing directory returns false and leaves nothing") {
    TempDir dir;
    const fs::path base = dir.path / "missing" / "last";
    CHECK(!modes::write_replay_files(base, sample_data(), std::vector<float>{1.0f}));
    CHECK(!fs::exists(dir.path / "missing"));
    CHECK(count_files(dir.path) == 0);
}

TEST_CASE("write_replay_files of a value it can't write returns false and leaves nothing") {
    TempDir dir;
    modes::ReplayData bad = sample_data();
    bad.duration = std::numeric_limits<double>::quiet_NaN();
    CHECK(!modes::write_replay_files(dir.path / "last", bad, std::vector<float>{1.0f}));
    CHECK(count_files(dir.path) == 0);
}

TEST_CASE("slot reports the latest write") {
    modes::ReplaySlot slot;
    CHECK(slot.status() == modes::ReplayStatus::None);
    const uint64_t t1 = slot.begin();
    CHECK(slot.status() == modes::ReplayStatus::Writing);
    slot.finish(t1, true);
    CHECK(slot.status() == modes::ReplayStatus::Ready);
    const uint64_t t2 = slot.begin();
    CHECK(slot.status() == modes::ReplayStatus::Writing);
    slot.finish(t2, false);
    CHECK(slot.status() == modes::ReplayStatus::Failed);
}

TEST_CASE("slotIgnoresFinishOfOlderToken") {
    modes::ReplaySlot slot;
    const uint64_t t1 = slot.begin();
    slot.reset();
    const uint64_t t2 = slot.begin();
    slot.finish(t1, true);
    CHECK(slot.status() == modes::ReplayStatus::Writing);
    slot.finish(t2, true);
    CHECK(slot.status() == modes::ReplayStatus::Ready);
    const uint64_t t3 = slot.begin();
    slot.reset();
    slot.finish(t3, true);
    CHECK(slot.status() == modes::ReplayStatus::None);
}

TEST_CASE("slot write of a token that is no longer the latest writes nothing") {
    TempDir dir;
    modes::ReplaySlot slot;
    const uint64_t t1 = slot.begin();
    slot.reset();
    CHECK(!slot.write(t1, dir.path / "last", sample_data(), std::vector<float>{1.0f}));
    CHECK(slot.status() == modes::ReplayStatus::None);
    CHECK(count_files(dir.path) == 0);

    const uint64_t t2 = slot.begin();
    CHECK(slot.write(t2, dir.path / "last", sample_data(), std::vector<float>{1.0f}));
    CHECK(slot.status() == modes::ReplayStatus::Ready);
    CHECK(count_files(dir.path) == 2);

    const uint64_t t3 = slot.begin();
    CHECK(!slot.write(t3, dir.path / "missing" / "last", sample_data(), std::vector<float>{1.0f}));
    CHECK(slot.status() == modes::ReplayStatus::Failed);
}
