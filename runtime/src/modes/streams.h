#pragma once

#include <cstdint>
#include <map>
#include <vector>

#include "modes/replay.h"

namespace sr::modes {

// The Stream* functions' storage (wiki functions__stream-*): per id, entries kept sorted by key. std::map, not
// unordered_map.
class StreamStore {
public:
    // Sets the entry at `key`, replacing a value already there.
    void set(int32_t id, float key, float value);
    // 1 if the stream has an entry at exactly `key`, else 0.
    float has(int32_t id, float key) const;
    // The first value if `key` is before the first key, the last value if after the last, the value at `key`, or
    // between two keys the linear interpolation; 0 for an empty or unknown stream.
    float value(int32_t id, float key) const;
    // The smallest key above `key`, or `key` when there is none.
    float next_key(int32_t id, float key) const;
    // The largest key below `key`, or `key` when there is none.
    float previous_key(int32_t id, float key) const;
    void clear() { streams_.clear(); }
    // Fills the store from a replay's streams (the keys are already summed).
    void load(const std::vector<ReplayStream>& streams);
    // Every stream in id order, for comparing with a replay.
    std::vector<ReplayStream> dump() const;

private:
    std::map<int32_t, std::map<float, float>> streams_;
};

}  // namespace sr::modes
