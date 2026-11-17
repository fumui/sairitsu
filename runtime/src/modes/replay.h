#pragma once

#include <cstdint>
#include <span>
#include <string>
#include <utility>
#include <vector>

namespace sr::modes {

struct ReplayPoint {
    double t;     // music time (s), input offset already removed
    float x, y;   // screen units (y up)
};

struct ReplayTouch {
    std::vector<ReplayPoint> points;  // first = start, last = end
};

struct ReplayInput {
    int32_t entity;
    float time, judgment, accuracy;
};

struct ReplayStream {
    int32_t id;
    std::vector<float> keys, values;
};

struct ReplayResult {
    std::string grade;
    int32_t arcade = 0, accuracy = 0, combo = 0, perfect = 0, great = 0, good = 0, miss = 0, total = 0;
};

struct Replay {
    double duration = 0;
    float input_offset = 0;
    int32_t width = 0, height = 0;
    std::vector<ReplayTouch> touches;
    std::vector<ReplayInput> inputs;  // in file order
    std::vector<std::vector<std::pair<std::string, float>>> exports;  // per level entity: entities[i].data
    std::vector<ReplayStream> streams;
    ReplayResult result;
};

// Gzip JSON replay. Throws data::LoadError.
Replay parse_replay(std::span<const uint8_t> file);

// Gzip JSON config: `options` in order. Throws data::LoadError.
std::vector<float> parse_replay_config(std::span<const uint8_t> file);

}  // namespace sr::modes
