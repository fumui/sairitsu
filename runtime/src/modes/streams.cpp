#include "modes/streams.h"

#include <iterator>

namespace sr::modes {

void StreamStore::set(int32_t id, float key, float value) { streams_[id][key] = value; }

float StreamStore::has(int32_t id, float key) const {
    const auto s = streams_.find(id);
    return s != streams_.end() && s->second.count(key) != 0 ? 1.0f : 0.0f;
}

float StreamStore::value(int32_t id, float key) const {
    const auto s = streams_.find(id);
    if (s == streams_.end() || s->second.empty()) return 0;
    const std::map<float, float>& m = s->second;
    const auto after = m.lower_bound(key);  // first entry with a key >= `key`
    if (after == m.end()) return m.rbegin()->second;
    if (after->first == key || after == m.begin()) return after->second;
    const auto before = std::prev(after);
    // Inference: float32 linear interpolation (the wiki says only "interpolated").
    const float t = (key - before->first) / (after->first - before->first);
    return before->second + (after->second - before->second) * t;
}

float StreamStore::next_key(int32_t id, float key) const {
    const auto s = streams_.find(id);
    if (s == streams_.end()) return key;
    const auto it = s->second.upper_bound(key);
    return it == s->second.end() ? key : it->first;
}

float StreamStore::previous_key(int32_t id, float key) const {
    const auto s = streams_.find(id);
    if (s == streams_.end()) return key;
    const auto it = s->second.lower_bound(key);
    return it == s->second.begin() ? key : std::prev(it)->first;
}

void StreamStore::load(const std::vector<ReplayStream>& streams) {
    for (const ReplayStream& s : streams)
        for (size_t i = 0; i < s.keys.size() && i < s.values.size(); ++i) set(s.id, s.keys[i], s.values[i]);
}

std::vector<ReplayStream> StreamStore::dump() const {
    std::vector<ReplayStream> out;
    for (const auto& [id, entries] : streams_) {
        ReplayStream s;
        s.id = id;
        for (const auto& [key, value] : entries) {
            s.keys.push_back(key);
            s.values.push_back(value);
        }
        out.push_back(std::move(s));
    }
    return out;
}

}  // namespace sr::modes
