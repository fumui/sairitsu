#include "time/time_tables.h"

#include <algorithm>
#include <string>
#include <utility>

#include "data/load_error.h"

namespace sr::time {

namespace {

// The datum `name` of the entity: its own value, else the archetype import's default. Refs are not numbers.
float datum(const data::LevelData& level, const data::EngineModeData& engine, size_t index, const char* name) {
    const data::LevelEntity& e = level.entities[index];
    for (const data::EntityDatum& d : e.data)
        if (d.name == name) {
            if (d.is_ref) throw data::LoadError("level.data: entity " + std::to_string(index) + " (" + e.archetype + "): '" + name + "' is a ref");
            return d.value;
        }
    for (const data::Archetype& a : engine.archetypes)
        if (a.name == e.archetype)
            for (const data::Import& im : a.imports)
                if (im.name == name && im.def) return *im.def;
    throw data::LoadError("level.data: entity " + std::to_string(index) + " (" + e.archetype + ") is missing '" + name + "'");
}

// The section covering x: the last change whose start is not greater than x (a binary search
// for the first start greater than x; every change when x is NaN), or nullptr when there is none.
template <class S, class X, class Field>
const S* section(const std::vector<S>& v, X x, Field field) {
    const auto it = std::partition_point(v.begin(), v.end(), [&](const S& s) { return !(field(s) > x); });
    return it == v.begin() ? nullptr : &*(it - 1);
}

}  // namespace

TimeTables TimeTables::build(const data::LevelData& level, const data::EngineModeData& engine) {
    std::vector<std::pair<float, float>> bpm, scale;  // (beat, bpm), (beat, timescale)
    for (size_t i = 0; i < level.entities.size(); ++i) {
        const std::string& arch = level.entities[i].archetype;
        if (arch == "#BPM_CHANGE")
            bpm.emplace_back(datum(level, engine, i, "#BEAT"), datum(level, engine, i, "#BPM"));
        else if (arch == "#TIMESCALE_CHANGE")
            scale.emplace_back(datum(level, engine, i, "#BEAT"), datum(level, engine, i, "#TIMESCALE"));
    }
    auto by_first = [](const auto& a, const auto& b) { return a.first < b.first; };
    std::stable_sort(bpm.begin(), bpm.end(), by_first);
    std::stable_sort(scale.begin(), scale.end(), by_first);

    TimeTables t;
    // Double section times from the float32 beat difference.
    double time = 0.0;
    for (size_t i = 0; i < bpm.size(); ++i) {
        if (i > 0) {
            const float d = bpm[i].first - bpm[i - 1].first;
            time = time + double(d) * 60.0 / double(bpm[i - 1].second);
        }
        t.bpm_.push_back(BpmSection{bpm[i].first, bpm[i].second, time});
    }
    // Change times in double, scaled time starting at the first change's time.
    double scaled = 0.0, prev = 0.0;
    for (size_t i = 0; i < scale.size(); ++i) {
        const double at = t.beat_to_time_double(scale[i].first);
        scaled = i == 0 ? at : scaled + (at - prev) * double(t.scale_.back().scale);
        t.scale_.push_back(ScaleSection{at, scale[i].second, scaled});
        prev = at;
    }
    // Changes that tie in beat keep their order; a later beat can still land earlier in time only with a
    // non-positive BPM, which no level has.
    return t;
}

const TimeTables::BpmSection* TimeTables::bpm_at(float beat) const {
    return section(bpm_, beat, [](const BpmSection& s) { return s.beat; });
}

const TimeTables::ScaleSection* TimeTables::scale_at(float time) const {
    return section(scale_, double(time), [](const ScaleSection& s) { return s.time; });
}

double TimeTables::beat_to_time_double(float beat) const {
    const BpmSection* s = bpm_at(beat);
    const float start = s ? s->beat : 0.0f;
    const double bpm = s ? double(s->bpm) : 0.0;
    const double from = s ? s->time : 0.0;
    const float d = beat - start;
    return from + double(d) * 60.0 / bpm;  // no section: x * 60 / 0, an infinity (NaN at 0)
}

float TimeTables::beat_to_time(float beat) const { return float(beat_to_time_double(beat)); }

float TimeTables::beat_to_bpm(float beat) const {
    const BpmSection* s = bpm_at(beat);
    return s ? s->bpm : 0.0f;
}

float TimeTables::beat_to_starting_beat(float beat) const {
    const BpmSection* s = bpm_at(beat);
    return s ? s->beat : 0.0f;
}

float TimeTables::beat_to_starting_time(float beat) const {
    const BpmSection* s = bpm_at(beat);
    return s ? float(s->time) : 0.0f;
}

float TimeTables::time_to_scaled_time(float time) const {
    const ScaleSection* s = scale_at(time);
    if (!s) return time;
    return float(s->scaled + (double(time) - s->time) * double(s->scale));
}

float TimeTables::time_to_starting_scaled_time(float time) const {
    const ScaleSection* s = scale_at(time);
    return s ? float(s->scaled) : 0.0f;
}

float TimeTables::time_to_starting_time(float time) const {
    const ScaleSection* s = scale_at(time);
    return s ? float(s->time) : 0.0f;
}

float TimeTables::time_to_time_scale(float time) const {
    const ScaleSection* s = scale_at(time);
    return s ? s->scale : 1.0f;
}

}  // namespace sr::time
