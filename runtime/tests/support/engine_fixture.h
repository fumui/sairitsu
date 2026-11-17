#pragma once

#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#include "data/configuration.h"
#include "data/engine_data.h"
#include "data/gzip.h"
#include "data/level_data.h"
#include "data/resources.h"
#include "doctest.h"

#ifndef SR_TEST_FIXTURES
#error "SR_TEST_FIXTURES must name runtime/tests/fixtures"
#endif

namespace sr::test {

inline std::vector<uint8_t> read_fixture_file(const std::filesystem::path& path) {
    std::ifstream in(path, std::ios::binary);
    REQUIRE_MESSAGE(bool(in), "cannot open ", path.string());
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
}

inline std::string gunzip_fixture_text(const std::filesystem::path& path) {
    const std::vector<uint8_t> bytes = data::gunzip(read_fixture_file(path));
    return std::string(bytes.begin(), bytes.end());
}

// The watch data, ROM and level data of one mini engine in fixtures/engines, built by
// `python -m sairitsu_tools build-engines` from tools/fixtures/engines/<name>.py. Everything else (skin, effect and
// particle data, configuration) is empty: a test adds what it needs.
inline data::LoadedLevel engine_fixture(const std::string& name) {
    const std::filesystem::path dir = std::filesystem::path(SR_TEST_FIXTURES) / "engines";
    data::LoadedLevel level;
    level.watch =
        data::parse_engine_mode_data(gunzip_fixture_text(dir / (name + ".watch.json.gz")), data::Mode::Watch);
    level.rom = data::parse_rom(data::gunzip(read_fixture_file(dir / (name + ".rom.gz"))));
    level.level = data::parse_level_data(gunzip_fixture_text(dir / (name + ".level.json.gz")));
    return level;
}

}  // namespace sr::test
